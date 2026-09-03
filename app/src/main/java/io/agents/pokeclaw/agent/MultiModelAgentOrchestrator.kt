// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.service.VoiceManager
import io.agents.pokeclaw.service.VoiceRecorder
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MultiModelAgentOrchestrator — Clean separation of concerns for the 3-Tier AI Agent:
 *
 * 1. Audio Thread (STT / TTS): Speech processing on SpeechExecutor.
 * 2. Interaction Thread (Sarvam 105B / Gemini 3.5 Flash Lite): Dialect matching & prompt rewriting on InteractionScope.
 * 3. Main Task Thread (GLM 5.3 Flash / GPT-4.1 / DeepSeek): Phone automation agent loop on MainTaskScope.
 *
 * Thread-safe stop and cancellation: When killAllTasks() is called, all background jobs on both scopes are cancelled instantly!
 */
object MultiModelAgentOrchestrator {

    private const val TAG = "MultiModelOrchestrator"

    // Separate Coroutine Scopes on Dispatchers.IO for Interaction vs Main Task Agent
    private val interactionJob = SupervisorJob()
    private val interactionScope = CoroutineScope(interactionJob + Dispatchers.IO)

    private val mainTaskJob = SupervisorJob()
    private val mainTaskScope = CoroutineScope(mainTaskJob + Dispatchers.IO)

    /**
     * Submit an Interaction Model task (Sarvam 105B / Gemini 3.5 Flash Lite / OpenRouter).
     */
    fun submitInteraction(
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = 0.2,
        onResult: (String?) -> Unit
    ): Job {
        return interactionScope.launch {
            val response = LlmSessionManager.singleShotInteraction(systemPrompt, userPrompt, temperature)
            withContext(Dispatchers.Main) {
                onResult(response)
            }
        }
    }

    /**
     * Submit a Main Model task (GLM 5.3 Flash / OpenRouter Main Model).
     */
    fun submitMainTask(
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = 0.7,
        onResult: (String?) -> Unit
    ): Job {
        return mainTaskScope.launch {
            val response = LlmSessionManager.singleShotCloud(systemPrompt, userPrompt, temperature)
            withContext(Dispatchers.Main) {
                onResult(response)
            }
        }
    }

    /**
     * Kill all running tasks, stop speech playback, and cancel all background threads instantly.
     */
    fun killAllTasks() {
        XLog.i(TAG, "killAllTasks: Stopping all audio, interaction, and main agent threads instantly")

        // 1. Cancel all background jobs on both scopes
        interactionJob.cancelChildren()
        mainTaskJob.cancelChildren()

        // 2. Stop audio recording and playback
        try {
            VoiceRecorder.stopRecording()
            VoiceManager.stop()
            VoiceManager.onPlaybackFinished = null
        } catch (e: Exception) {
            XLog.w(TAG, "Error stopping voice in killAllTasks", e)
        }
    }
}
