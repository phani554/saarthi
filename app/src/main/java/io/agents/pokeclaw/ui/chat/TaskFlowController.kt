// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.AppViewModel
import io.agents.pokeclaw.ServiceBindingState
import io.agents.pokeclaw.TaskEvent
import io.agents.pokeclaw.agent.DirectDeviceDataGuard
import io.agents.pokeclaw.agent.OutboundConfirmGate
import io.agents.pokeclaw.agent.SpokenStrings
import io.agents.pokeclaw.agent.PipelineRouter
import io.agents.pokeclaw.agent.TaskPromptEnvelope
import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.lang.ReplyLanguageState
import io.agents.pokeclaw.lang.ReplyLocalizer
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.service.AutoReplyManager
import io.agents.pokeclaw.service.VoiceManager
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.ui.settings.SettingsActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

data class TaskFlowUiState(
    val messages: SnapshotStateList<ChatMessage>,
    val modelStatus: MutableState<String>,
    val isAwaitingReply: MutableState<Boolean>,
    val isTaskRunning: MutableState<Boolean>,
)

/**
 * Owns task-mode send flow, typed TaskEvent rendering, and monitor start wiring.
 *
 * ComposeChatActivity keeps the shell; this controller keeps task-specific behavior.
 */
class TaskFlowController(
    private val activity: ComponentActivity,
    private val executor: ExecutorService,
    private val appViewModel: AppViewModel,
    private val chatSessionController: ChatSessionController,
    private val currentConversationId: () -> String,
    private val uiState: TaskFlowUiState,
    private val onPersistConversation: () -> Unit,
    private val onTaskSettled: (() -> Unit)? = null,
) {

    companion object {
        private const val TAG = "TaskFlowController"
        // Status text ChatSessionController shows when the selected tab has no model.
        private const val NO_MODEL_STATUS = "No model selected"

        private val CANCEL_WORDS = Regex(
            """\b(stop|cancel|wait|rok do|ruko|bhai ruk|vaddu|halt)\b""",
            RegexOption.IGNORE_CASE
        )

    }

    enum class VoiceInteractionState {
        IDLE,
        PRE_TASK_CONFIRMATION,
        TASK_EXECUTING
    }

    @Volatile
    var currentVoiceState = VoiceInteractionState.IDLE
        private set

    // Guarded by synchronized(preTaskHistoryBuilder): written from the executor and from main.
    private val preTaskHistoryBuilder = StringBuilder()
    var onTriggerVoiceCapture: (() -> Unit)? = null

    // Bumped when SOS starts or voice state is reset, so an in-flight rewriter turn is dropped.
    private val voiceGeneration = AtomicInteger(0)
    /** Bumped when a new request starts, so a late translated answer is not spoken over it. */
    private val replySeq = AtomicInteger(0)

    private var sendTaskRetryCount = 0
    private var lastMonitorStatusNote: String? = null
    private val pipelineRouter = PipelineRouter(activity)

    private fun clearPreTaskHistory() {
        synchronized(preTaskHistoryBuilder) { preTaskHistoryBuilder.clear() }
    }

    /** Runs [block] on the controller executor, or on a fresh thread if the executor is shut down. */
    private fun runOffMain(block: () -> Unit) {
        try {
            executor.execute { block() }
        } catch (e: RejectedExecutionException) {
            Thread { block() }.start()
        }
    }

    /** Drops any pending voice turn and returns the voice loop to IDLE (e.g. after KILL TASK). */
    fun resetVoiceState() {
        voiceGeneration.incrementAndGet()
        currentVoiceState = VoiceInteractionState.IDLE
        isTaskExecutingLock = false
        clearPreTaskHistory()
        VoiceManager.onPlaybackFinished = null
    }

    fun processVoiceTurn(userSpeech: String) {
        if (userSpeech.isBlank()) return
        replySeq.incrementAndGet()

        // Distress heard: DistressEscalator decides whether this is a short distress phrase.
        // Only then skip the confirm turn and start the SOS call chain immediately.
        if (io.agents.pokeclaw.care.DistressEscalator.isDistressUtterance(userSpeech)) {
            voiceGeneration.incrementAndGet()
            if (appViewModel.isTaskRunning()) appViewModel.stopTask()
            currentVoiceState = VoiceInteractionState.IDLE
            VoiceManager.onPlaybackFinished = null
            clearPreTaskHistory()
            val appContext = activity.applicationContext
            // trigger() speaks via VoiceManager (which can block on its speak lock), so keep it off main.
            runOffMain {
                val msg = try {
                    io.agents.pokeclaw.care.DistressEscalator.trigger(appContext, "voice")
                } catch (e: Exception) {
                    XLog.e(TAG, "SOS trigger failed", e)
                    "Could not start SOS: ${e.message}"
                }
                activity.runOnUiThread { addUser(userSpeech); addSystem("SOS: $msg") }
            }
            return
        }
        // "I'm fine / stop" while SOS is calling people (Latin, Devanagari and Telugu phrases).
        if (io.agents.pokeclaw.care.DistressEscalator.isActive &&
            io.agents.pokeclaw.care.DistressEscalator.isCancelUtterance(userSpeech)) {
            io.agents.pokeclaw.care.DistressEscalator.cancel(activity, "user said stop")
            VoiceManager.speakAsync(SpokenStrings.sosStopped(), flush = true)
            activity.runOnUiThread { addSystem("SOS stopped.") }
            return
        }

        // Answer to "Ravi ko '...' bhejun? Haan ya na?" goes to the parked message / call first.
        if (handleConfirmationReply(userSpeech)) return

        // The selected tab has no model (input is disabled): don't quietly run a voice task on the other mode's model.
        if (!appViewModel.isTaskRunning() && uiState.modelStatus.value == NO_MODEL_STATUS) {
            activity.runOnUiThread {
                Toast.makeText(activity, "No model selected. Pick a model first.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Speech while a typed task runs goes to the stop/redirect branch, not a new confirmation turn.
        if (currentVoiceState == VoiceInteractionState.IDLE && appViewModel.isTaskRunning()) {
            currentVoiceState = VoiceInteractionState.TASK_EXECUTING
        }

        val generation = voiceGeneration.get()
        executor.execute {
            try {
                when (currentVoiceState) {
                    VoiceInteractionState.IDLE, VoiceInteractionState.PRE_TASK_CONFIRMATION -> {
                        currentVoiceState = VoiceInteractionState.PRE_TASK_CONFIRMATION
                        val history = synchronized(preTaskHistoryBuilder) {
                            preTaskHistoryBuilder.append("User: ").append(userSpeech).append("\n")
                            preTaskHistoryBuilder.toString()
                        }

                        val result = io.agents.pokeclaw.agent.PromptRewriter.processTurn(
                            userSpeech = userSpeech,
                            conversationHistory = history,
                            mode = io.agents.pokeclaw.agent.PromptRewriter.InteractionMode.PRE_TASK_CONFIRMATION
                        )

                        if (generation != voiceGeneration.get()) {
                            XLog.i(TAG, "Dropping stale voice turn result (SOS or reset happened meanwhile)")
                            return@execute
                        }

                        synchronized(preTaskHistoryBuilder) {
                            preTaskHistoryBuilder.append("Saarthi: ").append(result.spokenResponse).append("\n")
                        }
                        io.agents.pokeclaw.service.VoiceManager.speakAsync(result.spokenResponse, flush = true)

                        activity.runOnUiThread {
                            addSystem(result.spokenResponse)
                        }

                        if (result.isCancelled) {
                            currentVoiceState = VoiceInteractionState.IDLE
                            clearPreTaskHistory()
                            VoiceManager.onPlaybackFinished = null
                        } else if (result.isConfirmed && result.cleanedEnglishTask.isNotBlank()) {
                            currentVoiceState = VoiceInteractionState.TASK_EXECUTING
                            clearPreTaskHistory()
                            VoiceManager.onPlaybackFinished = null
                            activity.runOnUiThread {
                                // No task started (already running, no config, ...): don't stay in TASK_EXECUTING.
                                if (!sendTask(result.cleanedEnglishTask) &&
                                    currentVoiceState == VoiceInteractionState.TASK_EXECUTING) {
                                    currentVoiceState = VoiceInteractionState.IDLE
                                }
                            }
                        } else {
                            currentVoiceState = VoiceInteractionState.PRE_TASK_CONFIRMATION
                            VoiceManager.onPlaybackFinished = {
                                if (currentVoiceState == VoiceInteractionState.PRE_TASK_CONFIRMATION) {
                                    activity.runOnUiThread {
                                        onTriggerVoiceCapture?.invoke()
                                    }
                                }
                            }
                        }
                    }
                    VoiceInteractionState.TASK_EXECUTING -> {
                        XLog.i(TAG, "Speech input received during active task execution: '$userSpeech'")
                        val lower = userSpeech.lowercase().trim()
                        // Whole words only, and only in a short utterance ("bus stop ke paas" is not a cancel).
                        val isCancelCommand = CANCEL_WORDS.containsMatchIn(lower) &&
                                lower.split(Regex("\\s+")).size <= 4

                        if (isCancelCommand) {
                            XLog.w(TAG, "Voice stop command received during task execution — cancelling task immediately")
                            appViewModel.stopTask()
                            currentVoiceState = VoiceInteractionState.IDLE
                            clearPreTaskHistory()
                            VoiceManager.speak(SpokenStrings.taskStopped())
                            activity.runOnUiThread {
                                addSystem("Task cancelled via voice command.")
                            }
                        } else {
                            XLog.i(TAG, "Voice redirection command received during active task — updating task goal to: '$userSpeech'")
                            appViewModel.stopTask()
                            currentVoiceState = VoiceInteractionState.PRE_TASK_CONFIRMATION
                            clearPreTaskHistory()
                            processVoiceTurn(userSpeech)
                        }
                    }
                }
            } catch (e: Exception) {
                XLog.e(TAG, "Error in processVoiceTurn", e)
            }
        }
    }

    @Volatile
    private var isTaskExecutingLock = false

    /**
     * Returns true when a task was started (or a direct tool / Accessibility retry is under way),
     * false when nothing was started, so the voice loop can leave TASK_EXECUTING.
     */
    fun sendTask(text: String): Boolean {
        if (handleConfirmationReply(text)) return true
        if (appViewModel.isTaskRunning() || isTaskExecutingLock) {
            addSystem("A task is already running. Stop it first.")
            return false
        }

        if (ModelConfigRepository.snapshot().isLocalActive() && isLikelyMonitorRequest(text)) {
            addUser(text)
            addSystem("Local mode starts monitoring from the Background card. Open Background, choose the app/contact, then tap Start Monitoring.")
            return false
        }

        when (AppCapabilityCoordinator.accessibilityState(activity)) {
            ServiceBindingState.DISABLED -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool without Accessibility")
                    return executeDirectToolTask(text, directTool)
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task without Accessibility")
                } else {
                Toast.makeText(activity, "Enable Accessibility Service to run tasks", Toast.LENGTH_LONG).show()
                addSystem("⚠️ Task mode needs Accessibility Service enabled. Opening Settings...")
                openSettings()
                sendTaskRetryCount = 0
                return false
                }
            }
            ServiceBindingState.CONNECTING -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool while Accessibility connects")
                    return executeDirectToolTask(text, directTool)
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task while Accessibility connects")
                } else {
                if (sendTaskRetryCount >= 1) {
                    Toast.makeText(activity, "Accessibility service not connected. Try toggling it off and on.", Toast.LENGTH_LONG).show()
                    addSystem("Accessibility service didn't connect. Try toggling it off and on in Settings.")
                    openSettings()
                    sendTaskRetryCount = 0
                    return false
                }
                sendTaskRetryCount++
                addSystem("Accessibility service connecting, please wait...")
                runOffMain {
                    val connected = ClawAccessibilityService.awaitRunning(5000)
                    activity.runOnUiThread {
                        if (connected) {
                            if (!sendTask(text) && currentVoiceState == VoiceInteractionState.TASK_EXECUTING) {
                                currentVoiceState = VoiceInteractionState.IDLE
                            }
                        } else {
                            Toast.makeText(activity, "Accessibility service didn't connect", Toast.LENGTH_LONG).show()
                            addSystem("Accessibility service didn't connect. Go to Settings and toggle it off then on.")
                            sendTaskRetryCount = 0
                            cleanupAfterTask()
                        }
                    }
                }
                return true
                }
            }
            ServiceBindingState.DEGRADED -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool while Accessibility is degraded")
                    return executeDirectToolTask(text, directTool)
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task while Accessibility is degraded")
                } else {
                    Toast.makeText(activity, "Accessibility service disconnected. Open Settings and toggle it back on.", Toast.LENGTH_LONG).show()
                    addSystem("Accessibility service disconnected. Open Settings and toggle it off then on.")
                    openSettings()
                    sendTaskRetryCount = 0
                    return false
                }
            }
            ServiceBindingState.READY -> Unit
        }
        sendTaskRetryCount = 0

        ensureNotificationPermission()
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false

        // The selected model needs its own key (cloud) or a downloaded model (local); never another provider's key.
        val modelConfig = ModelConfigRepository.snapshot()
        val aiReady = if (modelConfig.isLocalActive()) modelConfig.local.isConfigured
            else modelConfig.activeCloud.apiKey.isNotBlank()
        if (!aiReady) {
            val line = SpokenStrings.aiNotSetUp()
            addUser(text)
            addSystem(line)
            VoiceManager.speakAsync(line, flush = true)
            onPersistConversation()
            return false
        }

        // Close the window between here and appViewModel.startTask (executor hop + main post),
        // during which isTaskRunning() is still false and a second sendTask could slip through.
        isTaskExecutingLock = true

        val agentPromptOverride = buildAgentPromptOverride(text)
        replySeq.incrementAndGet()
        addUser(text)
        // One short start line (voice turns already spoke theirs in PromptRewriter); no per-step speech.
        if (currentVoiceState != VoiceInteractionState.TASK_EXECUTING) {
            VoiceManager.speakAsync(SpokenStrings.taskStart(SpokenStrings.appNameIn(text)), flush = true)
        }
        uiState.isAwaitingReply.value = true
        uiState.isTaskRunning.value = true
        XLog.i(TAG, "sendTask: isProcessing=TRUE")
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        val taskId = "task_${System.currentTimeMillis()}"

        try {
            executor.submit {
                chatSessionController.prepareForTaskStart()

                activity.runOnUiThread {
                    try {
                        appViewModel.startTask(text, taskId, agentPromptOverride = agentPromptOverride) { event ->
                            activity.runOnUiThread { handleTaskEvent(event) }
                        }
                    } catch (e: Exception) {
                        XLog.e(TAG, "sendTask failed: ${e.message}", e)
                        addSystem("Error: ${e.message}")
                        cleanupAfterTask()
                    } finally {
                        // From here on appViewModel.isTaskRunning() guards against a double start
                        // (or the task already settled), so the short-window lock is no longer needed.
                        isTaskExecutingLock = false
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            XLog.e(TAG, "sendTask: executor shut down", e)
            removeTypingIndicator()
            cleanupAfterTask()
            return false
        }
        return true
    }

    /**
     * The user's reply while a message / call waits for her yes (OutboundConfirmGate). "haan" runs exactly
     * the parked call, "na" drops it; anything else drops it and returns false so it is handled as a
     * new request. Returns true when the reply was consumed here. Call on the main thread.
     */
    fun handleConfirmationReply(text: String): Boolean {
        if (OutboundConfirmGate.peek() == null) return false
        if (appViewModel.isTaskRunning() || isTaskExecutingLock) return false
        return when (OutboundConfirmGate.classify(text)) {
            OutboundConfirmGate.Reply.YES -> {
                val approved = OutboundConfirmGate.approve() ?: return false
                VoiceManager.onPlaybackFinished = null
                XLog.i(TAG, "User approved parked ${approved.toolName}")
                executeDirectToolTask(text, DirectDeviceDataGuard.DeterministicToolCall(approved.toolName, approved.params))
            }
            OutboundConfirmGate.Reply.NO -> {
                // Drop only the action she was asked about; a queued one behind it is asked next.
                val more = OutboundConfirmGate.decline()
                VoiceManager.onPlaybackFinished = null
                currentVoiceState = VoiceInteractionState.IDLE
                clearPreTaskHistory()
                addUser(text)
                val line = SpokenStrings.declined()
                addSystem(line)
                VoiceManager.speakAsync(line, flush = true)
                if (more) askNextQueuedConfirmation()
                onPersistConversation()
                true
            }
            OutboundConfirmGate.Reply.OTHER -> {
                OutboundConfirmGate.clear()
                false
            }
        }
    }

    private fun executeDirectToolTask(text: String, toolCall: DirectDeviceDataGuard.DeterministicToolCall): Boolean {
        ensureNotificationPermission()
        addUser(text)
        isTaskExecutingLock = true // cleanupAfterTask clears it
        uiState.isAwaitingReply.value = true
        uiState.isTaskRunning.value = false
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        replySeq.incrementAndGet()
        runOffMain {
            try {
                val result = ToolRegistry.getInstance().executeTool(toolCall.toolName, toolCall.params)
                // The tool parked itself behind the confirm gate: show its question, not "Failed: CONFIRMATION NEEDED".
                val askResult = OutboundConfirmGate.isAskResult(result)
                val answer = if (askResult) {
                    null
                } else {
                    val english = if (result.isSuccess) {
                        result.data ?: "Done."
                    } else {
                        "Failed: ${result.error ?: "unknown error"}"
                    }
                    // Tool results are English (the agent reads them); the user hears her reply language.
                    ReplyLocalizer.localize(english)
                }
                activity.runOnUiThread {
                    if (answer != null) {
                        replaceTypingIndicator(answer)
                        VoiceManager.speakAsync(answer, flush = true)
                    } else {
                        removeTypingIndicator()
                    }
                    cleanupAfterTask()
                    // Another send / call may have been queued behind this one: ask it now.
                    askNextQueuedConfirmation()
                }
            } catch (e: Exception) {
                XLog.e(TAG, "executeDirectToolTask failed: ${e.message}", e)
                activity.runOnUiThread {
                    replaceTypingIndicator("Error: ${e.message}")
                    cleanupAfterTask()
                }
            }
        }
        return true
    }

    /**
     * Asks the oldest action still waiting behind the confirm gate (marks it asked), then reopens the
     * mic for her yes / no once the question has been spoken. No-op when nothing waits. Main thread.
     */
    private fun askNextQueuedConfirmation() {
        val next = OutboundConfirmGate.pendingQuestion() ?: return
        addSystem(next)
        VoiceManager.speakAsync(next, flush = false)
        VoiceManager.onPlaybackFinished = {
            if (OutboundConfirmGate.peek() != null) {
                activity.runOnUiThread { onTriggerVoiceCapture?.invoke() }
            }
        }
        onPersistConversation()
    }

    private fun canRunWithoutAccessibility(text: String): Boolean {
        if (DirectDeviceDataGuard.matchesNonInteractiveDeviceDataTask(text)) {
            return true
        }
        return when (pipelineRouter.route(text)) {
            is PipelineRouter.Route.DirectIntent -> true
            else -> false
        }
    }

    fun handleMonitorTask(text: String) {
        val target = MonitorTargetParser.fromTaskText(text)
        if (target == null) {
            addUser(text)
            addSystem("Could not figure out who to monitor. Try: \"Monitor Mom on WhatsApp\"")
            return
        }

        startMonitor(target, typedInput = text)
    }

    fun startMonitor(target: MonitorTargetSpec, typedInput: String? = null) {
        val trimmedLabel = target.label.trim()
        if (trimmedLabel.isEmpty()) {
            addSystem("Could not figure out who to monitor. Try: \"Monitor Mom on WhatsApp\"")
            return
        }

        typedInput?.let { addUser(it) }
        val missing = AppCapabilityCoordinator.missingMonitorRequirements(activity)
        if (missing.isNotEmpty()) {
            Toast.makeText(
                activity,
                "Enable ${missing.joinToString(" & ") { it.label }} in Settings first",
                Toast.LENGTH_LONG
            ).show()
            openSettings()
            return
        }

        val contact = trimmedLabel
        val app = target.app
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false
        addSystem("Setting up auto-reply for $contact on $app...")

        val autoReplyManager = AutoReplyManager.getInstance()
        autoReplyManager.addTarget(contact, app)
        autoReplyManager.setEnabled(true)
        XLog.i(TAG, "startMonitor: enabled auto-reply for '${target.displayLabel}'")

        Handler(Looper.getMainLooper()).postDelayed({
            uiState.isAwaitingReply.value = false
            uiState.isTaskRunning.value = false
            addSystem("✓ Auto-reply is now active for ${target.displayLabel}.\nMonitoring in background — you can stop anytime from the bar above.")
            XLog.i(TAG, "startMonitor: monitor active, staying in PokeClaw")
        }, 1500)
    }

    // "Done." is the orchestrator's placeholder when the agent gave no answer: don't claim success in words.
    private fun cleanSpokenAnswer(raw: String): String =
        if (raw.isBlank() || raw.trim() == "Done.") SpokenStrings.doneCheckScreen() else raw

    /**
     * Speaks the agent's final answer in the reply language. The agent writes English; when the user
     * spoke or typed another language the answer is rewritten (off the main thread) and the chat
     * bubble is updated to the same text. Skipped if a newer request started meanwhile.
     */
    private fun speakAnswerInReplyLanguage(answer: String) {
        val seq = replySeq.get()
        runOffMain {
            val spoken = ReplyLocalizer.localize(cleanSpokenAnswer(answer))
            activity.runOnUiThread {
                if (spoken != answer && answer.isNotBlank()) {
                    val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == answer }
                    if (idx >= 0) {
                        uiState.messages[idx] = uiState.messages[idx].copy(content = spoken)
                        onPersistConversation()
                    }
                }
                if (seq == replySeq.get()) VoiceManager.speakAsync(spoken, flush = true)
            }
        }
    }

    private fun handleTaskEvent(event: TaskEvent) {
        try {
            when (event) {
                is TaskEvent.Completed -> {
                    val fromVoice = currentVoiceState == VoiceInteractionState.TASK_EXECUTING
                    val askingUser = OutboundConfirmGate.peek() != null
                    replaceTypingIndicator(event.answer, event.modelName)
                    // Single owner of the final spoken answer (agent loop no longer speaks it too).
                    if (askingUser) {
                        // Already in her language (SpokenStrings confirmation question).
                        VoiceManager.speakAsync(cleanSpokenAnswer(event.answer), flush = true)
                    } else {
                        speakAnswerInReplyLanguage(event.answer)
                    }
                    cleanupAfterTask()
                    checkAutoReplyConfirmation()
                    if (askingUser && fromVoice) {
                        // The answer was "send / call ...? Haan ya na?": open the mic for her reply.
                        VoiceManager.onPlaybackFinished = {
                            if (OutboundConfirmGate.peek() != null) {
                                activity.runOnUiThread { onTriggerVoiceCapture?.invoke() }
                            }
                        }
                    }
                }
                is TaskEvent.Failed -> {
                    val rawError = event.error
                    // Resolve the "..." now: a late replace could otherwise hit a newer request's indicator.
                    replaceTypingIndicator("Error: $rawError")
                    explainErrorLater(rawError)
                    cleanupAfterTask()
                }
                is TaskEvent.Cancelled -> {
                    removeTypingIndicator()
                    cleanupAfterTask()
                }
                is TaskEvent.Blocked -> {
                    val msg = "Blocked by system dialog."
                    replaceTypingIndicator(msg)
                    explainErrorLater(msg)
                    cleanupAfterTask()
                }
                is TaskEvent.ToolAction -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    if (!event.toolName.contains("Finish", ignoreCase = true)) {
                        removeTypingIndicator()
                        // Folded into one "Working: step N" row by the chat screen (ChatScreen.StepGroup).
                        addStep(event.toolName, success = true)
                        // Not spoken: raw tool names ("Tap Node...") talked over the Sarvam reply.
                    }
                }
                is TaskEvent.ToolResult -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    if (!event.success) addStep("${event.toolName} failed", success = false)
                }
                is TaskEvent.Response -> {
                    uiState.isAwaitingReply.value = false
                    replaceTypingIndicator(event.text)
                }
                is TaskEvent.Progress -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    addStep(event.description, success = true)
                    // Not spoken: only the task start, questions to the user and the final result are spoken.
                }
                is TaskEvent.LoopStart -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                }
                is TaskEvent.TokenUpdate, is TaskEvent.Thinking -> Unit
            }
        } catch (e: Exception) {
            XLog.w(TAG, "handleTaskEvent error", e)
        }
    }

    /**
     * After the activity is recreated (rotation, dark mode, font size) while a chat-started task runs,
     * route that task's events to this screen instead of the destroyed one.
     */
    fun reattachToRunningTask() {
        if (!appViewModel.isTaskRunning()) return
        if (appViewModel.inProgressTaskChannel != io.agents.pokeclaw.channel.Channel.LOCAL) return
        uiState.isTaskRunning.value = true
        appViewModel.attachTaskCallback(appViewModel.inProgressTaskMessageId) { event ->
            activity.runOnUiThread { handleTaskEvent(event) }
        }
        XLog.i(TAG, "reattachToRunningTask: task events now go to the recreated screen")
    }

    /** Adds a plain-language explanation of [rawError] as its own message when the LLM returns. */
    private fun explainErrorLater(rawError: String) {
        runOffMain {
            val explanation = explainTaskErrorWithAi(rawError)
            activity.runOnUiThread {
                uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "💡 $explanation"))
                onPersistConversation()
                VoiceManager.speakAsync(explanation, flush = true)
            }
        }
    }

    private fun replaceTypingIndicator(text: String, actualModelName: String? = null) {
        val modelTag = actualModelName
            ?: uiState.modelStatus.value.removePrefix("● ").split(" ·").firstOrNull()?.trim()
            ?: ""
        val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) {
            uiState.messages[idx] = ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag)
        } else {
            uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag))
        }
        onPersistConversation()
    }

    private fun removeTypingIndicator() {
        val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) uiState.messages.removeAt(idx)
    }

    private fun cleanupAfterTask() {
        XLog.i(TAG, "cleanupAfterTask: isProcessing=FALSE")
        isTaskExecutingLock = false
        currentVoiceState = VoiceInteractionState.IDLE
        clearPreTaskHistory()
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false
        appViewModel.clearTaskCallback()
        onTaskSettled?.invoke()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                chatSessionController.loadModelIfReady(
                    conversationId = currentConversationId(),
                    visibleMessages = uiState.messages.toList(),
                )
            } catch (e: Exception) {
                XLog.e(TAG, "cleanupAfterTask: loadModel error", e)
            }
        }, 500)
    }

    private fun explainTaskErrorWithAi(technicalError: String): String {
        return try {
            val lang = ReplyLanguageState.replyLanguage()
            val systemPrompt = "You are Saarthi, an empathetic AI assistant. Explain phone automation errors to an elderly user in simple, conversational language. ${lang.promptInstruction()}"
            val prompt = """The user's phone task encountered this technical error:
"$technicalError"

Explain what went wrong in 1-2 friendly, simple sentences in ${lang.englishName} (${lang.script} script):"""
            val explanation = LlmSessionManager.singleShotInteraction(systemPrompt, prompt, 0.3)
            explanation?.trim() ?: "Task execution failed: $technicalError"
        } catch (e: Exception) {
            "Task execution failed: $technicalError"
        }
    }

    private fun checkAutoReplyConfirmation() {
        val autoReplyManager = AutoReplyManager.getInstance()
        if (!autoReplyManager.isEnabled) {
            lastMonitorStatusNote = null
            return
        }
        val contacts = autoReplyManager.monitoredContacts.joinToString(", ")
        if (contacts.isBlank()) {
            lastMonitorStatusNote = null
            return
        }
        val note = "✓ Auto-reply active for $contacts.\nMonitoring in background — stop from bar above."
        if (note == lastMonitorStatusNote) return
        addSystem(note)
        lastMonitorStatusNote = note
        XLog.i(TAG, "checkAutoReplyConfirmation: monitor active, staying in PokeClaw")
    }

    private fun ensureNotificationPermission() {
        if (!AppCapabilityCoordinator.isNotificationPermissionGranted(activity)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun addUser(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.USER, text))
    }

    private fun addSystem(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    /**
     * One agent step ("Tap Node", "Wait", "Open App", progress text). Stored as TOOL_GROUP so the chat
     * folds a run of them into a single collapsible status row. Content carries the same line so a
     * conversation reloaded from disk (which keeps only text) still shows the steps.
     */
    private fun addStep(text: String, success: Boolean) {
        val icon = if (success) "✓" else "○"
        uiState.messages.add(
            ChatMessage(
                role = ChatMessage.Role.TOOL_GROUP,
                content = "- $icon $text",
                toolSteps = listOf(ToolStep(toolName = text, summary = "", success = success)),
            )
        )
    }

    private fun openSettings() {
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }

    private fun buildAgentPromptOverride(rawTask: String): String? {
        if (ModelConfigRepository.snapshot().isLocalActive()) {
            return null
        }

        val historyLines = CloudContextHandoffFormatter.conversationLines(uiState.messages)
        val backgroundStatus = buildBackgroundStatusContext()

        return TaskPromptEnvelope.build(
            chatHistoryLines = historyLines,
            currentRequest = rawTask,
            backgroundState = backgroundStatus,
        )
    }

    private fun buildBackgroundStatusContext(): String? {
        val autoReplyManager = AutoReplyManager.getInstance()
        if (!autoReplyManager.isEnabled) return null

        val contacts = autoReplyManager.monitoredContacts.toList()
        if (contacts.isEmpty()) return null

        return buildString {
            append("Background monitor active for: ")
            append(contacts.joinToString(", "))
            append('.')
        }
    }

    private fun isLikelyMonitorRequest(text: String): Boolean {
        val lower = text.lowercase()
        val mentionsMonitor = lower.contains("monitor") ||
            lower.contains("auto-reply") ||
            lower.contains("auto reply") ||
            lower.contains("autoreply")
        val looksLikeWatchMessages = lower.contains("watch") &&
            (lower.contains("message") || lower.contains("messages") || lower.contains("reply"))
        return mentionsMonitor || looksLikeWatchMessages
    }
}
