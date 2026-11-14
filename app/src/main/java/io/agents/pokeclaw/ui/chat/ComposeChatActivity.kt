// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.ServiceBindingState
import io.agents.pokeclaw.agent.MultiModelAgentOrchestrator
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.floating.FloatingCircleManager
import io.agents.pokeclaw.service.VoiceManager
import io.agents.pokeclaw.ui.settings.LlmConfigActivity
import io.agents.pokeclaw.ui.settings.SettingsActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.Executors

/**
 * PokeClaw Chat Activity — Compose shell for the chat screen.
 *
 * Chat runtime ownership lives in [ChatSessionController].
 * This activity keeps lifecycle wiring, task flows, and sidebar/history UI state.
 */
class ComposeChatActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ComposeChatActivity"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_CHAT = "chat"
        private const val MAX_AUTOMATION_WAITS = 30
    }

    private val automationHandler = Handler(Looper.getMainLooper())

    private val executor = Executors.newSingleThreadExecutor()
    private val conversationStore by lazy { ConversationStore(this) }

    // Compose state — observed by ChatScreen
    private val _messages = mutableStateListOf<ChatMessage>()
    private val _modelStatus = mutableStateOf("No model loaded")
    private val _isLocalModelActive = mutableStateOf(ModelConfigRepository.isLocalActive())
    private val _needsPermission = mutableStateOf(false)
    private val _isAwaitingReply = mutableStateOf(false)
    private val _isTaskRunning = mutableStateOf(false)
    private val _inputEnabled = mutableStateOf(true)    // False when model not ready (no task running)
    private val _conversations = mutableStateListOf<ChatHistoryManager.ConversationSummary>()
    private val _isDownloading = mutableStateOf(false)
    private val _downloadProgress = mutableStateOf(0)

    // Session-level token tracking for chat mode
    private val _sessionTokens = mutableStateOf(0)
    private val _sessionCost = mutableStateOf(0.0)
    private var deferLocalChatBootstrapForAutoTask = false

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                // Android's recogniser gives no language code: judge the reply language from the text.
                io.agents.pokeclaw.lang.ReplyLanguageState.onText(spokenText)
                taskFlowController.processVoiceTurn(spokenText)
            }
        }
    }

    private fun triggerVoiceCapture() {
        // Allowed while a task runs: that is how "stop" / a new instruction reaches the task.
        // Also allowed with no model selected, so a spoken SOS still works; TaskFlowController
        // refuses ordinary voice tasks in that state.

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                200
            )
            return
        }

        if (VoiceManager.isPlayingAudio()) {
            // Barge-in: stop the reply that is playing and listen.
            VoiceManager.stopAudioPlayback()
        }

        if (VoiceManager.isRecordingInApp()) {
            VoiceManager.stopInAppVoiceCapture()
            // No toast: the status area above the input shows the voice state (toasts covered the input).
            return
        }

        val hasSarvamKey = KVUtils.getSarvamApiKey().trim().isNotEmpty()
        val hasGeminiKey = KVUtils.getVoiceGeminiKey().trim().isNotEmpty()

        if (hasSarvamKey || hasGeminiKey) {
            // "Listening" is shown in the chat's status area (VoiceEngineState.Recording), not a toast.
            VoiceManager.startInAppVoiceCapture(this) { transcribedText ->
                runOnUiThread {
                    if (!transcribedText.isNullOrBlank()) {
                        taskFlowController.processVoiceTurn(transcribedText)
                    } else {
                        Toast.makeText(this, "No speech detected", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolein aapki kya madad karoon...")
                // A fixed Settings › Reply language is also the recogniser language; Auto keeps the phone's.
                io.agents.pokeclaw.lang.ReplyLanguageState.fixed()?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it.code) }
            }
            try {
                speechLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val chatSessionController by lazy {
        ChatSessionController(
            activity = this,
            executor = executor,
            uiState = ChatSessionUiState(
                messages = _messages,
                modelStatus = _modelStatus,
                isAwaitingReply = _isAwaitingReply,
                inputEnabled = _inputEnabled,
                isDownloading = _isDownloading,
                downloadProgress = _downloadProgress,
                sessionTokens = _sessionTokens,
                sessionCost = _sessionCost,
            ),
            onPersistConversation = { saveChat() },
            onRefreshSidebarHistory = { refreshSidebarHistory() },
            isTaskRunning = { appViewModel.isTaskRunning() },
        )
    }

    private val taskFlowController by lazy {
        TaskFlowController(
            activity = this,
            executor = executor,
            appViewModel = appViewModel,
            chatSessionController = chatSessionController,
            currentConversationId = { conversationStore.currentConversationId },
            uiState = TaskFlowUiState(
                messages = _messages,
                modelStatus = _modelStatus,
                isAwaitingReply = _isAwaitingReply,
                isTaskRunning = _isTaskRunning,
            ),
            onPersistConversation = { saveChat() },
            onTaskSettled = { deferLocalChatBootstrapForAutoTask = false },
        )
    }

    private val activeTaskShellController by lazy {
        ActiveTaskShellController(appViewModel = appViewModel)
    }

    // Permission polling
    private val permHandler = Handler(Looper.getMainLooper())
    private val permPoller = object : Runnable {
        override fun run() {
            _needsPermission.value =
                AppCapabilityCoordinator.accessibilityState(this@ComposeChatActivity) != ServiceBindingState.READY
            permHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        VoiceManager.init(this)
        taskFlowController.onTriggerVoiceCapture = { triggerVoiceCapture() }

        // Hide floating circle only when no task is running
        try {
            if (!appViewModel.isTaskRunning()) {
                FloatingCircleManager.hide()
            } else {
                XLog.d(TAG, "onCreate: task running, keeping floating circle visible")
            }
        } catch (_: Exception) {}

        // Check for updates
        io.agents.pokeclaw.utils.UpdateChecker.checkForUpdate(this)

        // Status bar color
        val themeColors = ThemeManager.getColors()
        window.statusBarColor = themeColors.toolbarBg

        // Build Compose colors from ThemeManager
        val composeColors = with(ThemeManager) { themeColors.toComposeColors() }

        setContent {
          // Large text (elder mode): bigger text and touch targets when the Settings switch is ON.
          io.agents.pokeclaw.ui.ElderScaled {
            val activeTasks by activeTaskShellController.activeTasks.collectAsState()

            ChatScreen(
                messages = _messages.toList(),
                modelStatus = _modelStatus.value,
                needsPermission = _needsPermission.value,
                isAwaitingReply = _isAwaitingReply.value,
                isTaskRunning = _isTaskRunning.value,
                inputEnabled = _inputEnabled.value,
                isDownloading = _isDownloading.value,
                downloadProgress = _downloadProgress.value,
                isLocalModel = _isLocalModelActive.value,
                sessionTokens = _sessionTokens.value,
                sessionCost = _sessionCost.value,
                onSendChat = { sendChat(it) },
                onSendTask = {
                    // Typed text sets the reply language too (typed English gets English replies).
                    io.agents.pokeclaw.lang.ReplyLanguageState.onText(it)
                    taskFlowController.sendTask(it)
                },
                onVoiceInputTap = { triggerVoiceCapture() },
                onStartMonitor = { target -> taskFlowController.startMonitor(target) },
                onSendDirectMessage = { contact, app, message ->
                    taskFlowController.sendTask("send \"$message\" to $contact on $app")
                },
                onNewChat = { newChat() },
                onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onOpenModels = { startActivity(Intent(this, LlmConfigActivity::class.java)) },
                onFixPermissions = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onAttach = { Toast.makeText(this, "Image upload coming soon", Toast.LENGTH_SHORT).show() },
                conversations = _conversations.toList(),
                onSelectConversation = { loadConversation(it) },
                onDeleteConversation = { conv ->
                    val deleted = conversationStore.deleteConversation(conv)
                    XLog.i(TAG, "Delete conversation: ${conv.file.absolutePath} deleted=$deleted")
                    if (deleted && conv.id == conversationStore.currentConversationId) {
                        // Clear first so newChat() doesn't save the deleted conversation back to disk.
                        _messages.clear()
                        newChat()
                    }
                    refreshSidebarHistory()
                },
                onRenameConversation = { conv, newName ->
                    val renamed = conversationStore.renameConversation(conv, newName)
                    XLog.i(TAG, "Rename conversation: '${conv.title}' → '$newName' renamed=$renamed")
                    refreshSidebarHistory()
                },
                activeTasks = activeTasks,
                onStopTask = { contact ->
                    _isTaskRunning.value = appViewModel.isTaskRunning()
                    Toast.makeText(
                        this,
                        activeTaskShellController.stopTask(contact),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onStopAllTasks = {
                    // Monitor bar "Stop All": stops the auto-reply monitors only.
                    val stopMsg = activeTaskShellController.stopAllMonitors()
                    Toast.makeText(this, stopMsg, Toast.LENGTH_SHORT).show()
                },
                onKillTask = {
                    // KILL TASK banner / stop FAB: stops the running phone task, keeps the monitors.
                    _isAwaitingReply.value = false
                    _isTaskRunning.value = false
                    val stopMsg = activeTaskShellController.killCurrentTask()
                    MultiModelAgentOrchestrator.killAllTasks()
                    taskFlowController.resetVoiceState()
                    Toast.makeText(this, stopMsg, Toast.LENGTH_SHORT).show()
                },
                onModelSwitch = { modelId, displayName -> switchModel(modelId, displayName) },
                colors = composeColors,
            )
          }
        }

        refreshSidebarHistory()

        // Restore last conversation if Activity was recreated
        if (_messages.isEmpty()) {
            conversationStore.restoreLastConversation()?.let { restored ->
                syncSidebar(restored.conversations)
                if (restored.messages.isNotEmpty()) {
                    _messages.addAll(restored.messages)
                    XLog.i(
                        TAG,
                        "Restored ${restored.messages.size} messages from conversation ${restored.conversationId}"
                    )
                }
            }
        }

        deferLocalChatBootstrapForAutoTask = shouldDeferLocalChatBootstrap(intent)
        if (!deferLocalChatBootstrapForAutoTask) {
            chatSessionController.loadModelIfReady(
                conversationId = conversationStore.currentConversationId,
                visibleMessages = _messages.toList(),
            )
        }
        _isLocalModelActive.value = ModelConfigRepository.isLocalActive()

        // Recreated mid-task (rotation, theme, font size): pick the running task's events back up.
        taskFlowController.reattachToRunningTask()

        appViewModel.onBeforeTask = {
            chatSessionController.releaseForTask()
        }

        handleIntentAutomation(intent, initialDelayMs = 2000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deferLocalChatBootstrapForAutoTask = shouldDeferLocalChatBootstrap(intent)
        handleIntentAutomation(intent, initialDelayMs = 1000)
    }

    override fun onResume() {
        super.onResume()
        _needsPermission.value =
            AppCapabilityCoordinator.accessibilityState(this) != ServiceBindingState.READY
        _isLocalModelActive.value = ModelConfigRepository.isLocalActive()
        _isTaskRunning.value = appViewModel.isTaskRunning()
        refreshSidebarHistory()
        permHandler.removeCallbacks(permPoller)
        permHandler.postDelayed(permPoller, 1000)
        activeTaskShellController.onResume()
        if (!deferLocalChatBootstrapForAutoTask) {
            chatSessionController.onResume(
                conversationId = conversationStore.currentConversationId,
                visibleMessages = _messages.toList(),
            )
        }
    }

    override fun onPause() {
        super.onPause()
        saveChat()
        permHandler.removeCallbacks(permPoller)
        activeTaskShellController.onPause()
        chatSessionController.onPause(conversationStore.currentConversationId)
    }

    override fun onDestroy() {
        super.onDestroy()
        automationHandler.removeCallbacksAndMessages(null)
        chatSessionController.onDestroy()
        executor.shutdown()
    }

    // ==================== CHAT ====================

    private fun sendChat(text: String) {
        io.agents.pokeclaw.lang.ReplyLanguageState.onText(text)
        // "haan" / "na" to a parked message or call (asked at the end of a task) is not a chat turn.
        if (taskFlowController.handleConfirmationReply(text)) return
        chatSessionController.sendChat(text)
    }

    private fun handleIntentAutomation(intent: Intent?, initialDelayMs: Long) {
        val taskText = intent?.getStringExtra(EXTRA_TASK)?.takeIf { it.isNotBlank() }
        val chatText = intent?.getStringExtra(EXTRA_CHAT)?.takeIf { it.isNotBlank() }
        val automationText = taskText ?: chatText ?: return
        val isTask = taskText != null
        val isDeferredLocalTask = isTask && shouldDeferLocalChatBootstrap(intent)

        XLog.i(
            TAG,
            if (isTask) "Auto-task from intent: $automationText" else "Auto-chat from intent: $automationText"
        )

        val handler = automationHandler
        handler.postDelayed(object : Runnable {
            private var attempts = 0
            override fun run() {
                if (isFinishing || isDestroyed) return
                if (isDeferredLocalTask) {
                    taskFlowController.sendTask(automationText)
                    return
                }
                if (chatSessionController.isModelReady()) {
                    if (isTask) {
                        taskFlowController.sendTask(automationText)
                    } else {
                        sendChat(automationText)
                    }
                } else if (++attempts < MAX_AUTOMATION_WAITS) {
                    handler.postDelayed(this, 1000)
                } else {
                    XLog.w(TAG, "Auto-${if (isTask) "task" else "chat"} dropped: model never became ready")
                    Toast.makeText(this@ComposeChatActivity, "Model not ready, request not sent", Toast.LENGTH_LONG).show()
                }
            }
        }, initialDelayMs)
    }

    private fun shouldDeferLocalChatBootstrap(intent: Intent?): Boolean {
        val taskText = intent?.getStringExtra(EXTRA_TASK)?.takeIf { it.isNotBlank() } ?: return false
        return taskText.isNotBlank() && ModelConfigRepository.isLocalActive()
    }

    private fun syncTaskAgentConfig() {
        if (!appViewModel.updateAgentConfig()) {
            XLog.w(TAG, "syncTaskAgentConfig: failed to update task agent config")
        }
    }

    private fun switchModel(modelId: String, displayName: String) {
        chatSessionController.switchModel(modelId, displayName)
        _isLocalModelActive.value = ModelConfigRepository.isLocalActive()
        if (modelId != "NONE") {
            syncTaskAgentConfig()
        }
        XLog.i(TAG, "Model switched to: $modelId ($displayName)")
    }

    private fun newChat() {
        val session = conversationStore.startNewConversation(_messages, currentConversationModelName())
        syncSidebar(session.conversations)
        _messages.clear()
        _sessionTokens.value = 0
        _sessionCost.value = 0.0
        _isAwaitingReply.value = false
        _isTaskRunning.value = false
        chatSessionController.startNewConversationRuntime()
    }

    private fun loadConversation(conv: ChatHistoryManager.ConversationSummary) {
        val session = conversationStore.openConversation(conv, _messages, currentConversationModelName())
        syncSidebar(session.conversations)
        _messages.clear()
        _messages.addAll(session.messages)
        _isAwaitingReply.value = false
        _isTaskRunning.value = false
        chatSessionController.restoreConversationRuntime(session.conversationId, session.messages)
    }

    private fun saveChat() {
        syncSidebar(conversationStore.saveCurrent(_messages, currentConversationModelName()))
    }

    private fun refreshSidebarHistory() {
        syncSidebar(conversationStore.refreshSidebar())
    }

    private fun syncSidebar(convos: List<ChatHistoryManager.ConversationSummary>) {
        _conversations.clear()
        _conversations.addAll(convos)
    }

    private fun currentConversationModelName(): String {
        val config = ModelConfigRepository.snapshot()
        return if (config.isLocalActive()) {
            config.local.displayName.ifBlank {
                KVUtils.getLocalModelPath().substringAfterLast('/').substringBeforeLast('.')
            }
        } else {
            config.activeCloud.modelName
        }
    }
}
