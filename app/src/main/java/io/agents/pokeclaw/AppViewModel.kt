// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw

import android.os.PowerManager
import androidx.lifecycle.ViewModel
import io.agents.pokeclaw.ClawApplication.Companion.appViewModelInstance
import io.agents.pokeclaw.TaskEvent
import io.agents.pokeclaw.agent.AgentConfig
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.channel.ChannelSetup
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.floating.FloatingCircleManager
import io.agents.pokeclaw.server.ConfigServerManager
import io.agents.pokeclaw.service.KeepAliveJobService
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

class AppViewModel : ViewModel() {

    companion object {
        private const val TAG = "AppViewModel"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private var _commonInitialized = false

    val taskOrchestrator = TaskOrchestrator(
        agentConfigProvider = { getAgentConfig() },
        onTaskFinished = { releaseScreenWakeLock() }
    )

    private val channelSetup = ChannelSetup(taskOrchestrator = taskOrchestrator)

    val taskSessionStore: TaskSessionStore
        get() = taskOrchestrator.taskSessionStore
    val inProgressTaskMessageId: String get() = taskSessionStore.snapshot().messageId
    val inProgressTaskChannel: Channel? get() = taskSessionStore.snapshot().channel

    // ==================== Task API (clean interface for Activity) ====================

    /**
     * Called before a task starts — allows the chat UI to release its local LLM conversation
     * so the task agent can use the same LiteRT-LM engine (only 1 session supported).
     */
    var onBeforeTask: (() -> Unit)? = null

    fun startTask(
        task: String,
        taskId: String,
        agentPromptOverride: String? = null,
        onEvent: (TaskEvent) -> Unit,
    ) {
        onBeforeTask?.invoke()
        acquireTaskWakeLock(wakeScreen = false)
        // Keyed by taskId: this task's events go to this caller only (not to a channel task or another screen).
        taskOrchestrator.setTaskEventCallback(taskId, onEvent)
        if (!updateAgentConfig()) {
            taskOrchestrator.setTaskEventCallback(taskId, null)
            onEvent(TaskEvent.Failed("AI service not ready"))
            return
        }
        taskOrchestrator.startNewTask(Channel.LOCAL, task, taskId, agentPromptOverride = agentPromptOverride)
    }

    fun stopTask() {
        taskOrchestrator.cancelCurrentTask()
    }

    fun isTaskRunning(): Boolean = taskSessionStore.isTaskRunning()

    /** Next step of a split request, once the yes / no question that paused it is settled. */
    fun resumeQueuedSteps() = taskOrchestrator.resumeQueuedSteps()

    /** Routes the events of the task with [messageId] to [onEvent] (e.g. a recreated chat screen). */
    fun attachTaskCallback(messageId: String, onEvent: (TaskEvent) -> Unit) {
        taskOrchestrator.setTaskEventCallback(messageId, onEvent)
    }

    /** Drops the listener of [messageId], or every in-app listener when null. */
    fun clearTaskCallback(messageId: String? = null) {
        if (messageId == null) taskOrchestrator.clearTaskEventCallbacks()
        else taskOrchestrator.setTaskEventCallback(messageId, null)
    }

    fun init() {
        initCommon()
        initAgent()
    }

    fun initCommon() {
        if (_commonInitialized) return
        _commonInitialized = true
    }

    fun initAgent() {
        if (!KVUtils.hasLlmConfig()) return
        taskOrchestrator.initAgent()
    }

    fun getAgentConfig(): AgentConfig =
        ModelConfigRepository.snapshot().toAgentConfig(
            temperature = 0.1,
            maxIterations = 25
        )

    fun updateAgentConfig(): Boolean = taskOrchestrator.updateAgentConfig()

    fun afterInit() {
        // No wake lock here: it used to light the screen on every (background) process start.
        // The lock is now held only while a task runs (see acquireTaskWakeLock / onTaskFinished).
        KeepAliveJobService.cancel(ClawApplication.instance)
        ForegroundService.syncToBackgroundState(ClawApplication.instance)
        ConfigServerManager.autoStartIfNeeded(ClawApplication.instance)
        channelSetup.setup()
    }


    /**
     * Keep the screen on while a task runs (accessibility actions need it). [wakeScreen] turns the
     * screen on, which only remote (channel) tasks need. Released in onTaskFinished.
     */
    @Synchronized
    fun acquireTaskWakeLock(wakeScreen: Boolean) {
        if (wakeLock?.isHeld == true) return
        val pm = ClawApplication.instance.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            ?: return
        @Suppress("DEPRECATION")
        val flags = if (wakeScreen) PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            else PowerManager.SCREEN_DIM_WAKE_LOCK
        try {
            wakeLock = pm.newWakeLock(flags, "PokeClaw::ScreenWakeLock").apply {
                acquire(10 * 60 * 1000L) // 10 minute timeout to prevent battery drain
            }
            XLog.i(TAG, "Wake lock acquired (wakeScreen=$wakeScreen)")
        } catch (e: Exception) {
            XLog.w(TAG, "Wake lock acquire failed: ${e.message}")
        }
    }

    /**
     * Release the wake lock
     */
    @Synchronized
    private fun releaseScreenWakeLock() {
        wakeLock?.let {
            try {
                if (it.isHeld) {
                    it.release()
                    XLog.i(TAG, "Wake lock released")
                }
            } catch (e: Exception) {
                XLog.w(TAG, "Wake lock release failed: ${e.message}")
            }
        }
        wakeLock = null
    }

    /**
     * Show the circular floating window
     */
    fun showFloatingCircle() {
        try {
            FloatingCircleManager.show(ClawApplication.instance)
            FloatingCircleManager.onFloatClick = {
                XLog.d(TAG, "Floating circle clicked")
                bringAppToForeground()
            }
            FloatingCircleManager.onStopTask = {
                XLog.i(TAG, "Stop task requested from floating pill")
                stopTask()
                bringAppToForeground()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to show floating circle: ${e.message}")
        }
    }

    /**
     * Bring the app to the foreground
     */
    private fun bringAppToForeground() {
        val context = ClawApplication.instance
        val intent = android.content.Intent(context, io.agents.pokeclaw.ui.chat.ComposeChatActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    // Old pass-through methods removed — use startTask/stopTask/isTaskRunning/clearTaskCallback instead

    private fun trySendScreenshot(channel: Channel, filePath: String, messageID: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                XLog.w(TAG, "Screenshot file does not exist: $filePath")
                return
            }
            val imageBytes = file.readBytes()
            ChannelManager.sendImage(channel, imageBytes, messageID)
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to send screenshot", e)
        }
    }
}
