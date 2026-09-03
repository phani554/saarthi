// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.agent.knowledge.MemoryManager
import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * AI-driven prompt & search query optimizer + Dialect Interaction Manager.
 * Features an internal decision cache to prevent repetitive AI analysis across turns,
 * and explicitly injects Mem0 memory preferences into instructions.
 */
object PromptRewriter {

    private const val TAG = "PromptRewriter"

    /** In-memory decision cache to eliminate repetitive analysis on identical requests */
    private val decisionCache = ConcurrentHashMap<String, String>()

    enum class InteractionMode {
        PRE_TASK_CONFIRMATION,
        MID_TASK_CLARIFICATION
    }

    data class InteractionResult(
        val spokenResponse: String,
        val isConfirmed: Boolean,
        val cleanedEnglishTask: String,
        val isCancelled: Boolean = false
    )

    /**
     * Process an interaction turn (pre-task confirmation or mid-task clarification).
     * Automatically injects Mem0 memory.
     */
    fun processTurn(
        userSpeech: String,
        conversationHistory: String = "",
        mode: InteractionMode = InteractionMode.PRE_TASK_CONFIRMATION
    ): InteractionResult {
        val raw = userSpeech.trim()
        if (raw.isBlank()) {
            return InteractionResult(
                spokenResponse = "Mujhe aapki aawaz nahi aayi. Kripya dobara bolein.",
                isConfirmed = false,
                cleanedEnglishTask = "",
                isCancelled = false
            )
        }

        val memorySection = MemoryManager.getMemoryPromptSection()

        return try {
            val systemPrompt = """You are Saarthi, a friendly AI voice assistant.
$memorySection

Rules:
1. Strict Language Auto-Switching: Match the EXACT language/dialect of the user's LATEST speech turn!
2. Exit & Cancellation Intent: Check if user wants to cancel, exit, or declines adding anything else!
   - IF user declines/cancels: set isCancelled=true, isConfirmed=false, spokenResponse="Okay, no problem! Let me know if you need anything else.", cleanedEnglishTask="".
3. Pre-Task Confirmation & Intent:
   - Check if user is confirming: 'proceed', 'go ahead', 'yes', 'start', 'ha', 'sahi hai', 'sare', 'avunu', 'cheyyi', 'seri', 'ok'.
   - IF confirmed: set isConfirmed=true, isCancelled=false, spokenResponse="Starting task now.", cleanedEnglishTask="<Clean English Instruction for GLM 5.3>".
   - IF initial request or changes: set isConfirmed=false, isCancelled=false, respond in user's language asking for confirmation.
4. Output ONLY a valid JSON object with keys: "spokenResponse", "isConfirmed", "isCancelled", "cleanedEnglishTask"."""

            val prompt = """Mode: ${mode.name}
Conversation History:
$conversationHistory

Latest User Speech: "$raw"

Return JSON object:"""

            val resultStr = LlmSessionManager.singleShotInteraction(systemPrompt, prompt, 0.2)
                ?: LlmSessionManager.singleShotLocal(systemPrompt, prompt, 0.2)

            if (!resultStr.isNullOrBlank()) {
                val jsonStart = resultStr.indexOf('{')
                val jsonEnd = resultStr.lastIndexOf('}')
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    val jsonObj = JSONObject(resultStr.substring(jsonStart, jsonEnd + 1))
                    val spoken = jsonObj.optString("spokenResponse", "").trim()
                    val confirmed = jsonObj.optBoolean("isConfirmed", false)
                    val cancelled = jsonObj.optBoolean("isCancelled", false)
                    val cleanedTask = jsonObj.optString("cleanedEnglishTask", "").trim()

                    HybridMemoryRepository.recordTurnAsync(raw, spoken)

                    XLog.i(TAG, "Interaction turn: spoken='$spoken', confirmed=$confirmed, cancelled=$cancelled, cleanedTask='$cleanedTask'")
                    return InteractionResult(
                        spokenResponse = spoken.ifBlank { "Task start kar raha hoon." },
                        isConfirmed = confirmed,
                        cleanedEnglishTask = cleanedTask.ifBlank { if (cancelled) "" else optimize(raw) },
                        isCancelled = cancelled
                    )
                }
            }

            fallbackResult(raw)
        } catch (e: Exception) {
            XLog.w(TAG, "processTurn failed, using fallback", e)
            fallbackResult(raw)
        }
    }

    private fun fallbackResult(raw: String): InteractionResult {
        val lower = raw.lowercase()
        val isCancelled = lower.contains("no thanks") || lower.contains("nothing else") ||
                lower.contains("cancel") || lower.contains("stop") ||
                lower.contains("nahi") || lower.contains("nayi") || lower.contains("vaddu") ||
                lower.contains("exit") || lower.contains("kuch nahi")

        val isConfirmed = !isCancelled && (lower.contains("proceed") || lower.contains("go ahead") ||
                lower.contains("start") || lower.contains("yes") || lower.contains("ok") ||
                lower.contains("ha") || lower.contains("sahi") || lower.contains("theek") ||
                lower.contains("sare") || lower.contains("avunu") || lower.contains("cheyyi"))

        val optimized = if (isCancelled) "" else optimize(raw)
        val spoken = when {
            isCancelled -> "Okay, no problem! Let me know if you need anything else."
            isConfirmed -> "Task start kar raha hoon."
            else -> "Maine samjha: $optimized. 'Proceed' boleine start karne ke liye."
        }

        return InteractionResult(
            spokenResponse = spoken,
            isConfirmed = isConfirmed,
            cleanedEnglishTask = optimized,
            isCancelled = isCancelled
        )
    }

    /**
     * Rewrite user prompt for AI agent execution into a clean, canonical English instruction,
     * explicitly analyzing user memory preferences from Mem0 to enrich product and contact targets.
     * Uses internal decision caching to eliminate repetitive analysis.
     */
    fun optimize(userPrompt: String): String {
        val raw = userPrompt.trim()
        if (raw.isBlank()) return raw

        // Fast decision cache check (0ms)
        val cached = decisionCache[raw.lowercase()]
        if (cached != null) {
            XLog.i(TAG, "PromptRewriter Decision Cache HIT (0ms): '$raw' -> '$cached'")
            return cached
        }

        val (mem0Results, _) = runBlocking {
            HybridMemoryRepository.searchMemories(raw)
        }
        val memorySection = MemoryManager.getMemoryPromptSection() + if (mem0Results.isNotBlank()) "\n\n## Mem0 Relevant Memory Search\n$mem0Results" else ""

        return try {
            val systemPrompt = """You are an AI prompt & intent optimizer. Your job is to extract clear action intent, exact contact/group names from memory, and product constraints from user requests and translate them into a clean, unambiguous English instruction prompt for an Android task agent (GLM 5.3).
$memorySection"""

            val prompt = """Optimize and translate this request into a clear English task instruction for an Android app agent:
"$raw"

Rules:
1. Always output a clean English instruction prompt.
2. Explicitly analyze the User Memory and Mem0 search results above!
   - If user asks to order an item (e.g. "milk" or "bread") that has a preferred brand/variant in memory (e.g. "Amul Taaza 1L Milk"), explicitly include that exact brand/variant in the prompt so the agent searches the exact item!
   - If user mentions a contact nickname or group (e.g. "Mom" or "project group") that has a display name in memory (e.g. "Sarita (Mom)" or "Final Year Project"), explicitly use that display name!
3. Combine multi-clause messaging (e.g. "send hi to Mom for dinner and ask her what I should bring") into ONE clear instruction: "Send message to Mom: 'Hi, what should I bring for dinner?'".
4. Preserve size/quantity/volume constraints (e.g. "1L amul milk", "pepsi 250ml").
5. Output ONLY the optimized English action task. No preamble."""

            val result = LlmSessionManager.singleShotInteraction(systemPrompt, prompt, 0.2)
                ?: LlmSessionManager.singleShotLocal(systemPrompt, prompt, 0.2)

            val cleaned = result?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
            val finalTask = if (!cleaned.isNullOrBlank() && cleaned.length > 3) cleaned else raw
            decisionCache[raw.lowercase()] = finalTask
            XLog.i(TAG, "AI Prompt Rewritten & Cached: '$raw' -> '$finalTask'")
            finalTask
        } catch (e: Exception) {
            XLog.w(TAG, "PromptRewriter AI call failed, using original prompt", e)
            raw
        }
    }
}
