// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

/**
 * Cloud LLM provider and model definitions.
 * Used by LlmConfigActivity to render the provider tabs + model cards.
 */

data class CloudModel(
    val id: String,
    val displayName: String,
    val inputPricePerM: Double,
    val outputPricePerM: Double,
    val tier: ModelTier,
    val contextSize: Int,
    val recommended: Boolean = false
)

enum class ModelTier(val stars: String, val label: String) {
    LITE("\u2606", "Lite"),       // ☆
    FAST("\u2605", "Fast"),       // ★
    SMART("\u2605\u2605", "Smart"),     // ★★
    PRO("\u2605\u2605\u2605", "Pro")    // ★★★
}

enum class CloudProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val models: List<CloudModel>,
    val showBaseUrl: Boolean = false
) {
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        models = listOf(
            CloudModel("gpt-4o-mini", "GPT-4o Mini", 0.15, 0.60, ModelTier.FAST, 128_000, recommended = true),
            CloudModel("gpt-4o", "GPT-4o", 2.50, 10.00, ModelTier.SMART, 128_000),
            CloudModel("gpt-4.1", "GPT-4.1", 2.00, 8.00, ModelTier.PRO, 1_000_000),
            CloudModel("gpt-4.1-mini", "GPT-4.1 Mini", 0.40, 1.60, ModelTier.FAST, 1_000_000),
            CloudModel("gpt-4.1-nano", "GPT-4.1 Nano", 0.10, 0.40, ModelTier.LITE, 1_000_000),
        )
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        models = listOf(
            // GLM Series
            CloudModel("z-ai/glm-5.3-flash", "GLM 5.3 Flash", 0.06, 0.06, ModelTier.FAST, 128_000, recommended = true),
            CloudModel("zhipu/glm-4-flash", "GLM 4 Flash", 0.06, 0.06, ModelTier.FAST, 128_000),

            // Qwen Series
            CloudModel("qwen/qwen3.8-flash", "Qwen 3.8 Flash", 0.05, 0.15, ModelTier.FAST, 128_000),
            CloudModel("qwen/qwen3.8-max", "Qwen 3.8 Max", 0.40, 1.20, ModelTier.SMART, 128_000),
            CloudModel("qwen/qwen-2.5-72b-instruct", "Qwen 2.5 72B", 0.35, 0.40, ModelTier.SMART, 128_000),
            CloudModel("qwen/qwen-2.5-coder-32b-instruct", "Qwen 2.5 Coder 32B", 0.15, 0.20, ModelTier.FAST, 128_000),

            // Gemini Series
            CloudModel("google/gemini-3.1-flash-live-preview", "Gemini 3.1 Flash Live", 0.10, 0.40, ModelTier.FAST, 1_000_000, recommended = true),
            CloudModel("google/gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", 0.04, 0.15, ModelTier.LITE, 1_000_000),
            CloudModel("google/gemini-2.5-flash", "Gemini 2.5 Flash", 0.075, 0.30, ModelTier.FAST, 1_000_000),

            // Flagship Reasoning & UI Agents
            CloudModel("deepseek/deepseek-chat", "DeepSeek V3", 0.14, 0.28, ModelTier.SMART, 64_000),
            CloudModel("deepseek/deepseek-r1", "DeepSeek R1", 0.55, 2.19, ModelTier.PRO, 64_000),
            CloudModel("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", 3.00, 15.00, ModelTier.PRO, 200_000),
            CloudModel("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B", 0.12, 0.30, ModelTier.SMART, 128_000),
        ),
        showBaseUrl = true
    ),
    ANTHROPIC(
        displayName = "Anthropic",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        models = listOf(
            CloudModel("claude-sonnet-4-6", "Claude Sonnet 4.6", 3.00, 15.00, ModelTier.PRO, 200_000),
            CloudModel("claude-haiku-4-5", "Claude Haiku 4.5", 0.80, 4.00, ModelTier.FAST, 200_000, recommended = true),
        )
    ),
    GOOGLE(
        displayName = "Google",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        models = listOf(
            CloudModel("gemini-3.1-flash-live-preview", "Gemini 3.1 Flash Live", 0.10, 0.40, ModelTier.FAST, 1_000_000, recommended = true),
            CloudModel("gemini-2.5-flash", "Gemini 2.5 Flash", 0.15, 0.60, ModelTier.FAST, 1_000_000),
            CloudModel("gemini-2.5-pro", "Gemini 2.5 Pro", 1.25, 10.00, ModelTier.PRO, 1_000_000),
        )
    ),
    CUSTOM(
        displayName = "Custom",
        defaultBaseUrl = "",
        models = emptyList(),
        showBaseUrl = true
    );

    companion object {
        /**
         * Find provider by name (case-insensitive).
         * Returns OPENAI as default.
         */
        fun fromName(name: String): CloudProvider {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: OPENAI
        }

        /**
         * Find the provider that contains a given model ID.
         */
        fun findProviderForModel(modelId: String): CloudProvider? {
            return entries.find { provider ->
                provider.models.any { it.id == modelId }
            }
        }
    }
}
