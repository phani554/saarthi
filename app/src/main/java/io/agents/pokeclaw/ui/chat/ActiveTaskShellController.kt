// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.os.Handler
import android.os.Looper
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.AppViewModel
import io.agents.pokeclaw.service.AutoReplyManager
import io.agents.pokeclaw.service.ForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns active-task shell state for the top task bar and monitor stop actions.
 *
 * ComposeChatActivity observes the exposed state; it no longer polls AutoReplyManager directly.
 */
class ActiveTaskShellController(
    private val appViewModel: AppViewModel,
) {

    private val autoReplyManager = AutoReplyManager.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private val _activeTasks = MutableStateFlow<List<String>>(emptyList())

    val activeTasks: StateFlow<List<String>> = _activeTasks.asStateFlow()

    private val poller = object : Runnable {
        override fun run() {
            refreshActiveTasks()
            handler.postDelayed(this, 2000)
        }
    }

    fun onResume() {
        refreshActiveTasks()
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    fun onPause() {
        handler.removeCallbacks(poller)
    }

    fun stopTask(contact: String): String {
        autoReplyManager.removeContact(contact)
        if (autoReplyManager.monitoredContacts.isEmpty()) {
            autoReplyManager.isEnabled = false
        }
        refreshActiveTasks()
        ForegroundService.resetToIdle(ClawApplication.instance)
        return "Stopped monitoring $contact"
    }

    fun stopAllTasks(): String {
        var requestedTaskStop = false
        if (appViewModel.isTaskRunning()) {
            appViewModel.stopTask()
            requestedTaskStop = true
        }

        var stoppedMonitoring = false
        if (autoReplyManager.isEnabled) {
            autoReplyManager.stopAll()
            stoppedMonitoring = true
        }

        refreshActiveTasks()
        ForegroundService.resetToIdle(ClawApplication.instance)
        return when {
            requestedTaskStop -> "Stopping current task..."
            stoppedMonitoring -> "All tasks stopped"
            else -> "No active tasks"
        }
    }

    /** Monitor bar "Stop All": stops every auto-reply monitor, leaves a running phone task alone. */
    fun stopAllMonitors(): String {
        val stopped = autoReplyManager.isEnabled
        if (stopped) autoReplyManager.stopAll()
        refreshActiveTasks()
        if (!appViewModel.isTaskRunning()) {
            ForegroundService.resetToIdle(ClawApplication.instance)
        }
        return if (stopped) "All monitors stopped" else "No active monitors"
    }

    /** KILL TASK: stops the running phone task, leaves the auto-reply monitors running. */
    fun killCurrentTask(): String {
        val requested = appViewModel.isTaskRunning()
        if (requested) appViewModel.stopTask()
        if (!autoReplyManager.isEnabled) {
            ForegroundService.resetToIdle(ClawApplication.instance)
        }
        refreshActiveTasks()
        return if (requested) "Stopping current task..." else "Stopped"
    }

    private fun refreshActiveTasks() {
        _activeTasks.value = if (autoReplyManager.isEnabled) {
            autoReplyManager.monitoredContacts.toList()
        } else {
            emptyList()
        }
    }
}
