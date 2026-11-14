// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.langchain.LangChain4jToolBridge
import io.agents.pokeclaw.agent.llm.LlmClient
import io.agents.pokeclaw.agent.llm.LlmClientFactory
import io.agents.pokeclaw.agent.llm.LlmResponse
import io.agents.pokeclaw.agent.llm.StreamingListener
import io.agents.pokeclaw.benchmark.BenchmarkRecorder
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.tool.impl.GetScreenInfoTool
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog
import io.agents.pokeclaw.agent.knowledge.MemoryManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.utils.ContactListUiUtils
import java.io.File
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.yield

class DefaultAgentService : AgentService {

    companion object {
        private const val TAG = "AgentService"
        private val GSON = Gson()

        /**
         * Optimized system prompt for on-device LLM (Gemma 4).
         * Shorter than Cloud prompt but includes essential rules.
         * Task-only — chat is handled separately.
         */
        private const val LOCAL_TASK_PROMPT = """You are Saarthi, an AI phone assistant controlling an Android phone using tools.

## Execution Rules
1. Action Tasks: Use high-level direct tools (send_message, place_call, send_distress_signal, message_care_contact; these three ask the user first: on "CONFIRMATION NEEDED" stop, never send or call another way; auto_reply needs confirmed=true after the user says yes, find_search_bar, add_to_cart, read_cart) to execute tasks end-to-end in 1 fast step whenever possible.
2. E-Commerce Cart Completion: When requested items are added to cart and the Cart Summary / "Pay ₹XX" / "View Cart" CTA is visible on screen, DO NOT perform math calculations or audit price discrepancies! (Differences like delivery fees are normal). Call finish(summary="Items added to cart. Total: ₹XX") IMMEDIATELY! Never switch tabs to audit item prices.
3. Fact / Memory Statements: If user shares a preference, confirm and call finish(summary="Got it! Noted.").
4. Precision: Never guess or tap random suggestions. When searching for contacts or items, use exact keywords from memory/request.
5. Out of Stock: If an item is out of stock, skip it immediately or call finish.
6. Completion: When done, call finish(summary="actual data or summary").
7. Strict No-Oscillation Rule: Once message contents or item lists are read from WhatsApp, NEVER re-open WhatsApp or re-navigate back to WhatsApp! Process all extracted items directly on the target store (Flipkart Minutes / Blinkit / Zepto) until checkout.
8. Multi-Option Bottom Sheet Handling: When a size/variant bottom sheet appears (e.g. 250ml / 500ml / 1L), match the requested size if specified or tap the top default option immediately in 1 step without dismissing."""

        /** Maximum number of retries on LLM API call failure */
        private const val MAX_API_RETRIES = 3
        /** Dead-loop detection: sliding window size */
        private const val LOOP_DETECT_WINDOW = 4

        /**
         * Opt-3: Action tools — after any of these execute we auto-attach a fresh
         * get_screen_info result so the LLM can see the updated UI without spending
         * an extra inference round (5 s) to call it manually.
         */
        private val ACTION_TOOLS = setOf(
            "phone_click_node", "phone_tap", "phone_swipe", "phone_long_press",
            "tap", "long_press", "swipe", "scroll_to_find",
            "input_text", "type_text", "system_key", "open_app",
            "dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center",
            "volume_up", "volume_down", "press_menu", "press_power",
            "clipboard", "send_file", "repeat_actions", "wait",
            "send_message", "tap_node", "find_and_tap", "auto_reply",
            "find_search_bar", "add_to_cart"
        )
        /** ms to wait for UI to settle before capturing screen after an action */
        private const val SCREEN_SETTLE_MS = 700L

        /** Whether to write raw network request/response data to sandbox cache files for debugging */
        @JvmField
        var FILE_LOGGING_ENABLED = false
        @JvmField
        var FILE_LOGGING_CACHE_DIR: File? = null

        @JvmStatic
        fun formatLlmError(rawError: String?): String {
            if (rawError.isNullOrBlank()) return "Unknown API error"
            val lower = rawError.lowercase()
            return when {
                lower.contains("429") || lower.contains("rate limit") || lower.contains("too many requests") ->
                    "Rate limit reached (HTTP 429). DeepSeek / OpenRouter is currently busy or rate-limited. Please wait a few seconds and try again, or switch to GLM 4 Flash in Settings -> Models."
                lower.contains("401") || lower.contains("403") || lower.contains("unauthorized") || lower.contains("invalid api key") ->
                    "Invalid API Key (HTTP 401/403). Please verify your OpenRouter / LLM API key under Settings -> Models."
                lower.contains("402") || lower.contains("insufficient") || lower.contains("credit") || lower.contains("balance") ->
                    "Insufficient credits or quota exceeded. Please check your OpenRouter / LLM account balance."
                lower.contains("500") || lower.contains("502") || lower.contains("503") || lower.contains("service unavailable") ->
                    "Provider server error (HTTP 5xx). The AI provider is temporarily unavailable. Try again in a moment."
                lower.contains("timeout") || lower.contains("timed out") ->
                    "Request timed out. The AI provider is responding too slowly."
                else -> {
                    var clean = rawError.replace(Regex("""^.*HttpException:\s*"""), "")
                    clean = clean.replace(Regex("""\{.*"message":\s*"([^"]+)".*\}"""), "$1")
                    if (clean.length > 180) clean = clean.take(180) + "..."
                    clean
                }
            }
        }
    }

    private lateinit var config: AgentConfig
    private lateinit var llmClient: LlmClient
    private lateinit var toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentAgentJob: Job? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    override fun initialize(config: AgentConfig) {
        this.config = config
        this.llmClient = LlmClientFactory.create(config)
        this.toolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        XLog.i(TAG, "Agent initialized: provider=${config.provider}, model=${config.modelName}, streaming=${config.streaming}")
    }

    override fun updateConfig(config: AgentConfig) {
        if (running.get()) {
            cancel()
            XLog.w(TAG, "Task was running during config update, cancelled")
        }
        // Close old LlmClient before reinitializing to free engine memory
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
                XLog.i(TAG, "Old LlmClient closed before config update")
            } catch (e: Exception) {
                XLog.w(TAG, "Old LlmClient close error during config update", e)
            }
        }
        initialize(config)
        XLog.i(TAG, "Agent config updated, new model: ${config.modelName}")
    }

    override fun executeTask(userPrompt: String, callback: AgentCallback) {
        // Surface RUNNING state to UI before heavy inference work starts.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback.onLoopStart(0)
        } else {
            Handler(Looper.getMainLooper()).post { callback.onLoopStart(0) }
        }

        // Atomic check-and-set: two concurrent executeTask calls must not both start.
        if (!running.compareAndSet(false, true)) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        cancelled.set(false)
        var terminalCallback: (() -> Unit)? = null

        val callbackProxy = object : AgentCallback {
            override fun onLoopStart(round: Int) = callback.onLoopStart(round)

            override fun onContent(round: Int, content: String) = callback.onContent(round, content)

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                callback.onToolCall(round, toolId, toolName, parameters)
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                callback.onToolResult(round, toolId, toolName, parameters, result)
            }

            override fun onTokenUpdate(status: TokenMonitor.Status) = callback.onTokenUpdate(status)

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String?) {
                BenchmarkRecorder.setOutcome("success", finalAnswer, round, modelName)
                terminalCallback = { callback.onComplete(round, finalAnswer, totalTokens, modelName) }
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                BenchmarkRecorder.setOutcome("fail", error.message, round)
                terminalCallback = { callback.onError(round, error, totalTokens) }
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                BenchmarkRecorder.setOutcome("fail", "system dialog blocked", round)
                terminalCallback = { callback.onSystemDialogBlocked(round, totalTokens) }
            }
        }

        // LAZY start so currentAgentJob is assigned before the body (and its finally) can run.
        val taskClient: LlmClient? = if (::llmClient.isInitialized) llmClient else null
        val job = serviceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val myJob = coroutineContext[Job]
            // Benchmark: 0 when benchmark mode is off (all later hooks then return at once).
            val benchRunId = if (::config.isInitialized) {
                BenchmarkRecorder.startTask(userPrompt, config.provider == LlmProvider.LOCAL, config.modelName, config.baseUrl)
            } else 0L
            try {
                // Give Compose one frame to render the running/stop controls.
                yield()
                delay(50)
                currentCoroutineContext().ensureActive()
                runAgentLoop(userPrompt, callbackProxy)
            } catch (_: CancellationException) {
                XLog.i(TAG, "Agent task cancelled (coroutine)")
                if (terminalCallback == null && cancelled.get()) {
                    terminalCallback = {
                        callback.onComplete(0, ClawApplication.instance.getString(R.string.agent_task_cancel), 0)
                    }
                }
            } catch (e: Exception) {
                if (terminalCallback == null) {
                    if (cancelled.get()) {
                        XLog.i(TAG, "Agent task cancelled (interrupted)")
                        terminalCallback = {
                            callback.onComplete(0, ClawApplication.instance.getString(R.string.agent_task_cancel), 0)
                        }
                    } else {
                        XLog.e(TAG, "Agent execution error", e)
                        BenchmarkRecorder.setOutcome("fail", e.message, 0)
                        terminalCallback = { callback.onError(0, e, 0) }
                    }
                }
            } finally {
                // Benchmark: write the run (no-op when off, or when a newer task already started a run).
                BenchmarkRecorder.finishTask(benchRunId, cancelled.get())
                // If a newer task has already replaced this job (cancel() then a new executeTask),
                // this stale job must not close the new task's engine, clear its job/running state,
                // or fire a stale "Task cancelled" callback into the new task's UI.
                val isCurrent = currentAgentJob === myJob
                if (isCurrent) {
                    currentAgentJob = null
                    // Close local engine BEFORE clearing running flag so the chat engine
                    // reload (triggered by onComplete/onError) never overlaps with task engine.
                    if (taskClient != null) {
                        try {
                            taskClient.close()
                            XLog.i(TAG, "LlmClient closed after task completion")
                        } catch (e: Exception) {
                            XLog.w(TAG, "LlmClient close error after task", e)
                        }
                    }
                    running.set(false)
                    val terminal = terminalCallback
                    terminalCallback = null
                    terminal?.invoke()
                } else {
                    XLog.i(TAG, "Stale agent job finished after a newer task started; skipping cleanup/callback")
                    terminalCallback = null
                }
            }
        }
        currentAgentJob = job
        job.start()
    }

    // ==================== Pre-flight Check ====================

    private fun preCheck(): String? {
        // Missing key for the selected provider: refuse, never borrow another provider's or a generic key.
        if (::config.isInitialized && config.provider != LlmProvider.LOCAL && config.apiKey.isBlank()) {
            return SpokenStrings.AI_NOT_SET_UP_EN
        }
        if (ClawAccessibilityService.getConnectedInstance(5000L) == null) {
            return ClawApplication.instance.getString(R.string.agent_accessibility_not_enabled)
        }
        return null
    }

    // ==================== Device Context ====================

    private fun buildDeviceContext(): String {
        val app = ClawApplication.instance
        val sb = StringBuilder()
        sb.append("\n\n## Device Info\n")
        sb.append("- Brand: ").append(Build.BRAND).append("\n")
        sb.append("- Model: ").append(Build.MODEL).append("\n")
        sb.append("- Android Version: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        try {
            val wm = app
                .getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            sb.append("- Screen Resolution: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get display metrics", e)
        }

        sb.append("- Registered Tools: ").append(ToolRegistry.getAllTools().size).append("\n")

        val appName = try {
            val appInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
            app.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { "Saathi" }
        sb.append("\n## This App Info\n")
        sb.append("- App Name: ").append(appName).append("\n")
        sb.append("- Package Name: ").append(app.packageName).append("\n")
        sb.append("- When the user refers to 'this app' or 'the app', they mean the app above.\n")

        return sb.toString()
    }

    // ==================== LLM Call (with retry) ====================

    private suspend fun chatWithRetry(messages: List<ChatMessage>, callback: AgentCallback, iteration: Int): LlmResponse {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_API_RETRIES) {
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
            val benchStart = if (BenchmarkRecorder.isRecording()) System.currentTimeMillis() else 0L
            try {
                val response = if (config.streaming) {
                    val textBuilder = StringBuilder()
                    runInterruptible(Dispatchers.Default) {
                        llmClient.chatStreaming(messages, toolSpecs, object : StreamingListener {
                            override fun onPartialText(token: String) {
                                textBuilder.append(token)
                                callback.onContent(iteration, token)
                            }
                            override fun onComplete(response: LlmResponse) {}
                            override fun onError(error: Throwable) {}
                        })
                    }
                } else {
                    runInterruptible(Dispatchers.Default) {
                        llmClient.chat(messages, toolSpecs)
                    }
                }
                if (benchStart != 0L) {
                    BenchmarkRecorder.onLlmCall(
                        System.currentTimeMillis() - benchStart,
                        response.tokenUsage?.inputTokenCount(),
                        response.tokenUsage?.outputTokenCount(),
                        true
                    )
                }
                return response
            } catch (e: Exception) {
                if (benchStart != 0L) BenchmarkRecorder.onLlmCall(System.currentTimeMillis() - benchStart, null, null, false)
                lastException = e
                val msg = e.message ?: ""
                // Do not retry on auth failure or balance error
                if (msg.contains("401") || msg.contains("403") || msg.contains("insufficient")) {
                    throw e
                }
                val retryDelayMs = (Math.pow(2.0, attempt.toDouble()) * 2000).toLong()
                XLog.w(TAG, "LLM API call failed (attempt ${attempt + 1}/$MAX_API_RETRIES), retrying in ${retryDelayMs}ms: $msg")
                try {
                    delay(retryDelayMs)
                } catch (_: CancellationException) {
                    throw e
                }
            }
        }
        throw lastException!!
    }

    // ==================== Recovery BACK ====================

    /** Screens that are never a task's target app (BACK out of them is fine). */
    private fun isSystemSurface(pkg: String): Boolean =
        pkg.isBlank() || pkg == ClawApplication.instance.packageName || pkg == "com.android.systemui" ||
            pkg.contains("launcher") || pkg.contains("settings") || pkg.contains("permission") ||
            pkg == "android"

    private fun foregroundPkg(service: ClawAccessibilityService): String =
        try { service.rootInActiveWindow?.packageName?.toString().orEmpty() } catch (_: Exception) { "" }

    private fun relaunchApp(pkg: String): Boolean = try {
        val ctx = ClawApplication.instance
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) false else {
            // NEW_TASK without CLEAR flags brings the app's existing task back as it was.
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        }
    } catch (e: Exception) {
        XLog.w(TAG, "relaunch of target app failed: ${e.message}")
        false
    }

    /**
     * Recovery BACK that never leaves the target app: when already outside [targetPkg] the app is
     * relaunched instead of pressing BACK; otherwise BACK is pressed and, if that took the phone out of
     * the app (it was at its root screen), the app is relaunched. Returns what was done, for the log.
     */
    private suspend fun recoveryBack(service: ClawAccessibilityService, targetPkg: String): String {
        val target = targetPkg.takeUnless { isSystemSurface(it) }
        val before = foregroundPkg(service)
        if (target != null && before != target) {
            return if (relaunchApp(target)) "relaunched" else {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); "back"
            }
        }
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        if (target == null) return "back"
        delay(700)
        val after = foregroundPkg(service)
        if (after.isNotEmpty() && after != target) {
            XLog.i(TAG, "BACK left the target app; relaunching it")
            return if (relaunchApp(target)) "back, then relaunched" else "back"
        }
        return "back"
    }

    // ==================== Dead Loop Detection ====================

    private data class RoundFingerprint(val screenHash: Int, val toolCall: String)

    private fun isStuckInLoop(history: LinkedList<RoundFingerprint>): Boolean {
        if (history.size < LOOP_DETECT_WINDOW) return false
        val first = history.first()
        return history.all { it == first }
    }

    // ==================== Context Compression ====================

    /** Protected zone: keep the most recent N rounds intact */
    private val KEEP_RECENT_ROUNDS = 3

    /** Large-output observation tools → compressed placeholder */
    private val OBSERVATION_PLACEHOLDERS = mapOf(
        "get_screen_info" to "[screen info omitted]",
        "take_screenshot" to "[screenshot result omitted]",
        "find_node_info" to "[node find result omitted]",
        "get_installed_apps" to "[app list omitted]",
        "scroll_to_find" to "[scroll find result omitted]"
    )

    /**
     * Compress history messages before sending to save input tokens:
     * - get_screen_info: keep only the latest complete result globally
     * - Protected zone (most recent KEEP_RECENT_ROUNDS rounds): keep intact
     * - Outside protected zone: keep AI thinking as-is, compress tool results to a one-line summary
     */
    private fun compressHistoryForSend(messages: MutableList<ChatMessage>) {
        // Count total characters before compression
        val charsBefore = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val msgCountBefore = messages.size

        // 0. Special handling for screen observations: keep full screen dump only on the MOST RECENT tool result globally
        val screenPlaceholder = OBSERVATION_PLACEHOLDERS["get_screen_info"]!!
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && (it.toolName() == "get_screen_info" || it.text().contains("Screen after action:"))
        }
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage && i != lastScreenIdx) {
                if (msg.toolName() == "get_screen_info" && msg.text() != screenPlaceholder) {
                    messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), screenPlaceholder)
                } else if (msg.text().contains("Screen after action:")) {
                    val cleanBase = msg.text().substringBefore("Screen after action:").trim()
                    val shortText = if (cleanBase.isBlank()) "✓ Action executed [Screen updated]" else "$cleanBase [Screen updated]"
                    messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), shortText)
                }
            }
        }

        // 1. Find indices of all AiMessages; each represents one round
        val aiIndices = messages.indices.filter { messages[it] is AiMessage }
        if (aiIndices.size <= KEEP_RECENT_ROUNDS) return

        val totalRounds = aiIndices.size

        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= KEEP_RECENT_ROUNDS) break // protected zone

            val aiIndex = aiIndices[roundIdx]
            val aiMsg = messages[aiIndex] as AiMessage

            // Prune long reasoning/thinking text from older AiMessages
            if (aiMsg.text() != null && aiMsg.text().length > 80) {
                val requests = aiMsg.toolExecutionRequests()
                val shortText = "Step executed."
                messages[aiIndex] = if (requests != null && requests.isNotEmpty()) {
                    AiMessage.from(shortText, requests)
                } else {
                    AiMessage.from(shortText)
                }
            }

            // Collect ToolExecutionResultMessage indices for this round
            var j = aiIndex + 1
            while (j < messages.size && messages[j] is ToolExecutionResultMessage) {
                compressToolResultMessage(messages, j)
                j++
            }
        }

        // Count total characters after compression
        val charsAfter = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val saved = charsBefore - charsAfter
        if (saved > 0) {
            XLog.i(TAG, "Context compressed: ${charsBefore}→${charsAfter} chars, saved ${saved} chars (${saved * 100 / charsBefore}%), rounds=${aiIndices.size}")
            if (io.agents.pokeclaw.benchmark.BenchmarkRecorder.isRecording()) {
                val example = messages.filterIsInstance<ToolExecutionResultMessage>().firstOrNull { it.text().length in 1..200 }
                    ?.let { "${it.toolName()}: ${it.text()}" }
                io.agents.pokeclaw.benchmark.TreeCompressionTrace.onHistoryCompressed(aiIndices.size, charsBefore, charsAfter, example)
            }
        }
    }

    /** Compress Tool Result: use placeholder for observation tools, truncate summary for others */
    private fun compressToolResultMessage(messages: MutableList<ChatMessage>, index: Int) {
        val msg = messages[index] as ToolExecutionResultMessage
        val text = msg.text()
        if (text.length <= 100) return // already short enough, no need to compress

        val placeholder = OBSERVATION_PLACEHOLDERS[msg.toolName()]
        if (placeholder != null) {
            messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), placeholder)
            return
        }

        // Other tools: parse JSON to extract a summary
        val compressed = summarizeToolResult(text)
        messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), compressed)
    }

    /** Compress ToolResult JSON into a one-line summary */
    private fun summarizeToolResult(resultJson: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = GSON.fromJson(resultJson, mapType)
            val isSuccess = map["isSuccess"] as? Boolean ?: false
            if (isSuccess) {
                val data = map["data"]?.toString() ?: "ok"
                "✓ " + if (data.length > 80) data.take(80) + "..." else data
            } else {
                val error = map["error"]?.toString() ?: "failed"
                "✗ " + if (error.length > 80) error.take(80) + "..." else error
            }
        } catch (_: Exception) {
            if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
        }
    }

    // ==================== Main Execution Loop ====================

    private suspend fun runAgentLoop(userPrompt: String, callback: AgentCallback) {
        // Pre-flight check
        currentCoroutineContext().ensureActive()
        preCheck()?.let {
            callback.onError(0, RuntimeException(it), 0)
            return
        }

        val parsedPrompt = TaskPromptEnvelope.parse(userPrompt)
        val rawUserRequest = parsedPrompt.currentRequest
        BenchmarkRecorder.setTaskText(rawUserRequest)
        val optimizedUserRequest = PromptRewriter.optimize(rawUserRequest)

        // Build System Prompt — use optimized prompt for local LLM
        val basePrompt = if (config.provider == LlmProvider.LOCAL) {
            LOCAL_TASK_PROMPT
        } else {
            config.systemPrompt
        }

        val inAppSearchGuard = InAppSearchGuard.fromTask(optimizedUserRequest)
        val emailComposeGuard = EmailComposeGuard.fromTask(optimizedUserRequest)
        val directDeviceDataGuard = DirectDeviceDataGuard.fromTask(optimizedUserRequest)
        // Flipkart Minutes vs the regular store: decided from her own words (the rewriter can carry "Minutes"
        // over from an earlier cart in memory), never from the tab Flipkart last showed.
        val storeMode = ShoppingStoreRouter.modeFor(rawUserRequest)
        ShoppingStoreRouter.current = storeMode

        // For local LLM, inject matching playbook into system prompt
        val playbookSection = if (config.provider == LlmProvider.LOCAL) {
            val matched = PlaybookManager.match(optimizedUserRequest)
            if (matched != null) {
                XLog.i(TAG, "Playbook matched: ${matched.id} for '$optimizedUserRequest'")
                "\n\n## Playbook: ${matched.name}\nFollow these steps exactly:\n\n${matched.body}"
            } else ""
        } else ""

        TaskExecutionState.instance.startTask(rawUserRequest)

        MemoryManager.learnFromMessage(rawUserRequest)
        val (mem0Raw, mem0Source) = HybridMemoryRepository.searchMemories(rawUserRequest)
        val mem0Memories = if (mem0Source == io.agents.pokeclaw.data.memory.MemorySource.MEM0_CLOUD) mem0Raw else ""
        val memorySection = MemoryManager.getMemoryPromptSection(rawUserRequest) +
                if (mem0Memories.isNotBlank()) "\n\n## Mem0 Cloud Search Results for Request\n$mem0Memories" else ""
        val taskStateSection = TaskExecutionState.instance.toPromptSection()

        val fullSystemPrompt = buildString {
            append(basePrompt)
            // Small on-device model: keep memory short so the screen and tools fit in context.
            append(if (config.provider == LlmProvider.LOCAL) memorySection.take(800) else memorySection)
            append(playbookSection)
            append(taskStateSection)
            append(inAppSearchGuard.buildPromptSection())
            append(emailComposeGuard.buildPromptSection())
            append(directDeviceDataGuard.buildPromptSection())
            append(ShoppingStoreRouter.promptSection(rawUserRequest, storeMode))
            append(buildDeviceContext())
        }

        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(fullSystemPrompt))

        val promptForModel = if (parsedPrompt.hasChatHistory || parsedPrompt.hasBackgroundState) {
            buildString {
                append("You are continuing an existing chatroom. Use the provided context when the current request refers to earlier messages or asks about current background activity.\n\n")
                parsedPrompt.backgroundState?.trim()?.takeIf { it.isNotEmpty() }?.let { state ->
                    append("Current background status:\n")
                    append(state)
                    append("\n\n")
                }
                parsedPrompt.chatHistory?.trim()?.takeIf { it.isNotEmpty() }?.let { history ->
                    append("Chatroom so far:\n")
                    append(history)
                    append("\n\n")
                }
                append("Current user request:\n")
                append(optimizedUserRequest)
            }
        } else {
            optimizedUserRequest
        }

        // Opt-2: Pre-warm — only attach screen info for task-like prompts.
        // Chat/questions should NOT see screen data (it confuses the LLM into using tools).
        val lowerPrompt = rawUserRequest.lowercase()
        val looksLikeTask = lowerPrompt.contains("open ") || lowerPrompt.contains("send ") ||
            lowerPrompt.contains("tap ") || lowerPrompt.contains("search ") ||
            lowerPrompt.contains("play ") || lowerPrompt.contains("take ") ||
            lowerPrompt.contains("install ") || lowerPrompt.contains("click ") ||
            lowerPrompt.contains("go to ") || lowerPrompt.contains("navigate ") ||
            lowerPrompt.contains("turn on ") || lowerPrompt.contains("turn off ") ||
            lowerPrompt.contains("monitor ") || lowerPrompt.contains("close ") ||
            lowerPrompt.contains("swipe ") || lowerPrompt.contains("scroll ") ||
            lowerPrompt.contains("check ") || lowerPrompt.contains("compose ") ||
            lowerPrompt.contains("find ") || lowerPrompt.contains("screen") ||
            lowerPrompt.contains("notification") || lowerPrompt.contains("read my") ||
            lowerPrompt.contains("call ") || lowerPrompt.contains("dial ") ||
            lowerPrompt.contains("order") || lowerPrompt.contains("buy ") ||
            lowerPrompt.contains("message ") || lowerPrompt.contains("text ") ||
            lowerPrompt.contains("hi to") || lowerPrompt.contains("add ") ||
            lowerPrompt.contains("cart") || lowerPrompt.contains("blinkit") ||
            lowerPrompt.contains("zepto") || lowerPrompt.contains("whatsapp")

        val enrichedPrompt = if (looksLikeTask) {
            try {
                val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                if (screenTool != null) {
                    val screenResult = screenTool.execute(emptyMap())
                    if (screenResult.isSuccess && !screenResult.data.isNullOrBlank()) {
                        XLog.i(TAG, "runAgentLoop: pre-warm screen attached (${screenResult.data!!.length} chars)")
                        "$promptForModel\n\nCurrent screen:\n${if (config.provider == LlmProvider.LOCAL) screenResult.data!!.take(2500) else screenResult.data}"
                    } else promptForModel
                } else promptForModel
            } catch (e: Exception) { promptForModel }
        } else {
            XLog.i(TAG, "runAgentLoop: chat-like prompt, skipping pre-warm screen")
            promptForModel
        }
        messages.add(UserMessage.from(enrichedPrompt))

        var iterations = 0
        var totalTokens = 0
        var actualModelName: String? = null  // Track the real model name from API response
        val loopHistory = LinkedList<RoundFingerprint>()
        var lastScreenHash = 0
        var lastToolError: String? = null
        var previousScreenTexts: Set<String> = emptySet()
        val tokenMonitor = TokenMonitor(config.modelName)
        val stuckDetector = StuckDetector()
        var minutesSwitchDone = false
        val taskBudget = TaskBudget.fromSettings()
        var softLimitWarned = false
        var hasExecutedTool = false
        var localNoToolNudges = 0
        var accessibilityNudges = 0
        // Tasks whose only valid outcome is an action on the phone (a text reply alone cannot complete them).
        val looksLikeSideEffectTask = lowerPrompt.contains("send ") || lowerPrompt.contains("call ") ||
            lowerPrompt.contains("dial ") || lowerPrompt.contains("message ") || lowerPrompt.contains("text ") ||
            lowerPrompt.contains("order") || lowerPrompt.contains("buy ") || lowerPrompt.contains("add ") ||
            lowerPrompt.contains("open ") || lowerPrompt.contains("tap ") || lowerPrompt.contains("click ") ||
            lowerPrompt.contains("turn on ") || lowerPrompt.contains("turn off ") || lowerPrompt.contains("install ") ||
            lowerPrompt.contains("play ") || lowerPrompt.contains("hi to")
        var guardTextNudges = 0
        var lastScreenDiffCount = 0
        var consecutiveActionRounds = 0
        val taskWantsSettings = lowerPrompt.contains("setting") || lowerPrompt.contains("wifi") ||
            lowerPrompt.contains("wi-fi") || lowerPrompt.contains("bluetooth") || lowerPrompt.contains("brightness") ||
            lowerPrompt.contains("permission") || lowerPrompt.contains("airplane") || lowerPrompt.contains("hotspot") ||
            lowerPrompt.contains("location") || lowerPrompt.contains("volume") || lowerPrompt.contains("display")
        val taskStartTime = System.currentTimeMillis()
        val maxIterations = minOf(config.maxIterations, 30)
        if (config.maxIterations > maxIterations) {
            XLog.w(TAG, "runAgentLoop: maxIterations capped to $maxIterations (configured=${config.maxIterations})")
        }

        while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)
            BenchmarkRecorder.onStep(iterations)

            // Time limit cutoff: only apply if step count <= 4 (disabled for multi-step workflows)
            if (iterations <= 4 && System.currentTimeMillis() - taskStartTime > 180_000L) {
                XLog.w(TAG, "runAgentLoop: 180s task time limit reached — completing task cleanly")
                // Was "Task completed successfully." even though nothing finished (fake success).
                callback.onError(iterations, RuntimeException("I couldn't finish this in 3 minutes, so I stopped. Nothing was completed."), totalTokens)
                return
            }

            // Compress history messages before sending to save tokens
            compressHistoryForSend(messages)

            // LLM call (with retry)
            val llmResponse: LlmResponse
            try {
                llmResponse = chatWithRetry(messages, callback, iterations)
            } catch (e: Exception) {
                XLog.e(TAG, "LLM API call failed after retries", e)
                val friendlyError = formatLlmError(e.message)
                callback.onError(iterations, RuntimeException(friendlyError), totalTokens)
                return
            }

            if (cancelled.get()) {
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                return
            }

            // Capture actual model name from first API response
            if (actualModelName == null && !llmResponse.modelName.isNullOrEmpty()) {
                actualModelName = llmResponse.modelName
                XLog.d(TAG, "runAgentLoop: actual model from API = $actualModelName")
            }
            // Accumulate token usage
            llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }
            tokenMonitor.record(
                step = iterations,
                inputTokens = llmResponse.tokenUsage?.inputTokenCount(),
                outputTokens = llmResponse.tokenUsage?.outputTokenCount(),
                totalTokenCount = llmResponse.tokenUsage?.totalTokenCount()
            )
            callback.onTokenUpdate(tokenMonitor.getStatus())

            // Budget check
            val tokenStatus = tokenMonitor.getStatus()
            when (taskBudget.check(tokenStatus.totalTokens, tokenStatus.estimatedCostUsd)) {
                TaskBudget.Status.HARD_LIMIT -> {
                    XLog.w(TAG, "Budget HARD LIMIT reached at step $iterations: ${tokenStatus.formattedTokens} (${tokenStatus.formattedCost})")
                    // Reported as an error, not a completion: the task was not finished.
                    callback.onError(
                        iterations,
                        RuntimeException(
                            "Task stopped: budget limit reached (${tokenStatus.formattedTokens} tokens, ${tokenStatus.formattedCost}). " +
                            "Increase budget in Settings if needed."
                        ),
                        totalTokens
                    )
                    return
                }
                TaskBudget.Status.SOFT_LIMIT -> {
                    if (!softLimitWarned) {
                        softLimitWarned = true
                        XLog.i(TAG, "Budget SOFT LIMIT at step $iterations: ${tokenStatus.formattedTokens}")
                        messages.add(UserMessage.from(
                            "[System Notice] You are using ${tokenStatus.formattedTokens} tokens (${tokenStatus.formattedCost}), " +
                            "approaching the budget limit. Finish the task efficiently. " +
                            "If you cannot complete it soon, call finish with a partial summary."
                        ))
                    }
                }
                TaskBudget.Status.OK -> { /* continue normally */ }
            }

            // DEBUG: log raw LLM response for tool calling diagnosis
            XLog.i(TAG, "runAgentLoop iter=$iterations response.textLen=${llmResponse.text?.length ?: 0}")
            XLog.i(TAG, "runAgentLoop iter=$iterations hasToolCalls=${llmResponse.hasToolExecutionRequests()} toolCallCount=${llmResponse.toolExecutionRequests?.size ?: 0}")

            // Add AI message to history (must construct AiMessage)
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                if (llmResponse.text.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)

            // Push thinking content in non-streaming mode & speak intermediate intent
            if (!config.streaming && !llmResponse.text.isNullOrBlank()) {
                val hasFinishCall = llmResponse.toolExecutionRequests?.any { it.name() == "finish" } == true
                val suppressHallucinatedCompletion =
                    !llmResponse.hasToolExecutionRequests() &&
                        (inAppSearchGuard.shouldBlockTextOnlyCompletion() ||
                            emailComposeGuard.shouldBlockTextOnlyCompletion())
                if (!suppressHallucinatedCompletion && !hasFinishCall) {
                    callback.onContent(iterations, llmResponse.text)
                    // Not spoken: intermediate model text overlapped other speech. Final answer is spoken once by the UI.
                }
            }

            // No tool calls in this response — treat it as the final user-facing result.
            if (!llmResponse.hasToolExecutionRequests()) {
                val responseText = llmResponse.text?.trim().orEmpty()

                // Check for hallucinated accessibility warning from LLM on initial turns
                val isHallucinatedAccessibilityError = !hasExecutedTool &&
                    (responseText.contains("accessibility", ignoreCase = true) ||
                     responseText.contains("not enabled", ignoreCase = true) ||
                     responseText.contains("enable it", ignoreCase = true))

                // A task guard (in-app search / email compose / device data) says the work is not done yet:
                // a text-only "done" is a hallucinated completion. Push back with the guard's correction.
                if (looksLikeTask && guardTextNudges < 2) {
                    val guardCorrection = when {
                        inAppSearchGuard.shouldBlockTextOnlyCompletion() -> inAppSearchGuard.buildCompletionCorrection()
                        emailComposeGuard.shouldBlockTextOnlyCompletion() -> emailComposeGuard.buildCompletionCorrection()
                        directDeviceDataGuard.shouldBlockTextOnlyCompletion() -> directDeviceDataGuard.buildCompletionCorrection()
                        else -> null
                    }
                    if (guardCorrection != null) {
                        guardTextNudges++
                        XLog.w(TAG, "runAgentLoop: text-only completion blocked by task guard; nudging ($guardTextNudges/2)")
                        messages.add(UserMessage.from(guardCorrection))
                        continue
                    }
                }

                // Models often "answer" a phone task in words ("Message sent!", "I opened WhatsApp")
                // without calling any tool. Push back twice before accepting a text-only reply.
                if (!hasExecutedTool && looksLikeTask && localNoToolNudges < 2) {
                    localNoToolNudges++
                    XLog.w(TAG, "runAgentLoop: model replied without a tool call on a task; nudging ($localNoToolNudges/2)")
                    messages.add(UserMessage.from(
                        if (config.provider == LlmProvider.LOCAL) {
                            "You have not done anything on the phone yet. Do not describe results. " +
                            "Reply with exactly one action in this format:\nAction: tool_name\n{\"arg\": \"value\"}"
                        } else {
                            "You have not done anything on the phone yet. Do not describe results or claim the task is done. " +
                            "Call the tool for the next step now."
                        }
                    ))
                    continue
                }

                if (isHallucinatedAccessibilityError && accessibilityNudges < 1) {
                    accessibilityNudges++
                    XLog.w(TAG, "runAgentLoop: detected hallucinated accessibility error text from LLM! Injecting tool prompt.")
                    messages.add(UserMessage.from(
                        "[System Notice] Accessibility Service is ALREADY ENABLED and FULLY ACTIVE on this phone. " +
                        "Do NOT output instructions or say accessibility is disabled. " +
                        "You MUST execute a tool call now (for example open_app or send_message or get_screen_info) to proceed with the user's task."
                    ))
                    continue
                }

                val completionReason = when {
                    responseText.isNotEmpty() -> "text-only response"
                    hasExecutedTool -> "empty post-tool response"
                    else -> "empty response"
                }
                if (!hasExecutedTool && looksLikeSideEffectTask) {
                    // Nothing was done on the phone: never report a text-only reply ("Message sent!")
                    // to a send/call/order/open task as success. Questions answered from the screen are fine.
                    XLog.w(TAG, "runAgentLoop: phone task ended without any tool call; reporting as not done")
                    callback.onError(
                        iterations,
                        RuntimeException("I couldn't do this on the phone, so nothing was done."),
                        totalTokens
                    )
                    return
                }
                val finalAnswer = if (responseText.isNotEmpty()) responseText else ClawApplication.instance.getString(R.string.agent_task_completed)
                XLog.i(TAG, "runAgentLoop: $completionReason, completing")
                // Spoken once by the UI on TaskEvent.Completed (speaking here too caused double voice).
                callback.onComplete(iterations, finalAnswer, totalTokens, actualModelName)
                return
            }

            hasExecutedTool = true

            // Execute tool calls
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                    return
                }

                val toolName = toolRequest.name() ?: ""
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val toolArgs = toolRequest.arguments() ?: "{}"

                // Parse parameters
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                var params: Map<String, Any>? = try {
                    GSON.fromJson(toolArgs, mapType)
                } catch (e: Exception) {
                    XLog.w(TAG, "Failed to parse tool args for $toolName: $toolArgs", e)
                    HashMap()
                }
                if (params == null) params = HashMap()

                val blockedFinish = if (toolName == "finish") {
                    val screenInfo = try {
                        ToolRegistry.getInstance()
                            .getTool("get_screen_info")
                            ?.execute(emptyMap())
                            ?.takeIf { it.isSuccess }
                            ?.data
                    } catch (_: Exception) {
                        null
                    }
                    directDeviceDataGuard.maybeBlockFinish()
                        ?: inAppSearchGuard.maybeBlockFinish(screenInfo)
                        ?: emailComposeGuard.maybeBlockFinish(screenInfo)
                } else null
                if (blockedFinish != null) {
                    val blockedResult = ToolResult.error(blockedFinish)
                    XLog.i(TAG, "Task guard blocked premature finish (promptLen=${userPrompt.length})")
                    callback.onToolCall(iterations, toolName, displayName, toolArgs)
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blockedResult)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blockedResult)))
                    messages.add(UserMessage.from(blockedFinish))
                    continue
                }

                callback.onToolCall(iterations, toolName, displayName, toolArgs)
                directDeviceDataGuard.recordToolAttempt(toolName)
                emailComposeGuard.recordToolAttempt(toolName)

                val activePkg = try {
                    ClawAccessibilityService.getConnectedInstance(100L)?.rootInActiveWindow?.packageName?.toString().orEmpty()
                } catch (_: Exception) { "" }
                TaskExecutionState.instance.setActiveContext(activePkg, toolName)
                TaskExecutionState.instance.incrementStep()

                val benchToolStart = if (BenchmarkRecorder.isRecording()) System.currentTimeMillis() else 0L
                val result = ToolRegistry.getInstance().executeTool(toolName, params)
                if (benchToolStart != 0L) {
                    BenchmarkRecorder.onToolCall(
                        toolName, System.currentTimeMillis() - benchToolStart, result.isSuccess,
                        if (OutboundConfirmGate.isAskResult(result)) io.agents.pokeclaw.benchmark.BenchmarkTagging.STATUS_ASKED else null,
                    )
                }
                val paramsString = if (params.isEmpty()) "" else params.toString()
                callback.onToolResult(iterations, toolName, displayName, paramsString, result)
                if (result.isSuccess) {
                    inAppSearchGuard.recordSuccessfulTool(toolName, params)
                    emailComposeGuard.recordSuccessfulTool(toolName)
                }
                // Flipkart's Minutes tab is an image with no text: switch to it in code once per task.
                if (toolName == "open_app" && result.isSuccess &&
                    params.values.any { it?.toString()?.contains("flipkart", true) == true } &&
                    storeMode == ShoppingStoreRouter.Mode.MINUTES && !minutesSwitchDone
                ) {
                    minutesSwitchDone = true
                    try {
                        delay(1500) // let the home screen draw
                        ClawAccessibilityService.getConnectedInstance(1000L)?.let { svc ->
                            FlipkartMinutesSwitch.ensure(svc)?.let { note ->
                                delay(1200)
                                messages.add(UserMessage.from("[System] $note"))
                            }
                        }
                    } catch (e: Exception) {
                        XLog.w(TAG, "Minutes switch failed: ${e.message}")
                    }
                }

                // Outbound message/call parked until the user says yes: end the task with the question
                // (spoken and shown in her language); TaskFlowController handles her "haan" / "na".
                if (OutboundConfirmGate.isAskResult(result)) {
                    val question = OutboundConfirmGate.pendingQuestion() ?: (result.error ?: "")
                    XLog.i(TAG, "Outbound '$toolName' needs the user's yes; ending the task with the question")
                    callback.onComplete(iterations, question, totalTokens, actualModelName)
                    return
                }

                // System dialog blocking detected → notify user and stop task
                if (!result.isSuccess && result.error == GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) {
                    XLog.w(TAG, "System dialog blocked, notifying user and stopping task")
                    callback.onSystemDialogBlocked(iterations, totalTokens)
                    return
                }

                // finish tool → task complete
                if (toolName == "finish" && result.isSuccess) {
                    val finishData = result.data ?: ClawApplication.instance.getString(R.string.agent_task_completed)
                    callback.onComplete(iterations, finishData, totalTokens, actualModelName)
                    return
                }

                // End-to-end direct completion tools: complete task immediately upon tool success (0 extra LLM rounds)
                val directCompletionTools = setOf("place_call", "send_message", "send_distress_signal", "message_care_contact")
                if (toolName in directCompletionTools && result.isSuccess) {
                    val completionMsg = result.data ?: "✓ $toolName executed successfully"
                    XLog.i(TAG, "Direct completion tool '$toolName' succeeded — completing task end-to-end immediately")
                    TaskDependencyGraph.recordOutcome(
                        TaskExecutionState.instance.taskGoal,
                        isSuccess = true,
                        summary = completionMsg
                    )
                    callback.onComplete(iterations, completionMsg, totalTokens, actualModelName)
                    return
                }

                // Opt-3: Auto-attach fresh screen state after action tools.
                // LLM sees updated UI in the same tool result → can decide next step
                // immediately without spending an extra 5 s inference round on get_screen_info.
                val combinedResultData: String = if (toolName in ACTION_TOOLS) {
                    try {
                        delay(SCREEN_SETTLE_MS) // let UI animate/settle
                        val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                        val screenAfter = screenTool?.execute(emptyMap())
                        if (screenAfter != null && screenAfter.isSuccess && !screenAfter.data.isNullOrBlank()) {
                            // Update lastScreenHash for loop detection
                            lastScreenHash = screenAfter.data!!.hashCode()
                            XLog.i(TAG, "Opt3: auto-attached screen after $toolName (${screenAfter.data!!.length} chars)")
                            // Screen diff: extract text lines and compare with previous
                            val currentTexts = screenAfter.data!!.lines()
                                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                            val added = currentTexts - previousScreenTexts
                            val removed = previousScreenTexts - currentTexts
                            previousScreenTexts = currentTexts
                            lastScreenDiffCount = added.size + removed.size
                            val diffSection = buildString {
                                if (added.isNotEmpty()) append("\nNew on screen: ${added.take(10).joinToString(", ")}")
                                if (removed.isNotEmpty()) append("\nGone from screen: ${removed.take(10).joinToString(", ")}")
                            }
                            // Chain of Thought Backtracking check for accidental navigation (e.g. Settings / launcher)
                            val service = ClawAccessibilityService.getConnectedInstance(100L)
                            val currentPkg = service?.rootInActiveWindow?.packageName?.toString().orEmpty()
                            // Never auto-back when the task is about settings, right after open_app, or on the
                            // runtime permission dialog (CALL_PHONE / SEND_SMS grants need it to stay up).
                            val isAccidentalNav = !taskWantsSettings && toolName != "open_app" &&
                                !currentPkg.contains("permissioncontroller") &&
                                (currentPkg.contains("settings") || currentPkg.contains("permission"))
                            var backtrackNotice = ""
                            if (isAccidentalNav && service != null) {
                                XLog.w(TAG, "Accidental navigation to '$currentPkg' detected; returning to the target app")
                                // Back to the app the tool started in (relaunch it rather than BACK out of it).
                                val how = recoveryBack(service, TaskExecutionState.instance.activePackageName)
                                backtrackNotice = "\n\n[Self-Correction] Accidental navigation to '$currentPkg' detected. Returned to the target app ($how)."
                            }

                            val baseData = if (result.isSuccess) (result.data ?: "✓ Action succeeded") else "✗ Error: ${result.error}"
                            "$baseData$backtrackNotice\n\nScreen after action:\n${screenAfter.data}$diffSection"
                        } else {
                            XLog.w(TAG, "Opt3: get_screen_info failed after $toolName: ${screenAfter?.error}")
                            if (result.isSuccess) (result.data ?: "✓ Action succeeded") else "✗ Error: ${result.error}"
                        }
                    } catch (e: Exception) {
                        XLog.w(TAG, "Opt3: exception fetching screen after $toolName", e)
                        if (result.isSuccess) (result.data ?: "✓ Action succeeded") else "✗ Error: ${result.error}"
                    }
                } else {
                    // Record fingerprint for dead-loop detection (non-action tools path)
                    if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
                        lastScreenHash = result.data.hashCode()
                    }
                    if (result.isSuccess) (result.data ?: "✓ Action succeeded") else "✗ Error: ${result.error}"
                }

                // For action tools the loop detection hash was already updated above;
                // for non-get_screen_info action tools also record the fingerprint.
                if (toolName in ACTION_TOOLS) {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
                } else if (toolName.isNotEmpty() && toolName != "get_screen_info") {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
                }

                lastToolError = if (result.isSuccess) null else (result.error ?: "error")

                // Add tool result to messages
                messages.add(ToolExecutionResultMessage.from(toolRequest, combinedResultData))
                XLog.d(TAG, "displayName:$displayName toolName:$toolName")
            }

            // Dead-loop: same tool call on the same screen for LOOP_DETECT_WINDOW rounds → stop honestly.
            if (isStuckInLoop(loopHistory)) {
                XLog.w(TAG, "runAgentLoop: dead loop detected (same action on same screen ${LOOP_DETECT_WINDOW}x), stopping")
                callback.onError(
                    iterations,
                    RuntimeException("I kept repeating the same step without progress, so I stopped. The task is not finished."),
                    totalTokens
                )
                return
            }

            // Stuck detection & Active Auto-Recovery
            val lastAction = llmResponse.toolExecutionRequests?.firstOrNull()?.let {
                "${it.name()}:${it.arguments()?.take(50)}"
            } ?: ""
            val roundHadAction = llmResponse.toolExecutionRequests?.any { (it.name() ?: "") in ACTION_TOOLS } == true
            consecutiveActionRounds = if (roundHadAction) consecutiveActionRounds + 1 else 0
            // Diff only means something after an action round (the screen was re-read); otherwise report "changed".
            val screenDiffCount = if (roundHadAction) lastScreenDiffCount else 1
            val detection = stuckDetector.record(TaskExecutionState.instance.activePackageName, lastAction, lastScreenHash, screenDiffCount, lastToolError)
            if (detection != null) {
                XLog.w(TAG, "StuckDetector ${detection.level} at iteration $iterations: ${detection.signal.description}")
                BenchmarkRecorder.noteStuck(detection.level.name, detection.signal.description)

                val service = ClawAccessibilityService.getConnectedInstance(1000L)
                // Only press BACK / dismiss when the last 3 rounds were real actions: an unchanged hash
                // after read-only tools is not evidence of a stuck screen.
                if (service != null && consecutiveActionRounds >= 3 && (detection.level == StuckDetector.RecoveryLevel.AUTO_DISMISS || detection.level == StuckDetector.RecoveryLevel.STRATEGY_SWITCH)) {
                    val root = service.rootInActiveWindow
                    if (root != null) {
                        try {
                            val method = ContactListUiUtils::class.java.getDeclaredMethod("dismissBlockingOverlay", ClawAccessibilityService::class.java, AccessibilityNodeInfo::class.java, Long::class.javaPrimitiveType)
                            method.isAccessible = true
                            val dismissed = method.invoke(null, service, root, 300L) as? Boolean ?: false
                            if (dismissed) {
                                XLog.i(TAG, "Active recovery: auto-dismissed blocking overlay or permission dialog")
                                val cotPrompt = ChainOfThoughtLearnings.getReasoningPrompt(ChainOfThoughtLearnings.IssueType.SUB_SCREEN_OVERLAY, "Blocking overlay auto-dismissed")
                                messages.add(UserMessage.from(cotPrompt))
                            } else {
                                XLog.i(TAG, "Active recovery: Back (staying inside the target app)")
                                recoveryBack(service, root.packageName?.toString().orEmpty())
                                val cotPrompt = ChainOfThoughtLearnings.getReasoningPrompt(ChainOfThoughtLearnings.IssueType.SUB_SCREEN_OVERLAY, "Pressed Back key to clear unresponsive screen")
                                messages.add(UserMessage.from(cotPrompt))
                            }
                        } catch (_: Exception) {
                            recoveryBack(service, root.packageName?.toString().orEmpty())
                            val cotPrompt = ChainOfThoughtLearnings.getReasoningPrompt(ChainOfThoughtLearnings.IssueType.UNRESPONSIVE_BUTTON, "Pressed Back key to unblock screen")
                            messages.add(UserMessage.from(cotPrompt))
                        }
                    }
                } else {
                    val cotPrompt = ChainOfThoughtLearnings.getReasoningPrompt(ChainOfThoughtLearnings.IssueType.UNRESPONSIVE_BUTTON, detection.signal.description)
                    messages.add(UserMessage.from(cotPrompt))
                }

                // No mid-task speech (owner decision 2026-11-14): only task start, questions and the result are spoken.
            }
            XLog.d(TAG, "Round:$iterations total=$totalTokens thisRound=${llmResponse.tokenUsage?.totalTokenCount()}")
        }

        if (cancelled.get()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
        } else {
            XLog.w(TAG, "runAgentLoop: max-iterations reached ($iterations/$maxIterations), terminating")
            callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, maxIterations)), totalTokens)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        XLog.i(TAG, "Agent task forcefully terminated by user.")
        currentAgentJob?.cancel(CancellationException("Agent task forcefully terminated by user."))
        running.set(false)
        try {
            ForegroundService.resetToIdle(ClawApplication.instance)
        } catch (e: Exception) {
            XLog.w(TAG, "cancel: failed to reset foreground state to idle", e)
        }
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
                XLog.i(TAG, "LlmClient closed during forceful cancellation")
            } catch (e: Exception) {
                XLog.w(TAG, "LlmClient close error during forceful cancellation", e)
            }
        }
    }

    fun stopTask() {
        cancel()
    }

    override fun shutdown() {
        cancel()
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
                XLog.i(TAG, "LlmClient closed on shutdown")
            } catch (e: Exception) {
                XLog.w(TAG, "LlmClient close error on shutdown", e)
            }
        }
    }

    override fun isRunning(): Boolean = running.get()
}
