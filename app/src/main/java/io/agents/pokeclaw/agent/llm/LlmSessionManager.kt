// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.openai.OpenAiChatModel
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.LlmProvider
import io.agents.pokeclaw.agent.langchain.http.OkHttpClientBuilderAdapter
import io.agents.pokeclaw.service.VoiceManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

/**
 * Single source of truth for LLM client creation.
 *
 * Eliminates duplicate client construction in ComposeChatActivity,
 * AutoReplyManager.generateReplyCloud(), and AutoReplyManager.singleLlmCall().
 *
 * Thread-safe. All methods can be called from any thread.
 */
object LlmSessionManager {

    private const val TAG = "LlmSessionManager"
    private const val DEFAULT_LOCAL_SYSTEM_PROMPT = "You are a helpful assistant. Answer concisely."

    /**
     * Create a Cloud LLM ChatModel using the user's current config.
     * Returns null if no API key is configured.
     */
    fun createCloudChatModel(temperature: Double = 0.7): ChatModel? {
        val config = ModelConfigRepository.snapshot()
        if (config.activeMode == ActiveModelMode.LOCAL) {
            XLog.w(TAG, "createCloudChatModel: local mode is active")
            return null
        }

        val cloud = config.activeCloud
        if (cloud.apiKey.isEmpty()) {
            XLog.w(TAG, "createCloudChatModel: no API key configured")
            return null
        }

        val forceOpenRouter = KVUtils.isRouteViaOpenRouter()
        val openRouterKey = KVUtils.getApiKeyForProvider("OPENROUTER").ifEmpty { cloud.apiKey }.trim()
        val modelName = if (forceOpenRouter && !cloud.modelName.contains("/")) {
            "google/gemini-3.8-flash"
        } else {
            cloud.modelName.ifEmpty { "gpt-4o-mini" }
        }
        val baseUrl = if (forceOpenRouter) "https://openrouter.ai/api/v1" else cloud.resolvedBaseUrl.ifEmpty { "https://api.openai.com/v1" }

        XLog.d(TAG, "createCloudChatModel: forceOpenRouter=$forceOpenRouter model=$modelName baseUrl=$baseUrl")
        return OpenAiChatModel.builder()
            .httpClientBuilder(OkHttpClientBuilderAdapter())
            .apiKey(if (forceOpenRouter) openRouterKey else cloud.apiKey)
            .modelName(modelName)
            .baseUrl(baseUrl)
            .customHeaders(mapOf("HTTP-Referer" to "https://pokeclaw.agents.io", "X-Title" to "PokeClaw"))
            .temperature(temperature)
            .build()
    }

    /**
     * Creates a ChatModel specifically configured for the designated Voice Interaction & Dialect Model
     * (e.g., sarvam-105b, z-ai/glm-5.3-flash, or google/gemini-3.5-flash-lite).
     */
    fun createInteractionChatModel(temperature: Double = 0.3): ChatModel? {
        val interactionModel = KVUtils.getInteractionModelId().ifBlank { "google/gemini-3.5-flash-lite" }
        val sarvamKey = KVUtils.getSarvamApiKey().trim()
        val forceOpenRouter = KVUtils.isRouteViaOpenRouter()
        val openRouterKey = KVUtils.getApiKeyForProvider("OPENROUTER").ifEmpty { KVUtils.getLlmApiKey() }.trim()

        if (!forceOpenRouter && (interactionModel.startsWith("sarvam") || interactionModel == "sarvam-105b")) {
            if (sarvamKey.isEmpty()) {
                XLog.w(TAG, "Sarvam interaction model selected but Sarvam API key is blank")
                return null
            }
            XLog.d(TAG, "createInteractionChatModel: using Sarvam-105b")
            return OpenAiChatModel.builder()
                .httpClientBuilder(OkHttpClientBuilderAdapter())
                .apiKey(sarvamKey)
                .modelName("sarvam-105b")
                .baseUrl("https://api.sarvam.ai/v1")
                .customHeaders(mapOf("api-subscription-key" to sarvamKey))
                .temperature(temperature)
                .build()
        }

        if (openRouterKey.isEmpty()) {
            XLog.w(TAG, "Interaction model selected but API key is blank")
            return null
        }

        val isOpenRouter = forceOpenRouter || interactionModel.contains("/") || KVUtils.getLlmProvider().equals("OPENROUTER", ignoreCase = true)
        val resolvedModel = if (forceOpenRouter && !interactionModel.contains("/")) "google/gemini-3.5-flash-lite" else interactionModel
        val baseUrl = if (isOpenRouter) "https://openrouter.ai/api/v1" else "https://generativelanguage.googleapis.com/v1beta/openai/"

        XLog.d(TAG, "createInteractionChatModel: forceOpenRouter=$forceOpenRouter model=$resolvedModel via $baseUrl")
        return OpenAiChatModel.builder()
            .httpClientBuilder(OkHttpClientBuilderAdapter())
            .apiKey(openRouterKey)
            .modelName(resolvedModel)
            .baseUrl(baseUrl)
            .customHeaders(mapOf("HTTP-Referer" to "https://pokeclaw.agents.io", "X-Title" to "PokeClaw"))
            .temperature(temperature)
            .build()
    }

    /**
     * Single-shot call using the Interaction Model (for dialect turns, quick chat, or prompt rewriting).
     */
    fun singleShotInteraction(systemPrompt: String, userPrompt: String, temperature: Double = 0.2): String? {
        return try {
            val chatModel = createInteractionChatModel(temperature)
            if (chatModel != null) {
                val messages = listOf<ChatMessage>(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt)
                )
                val request = ChatRequest.builder().messages(messages).build()
                val response = chatModel.chat(request)
                response.aiMessage().text()
            } else {
                singleShotCloud(systemPrompt, userPrompt, temperature)
            }
        } catch (e: Exception) {
            XLog.w(TAG, "singleShotInteraction failed: ${e.message}")
            handleLlmError(e)
            singleShotCloud(systemPrompt, userPrompt, temperature)
        }
    }

    fun handleLlmError(e: Exception) {
        val msg = e.message.orEmpty()
        if (msg.contains("401") || msg.contains("402") || msg.contains("403") || msg.contains("Unauthorized") || msg.contains("Payment Required")) {
            val report = "API Key Expired or Quota Exceeded (HTTP 401/402/403). Please check Settings."
            XLog.e(TAG, report, e)
            VoiceManager.speakNative("API Key Expired or Quota Exceeded. Please update your key in Settings.")
        }
    }

    /**
     * Create a Cloud LlmClient using the resolved active config.
     */
    fun createCloudClient(temperature: Double = 0.7): LlmClient? {
        val config = ModelConfigRepository.snapshot()
        if (config.activeMode == ActiveModelMode.LOCAL) return null
        val cloud = config.activeCloud
        if (cloud.apiKey.isEmpty() || cloud.modelName.isEmpty()) {
            XLog.w(TAG, "createCloudClient: incomplete cloud config")
            return null
        }
        return LlmClientFactory.create(
            config.toAgentConfig(
                temperature = temperature,
                maxIterations = 25
            )
        )
    }

    /**
     * Single-shot LLM call — send one prompt, get one response.
     * Uses the user's selected Cloud or Local model.
     * For quick targeted questions (not a full agent loop).
     *
     * @return LLM response text, or null if failed
     */
    fun singleShot(prompt: String, temperature: Double = 0.3): String? {
        return if (ModelConfigRepository.snapshot().activeMode == ActiveModelMode.CLOUD) {
            singleShotCloud(prompt, temperature)
        } else {
            singleShotLocal(prompt, temperature)
        }
    }

    /**
     * Single-shot Cloud LLM call.
     */
    fun singleShotCloud(prompt: String, temperature: Double = 0.7): String? {
        return try {
            val chatModel = createCloudChatModel(temperature) ?: return null
            val messages = listOf<ChatMessage>(UserMessage.from(prompt))
            val request = ChatRequest.builder().messages(messages).build()
            val response = chatModel.chat(request)
            response.aiMessage().text()
        } catch (e: Exception) {
            XLog.w(TAG, "singleShotCloud failed: ${e.message}")
            null
        }
    }

    /**
     * Single-shot Cloud LLM call with system prompt.
     */
    fun singleShotCloud(systemPrompt: String, userPrompt: String, temperature: Double = 0.7): String? {
        return try {
            val chatModel = createCloudChatModel(temperature) ?: return null
            val messages = listOf<ChatMessage>(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)
            )
            val request = ChatRequest.builder().messages(messages).build()
            val response = chatModel.chat(request)
            response.aiMessage().text()
        } catch (e: Exception) {
            XLog.w(TAG, "singleShotCloud failed: ${e.message}")
            null
        }
    }

    /**
     * Single-shot Local LLM call using LiteRT-LM.
     */
    fun singleShotLocal(prompt: String, temperature: Double = 0.3): String? {
        return singleShotLocal(
            systemPrompt = DEFAULT_LOCAL_SYSTEM_PROMPT,
            prompt = prompt,
            temperature = temperature
        )
    }

    fun singleShotLocal(systemPrompt: String, prompt: String, temperature: Double = 0.3): String? {
        return try {
            val modelPath = ModelConfigRepository.snapshot().local.modelPath
            if (modelPath.isNullOrEmpty()) return null

            val context = ClawApplication.instance
            LocalModelRuntime.runSingleShot(
                context = context,
                modelPath = modelPath,
                systemPrompt = systemPrompt,
                prompt = prompt,
                temperature = temperature,
            ).text
        } catch (e: Exception) {
            XLog.w(TAG, "singleShotLocal failed: ${e.message}")
            null
        }
    }

    /**
     * Check if Cloud LLM is configured (has API key).
     */
    fun isCloudConfigured(): Boolean {
        return ModelConfigRepository.snapshot().defaultCloud.isConfigured
    }
}
