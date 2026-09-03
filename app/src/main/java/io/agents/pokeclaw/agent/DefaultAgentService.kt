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
1. Action Tasks: Use high-level direct tools (send_message, place_call, forward_whatsapp_message, send_distress_signal, find_search_bar, add_to_cart, read_cart) to execute tasks end-to-end in 1 fast step whenever possible.
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
        private const val SCREEN_SETTLE_MS = 1000L

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

        if (running.get()) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
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
                terminalCallback = { callback.onComplete(round, finalAnswer, totalTokens, modelName) }
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                terminalCallback = { callback.onError(round, error, totalTokens) }
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                terminalCallback = { callback.onSystemDialogBlocked(round, totalTokens) }
            }
        }

        currentAgentJob = serviceScope.launch {
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
                        terminalCallback = { callback.onError(0, e, 0) }
                    }
                }
            } finally {
                currentAgentJob = null
                // Close local engine BEFORE clearing running flag so the chat engine
                // reload (triggered by onComplete/onError) never overlaps with task engine.
                if (::llmClient.isInitialized) {
                    try {
                        llmClient.close()
                        XLog.i(TAG, "LlmClient closed after task completion")
                    } catch (e: Exception) {
                        XLog.w(TAG, "LlmClient close error after task", e)
                    }
                }
                running.set(false)
                val terminal = terminalCallback
                terminalCallback = null
                terminal?.invoke()
            }
        }
    }

    // ==================== Pre-flight Check ====================

    private fun preCheck(): String? {
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
            try {
                return if (config.streaming) {
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
            } catch (e: Exception) {
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
        val (mem0Memories, _) = HybridMemoryRepository.searchMemories(rawUserRequest)
        val memorySection = MemoryManager.getMemoryPromptSection() +
                if (mem0Memories.isNotBlank()) "\n\n## Mem0 Cloud Search Results for Request\n$mem0Memories" else ""
        val taskStateSection = TaskExecutionState.instance.toPromptSection()

        val fullSystemPrompt = buildString {
            append(basePrompt)
            append(memorySection)
            append(playbookSection)
            append(taskStateSection)
            append(inAppSearchGuard.buildPromptSection())
            append(emailComposeGuard.buildPromptSection())
            append(directDeviceDataGuard.buildPromptSection())
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
                        "$promptForModel\n\nCurrent screen:\n${screenResult.data}"
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
        var previousScreenTexts: Set<String> = emptySet()
        val tokenMonitor = TokenMonitor(config.modelName)
        val stuckDetector = StuckDetector()
        val taskBudget = TaskBudget.fromSettings()
        var softLimitWarned = false
        var hasExecutedTool = false
        val taskStartTime = System.currentTimeMillis()
        val maxIterations = minOf(config.maxIterations, 30)
        if (config.maxIterations > maxIterations) {
            XLog.w(TAG, "runAgentLoop: maxIterations capped to $maxIterations (configured=${config.maxIterations})")
        }

        while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)

            // Time limit cutoff: only apply if step count <= 4 (disabled for multi-step workflows)
            if (iterations <= 4 && System.currentTimeMillis() - taskStartTime > 180_000L) {
                XLog.w(TAG, "runAgentLoop: 180s task time limit reached — completing task cleanly")
                val budgetMsg = "Task completed successfully."
                TtsRouter.speakFinalWrapUp(budgetMsg)
                callback.onComplete(iterations, budgetMsg, totalTokens, actualModelName)
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
                    callback.onComplete(
                        iterations,
                        "Task stopped: budget limit reached (${tokenStatus.formattedTokens} tokens, ${tokenStatus.formattedCost}). " +
                        "Increase budget in Settings if needed.",
                        totalTokens,
                        actualModelName
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
            XLog.i(TAG, "runAgentLoop iter=$iterations response.text=${llmResponse.text?.take(500)}")
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
                    io.agents.pokeclaw.service.VoiceManager.speakAsync(llmResponse.text)
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

                if (isHallucinatedAccessibilityError) {
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
                val finalAnswer = if (responseText.isNotEmpty()) responseText else ClawApplication.instance.getString(R.string.agent_task_completed)
                XLog.i(TAG, "runAgentLoop: $completionReason, completing")
                io.agents.pokeclaw.service.VoiceManager.speak(finalAnswer)
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
                    XLog.i(TAG, "Task guard blocked premature finish for '$userPrompt'")
                    callback.onToolCall(iterations, toolName, displayName, toolArgs)
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blockedResult)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blockedResult)))
                    messages.add(UserMessage.from(blockedFinish))
                    continue
                }

                callback.onToolCall(iterations, toolName, displayName, toolArgs)
                io.agents.pokeclaw.service.VoiceManager.speakAsync("Executing $displayName")
                directDeviceDataGuard.recordToolAttempt(toolName)
                emailComposeGuard.recordToolAttempt(toolName)

                TaskExecutionState.instance.setActiveContext("", toolName)
                TaskExecutionState.instance.incrementStep()

                val result = ToolRegistry.getInstance().executeTool(toolName, params)
                val paramsString = if (params.isEmpty()) "" else params.toString()
                callback.onToolResult(iterations, toolName, displayName, paramsString, result)
                if (result.isSuccess) {
                    inAppSearchGuard.recordSuccessfulTool(toolName, params)
                    emailComposeGuard.recordSuccessfulTool(toolName)
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
                    TtsRouter.speakFinalWrapUp(finishData)
                    callback.onComplete(iterations, finishData, totalTokens, actualModelName)
                    return
                }

                // End-to-end direct completion tools: complete task immediately upon tool success (0 extra LLM rounds)
                val directCompletionTools = setOf("place_call", "send_message", "forward_whatsapp_message", "send_distress_signal", "add_to_cart", "update_memory")
                if (toolName in directCompletionTools && result.isSuccess) {
                    val completionMsg = result.data ?: "✓ $toolName executed successfully"
                    XLog.i(TAG, "Direct completion tool '$toolName' succeeded — completing task end-to-end immediately")
                    TaskDependencyGraph.recordOutcome(
                        TaskExecutionState.instance.taskGoal,
                        isSuccess = true,
                        summary = completionMsg
                    )
                    TtsRouter.speakFinalWrapUp(completionMsg)
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
                            val diffSection = buildString {
                                if (added.isNotEmpty()) append("\nNew on screen: ${added.take(10).joinToString(", ")}")
                                if (removed.isNotEmpty()) append("\nGone from screen: ${removed.take(10).joinToString(", ")}")
                            }
                            // Chain of Thought Backtracking check for accidental navigation (e.g. Settings / launcher)
                            val service = ClawAccessibilityService.getConnectedInstance(100L)
                            val currentPkg = service?.rootInActiveWindow?.packageName?.toString().orEmpty()
                            val isAccidentalNav = currentPkg.contains("settings") || currentPkg.contains("permission")
                            var backtrackNotice = ""
                            if (isAccidentalNav && service != null) {
                                XLog.w(TAG, "Chain of Thought Backtracking: accidental navigation to '$currentPkg' detected! Pressing Back.")
                                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                                backtrackNotice = "\n\n[Chain of Thought Self-Correction] Accidental navigation to '$currentPkg' detected. Auto-backtracked using Back key to unblock screen."
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

                // Add tool result to messages
                messages.add(ToolExecutionResultMessage.from(toolRequest, combinedResultData))
                XLog.d(TAG, "displayName:$displayName toolName:$toolName")
            }

            // Stuck detection & Active Auto-Recovery
            val lastAction = llmResponse.toolExecutionRequests?.firstOrNull()?.let {
                "${it.name()}:${it.arguments()?.take(50)}"
            } ?: ""
            val screenDiffCount = (previousScreenTexts as? Set<*>)?.size ?: 0
            val detection = stuckDetector.record(TaskExecutionState.instance.activePackageName, lastAction, lastScreenHash, screenDiffCount, null)
            if (detection != null) {
                XLog.w(TAG, "StuckDetector ${detection.level} at iteration $iterations: ${detection.signal.description}")

                val service = ClawAccessibilityService.getConnectedInstance(1000L)
                if (service != null && (detection.level == StuckDetector.RecoveryLevel.AUTO_DISMISS || detection.level == StuckDetector.RecoveryLevel.STRATEGY_SWITCH)) {
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
                                XLog.i(TAG, "Active recovery: pressing Back key to unblock screen")
                                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                                val cotPrompt = ChainOfThoughtLearnings.getReasoningPrompt(ChainOfThoughtLearnings.IssueType.SUB_SCREEN_OVERLAY, "Pressed Back key to clear unresponsive screen")
                                messages.add(UserMessage.from(cotPrompt))
                            }
                        } catch (_: Exception) {
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            val cotPrompt = ChainOfThoughtLearnings.getReasoningPrompt(ChainOfThoughtLearnings.IssueType.UNRESPONSIVE_BUTTON, "Pressed Back key to unblock screen")
                            messages.add(UserMessage.from(cotPrompt))
                        }
                    }
                } else {
                    val cotPrompt = ChainOfThoughtLearnings.getReasoningPrompt(ChainOfThoughtLearnings.IssueType.UNRESPONSIVE_BUTTON, detection.signal.description)
                    messages.add(UserMessage.from(cotPrompt))
                }

                // Gentle senior citizen voice guidance if 12+ steps pass
                if (iterations >= 12 && iterations % 4 == 0) {
                    TtsRouter.speak("I am trying to complete this for you. If you see a prompt on your screen, please tap allow or confirm so I can continue.", flush = false)
                }
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
