// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.utils.XLog

/**
 * AI-driven prompt & search query optimizer.
 * Uses a fast single-shot AI call to transform colloquial user input into clear,
 * search-engine friendly keywords and structured action prompts.
 */
object PromptRewriter {

    private const val TAG = "PromptRewriter"

    /**
     * Rewrite user prompt for AI agent execution using AI.
     */
    fun optimize(userPrompt: String): String {
        val raw = userPrompt.trim()
        if (raw.isBlank() || raw.length < 5) return raw

        // Only run AI prompt rewriting for shopping/search/message tasks
        val lower = raw.lowercase()
        val needsRewriting = lower.contains("order") || lower.contains("buy") ||
                lower.contains("search") || lower.contains("find") ||
                lower.contains("blinkit") || lower.contains("zepto") ||
                lower.contains("instamart") || lower.contains("amazon")

        if (!needsRewriting) return raw

        return try {
            val systemPrompt = "You are an AI prompt & intent optimizer. Your job is to extract clear action intent and exact product/size constraints from user requests."
            val prompt = """Optimize this request for an Android app agent:
"$raw"

Rules:
1. Preserve specific size/quantity/volume constraints requested by the user (e.g. "small pepsi bottle" -> "Pepsi small bottle (250ml/330ml)", "1L amul milk" -> "Amul Milk 1L").
2. For e-commerce search, guide the agent to search core brand keywords first ("Pepsi") and then select the requested size/variant ("small bottle").
3. Preserve app name, target contacts, and action intent.
4. If the request contains MULTIPLE items or tasks (e.g. 4 products to add to cart and a WhatsApp message to send), list ALL items and actions explicitly so the agent executes every single item in order without stopping!
5. Output ONLY the optimized action task. No preamble."""

            val result = LlmSessionManager.singleShotCloud(systemPrompt, prompt, 0.2)
                ?: LlmSessionManager.singleShotLocal(systemPrompt, prompt, 0.2)

            val cleaned = result?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
            if (!cleaned.isNullOrBlank() && cleaned.length > 3) {
                XLog.i(TAG, "AI Prompt Rewritten: '$raw' -> '$cleaned'")
                cleaned
            } else {
                raw
            }
        } catch (e: Exception) {
            XLog.w(TAG, "PromptRewriter AI call failed, using original prompt", e)
            raw
        }
    }
}
