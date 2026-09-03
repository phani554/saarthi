// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.service.VoiceManager
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single TtsRouter that owns Native TTS and Sarvam TTS behind a Mutex lock.
 * Enforces strict mutual exclusion based on AgentTaskState:
 * - Native Android TTS speaks ONLY while task state is Executing or Recovering.
 * - Sarvam TTS speaks for everything else (greetings, confirmations, final wrap-up).
 * - Handoff (Executing -> Completed): synchronously stops Native TTS and awaits completion before Sarvam speaks.
 */
object TtsRouter {

    private const val TAG = "TtsRouter"
    private val mutex = Mutex()
    private val routerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Speak text using the appropriate TTS engine based on current AgentTaskState.
     */
    fun speak(text: String, flush: Boolean = false, onFinished: (() -> Unit)? = null) {
        if (text.isBlank()) return

        routerScope.launch {
            mutex.withLock {
                val currentState = TaskExecutionState.instance.currentState.value
                XLog.i(TAG, "Routing TTS request (state=${currentState.javaClass.simpleName}, flush=$flush): '$text'")

                // Hard lockdown check
                if (currentState is AgentTaskState.CallInProgress) {
                    XLog.w(TAG, "Call in progress — suppressing TTS output")
                    VoiceManager.stop()
                    onFinished?.invoke()
                    return@launch
                }

                val useNative = currentState is AgentTaskState.Executing || currentState is AgentTaskState.Recovering

                if (useNative) {
                    XLog.i(TAG, "Task is Executing -> using Native Android TTS")
                    VoiceManager.speakNative(text, flush)
                    onFinished?.invoke()
                } else {
                    XLog.i(TAG, "Task is ${currentState.javaClass.simpleName} -> using Sarvam Cloud TTS")
                    // Ensure native TTS is fully stopped before Sarvam speaks
                    stopNativeAndAwaitCompletion()
                    VoiceManager.speak(text, flush)
                    onFinished?.invoke()
                }
            }
        }
    }

    /**
     * Perform clean handoff from Executing -> Completed/Failed:
     * Synchronously stops native engine and awaits its completion before speaking the final wrap-up utterance via Sarvam.
     * Replaces technical internal messages ("Task executed within time limit.") with natural user feedback ("Kaam poora ho gaya hai.").
     */
    fun speakFinalWrapUp(summaryText: String, onFinished: (() -> Unit)? = null) {
        routerScope.launch {
            mutex.withLock {
                val state = TaskExecutionState.instance.currentState.value
                if (state is AgentTaskState.CallInProgress) {
                    VoiceManager.stop()
                    onFinished?.invoke()
                    return@launch
                }

                val cleanSummary = when {
                    summaryText.contains("within time limit") || summaryText == "Done." -> "Kaam poora ho gaya hai."
                    else -> summaryText
                }

                XLog.i(TAG, "Performing Executing -> Completed TTS handoff for summary: '$cleanSummary' (raw: '$summaryText')")
                stopNativeAndAwaitCompletion()

                // Speak final wrap-up message via Sarvam TTS
                VoiceManager.speak(cleanSummary, flush = true)
                onFinished?.invoke()
            }
        }
    }

    /**
     * Stop all TTS playback and release locks.
     */
    fun stopAll() {
        routerScope.launch {
            try {
                VoiceManager.stop()
                XLog.i(TAG, "Stopped all TTS engines")
            } catch (e: Exception) {
                XLog.w(TAG, "Error stopping TTS engines: ${e.message}")
            }
        }
    }

    private suspend fun stopNativeAndAwaitCompletion() {
        try {
            VoiceManager.stop()
            withTimeoutOrNull(800L) {
                while (VoiceManager.isPlayingAudio()) {
                    delay(50L)
                }
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Error awaiting native TTS completion: ${e.message}")
        }
    }
}
