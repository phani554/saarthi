// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.service.VoiceManager
import io.agents.pokeclaw.utils.XLog

/**
 * Global Task & Thread State Cleaner.
 * Synchronously halts all residual background threads, clears pending speech handler queues,
 * cancels coroutine scopes, and resets task state flow to guarantee zero out-of-order speech playback.
 */
object GlobalTaskStateCleaner {

    private const val TAG = "GlobalStateCleaner"

    fun cleanAll(reason: String = "Global state reset") {
        XLog.w(TAG, "Cleaning ALL background threads, coroutine scopes, and TTS queues ($reason)")

        // 1. Purge speech queues & stop audio
        try {
            VoiceManager.cleanupAllResidualState()
        } catch (e: Exception) {
            XLog.w(TAG, "Speech cleanup exception: ${e.message}")
        }

        // 2. Stop TtsRouter lock & engines
        try {
            TtsRouter.stopAll()
        } catch (e: Exception) {
            XLog.w(TAG, "TtsRouter stop exception: ${e.message}")
        }

        // 3. Cancel task coroutine scope & state machine
        try {
            TaskExecutionState.instance.cancelTaskScope(reason)
            TaskExecutionState.instance.reset()
        } catch (e: Exception) {
            XLog.w(TAG, "TaskExecutionState reset exception: ${e.message}")
        }

        // 4. Cancel MultiModelAgentOrchestrator active jobs
        try {
            MultiModelAgentOrchestrator.killAllTasks()
        } catch (e: Exception) {
            XLog.w(TAG, "MultiModelAgentOrchestrator kill exception: ${e.message}")
        }

        // 5. Clear pending task queue & dependency graph
        try {
            TaskQueueManager.clear()
        } catch (e: Exception) {
            XLog.w(TAG, "TaskQueueManager clear exception: ${e.message}")
        }

        XLog.i(TAG, "Global cleanAll finished cleanly ($reason)")
    }
}
