// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * AI-driven prompt & search query optimizer + Dialect Interaction Manager.
 *
 * Uses Sarvam AI (sarvam-105b) or Gemini (gemini-3.5-flash-lite / 3.8-flash) to:
 * 1. Interact in the user's spoken dialect matching turn-by-turn (Hinglish / Hindi / Telugu / Tamil / English).
 * 2. Resolve user memory (friends, contacts, major project group names, personal preferences).
 * 3. Detect confirmation intent ("proceed", "go ahead", "sahi hai", "sare", "avunu") and cancellation intent ("nothing else", "cancel", "no").
 * 4. Translate confirmed requests into clean, canonical English prompts for GLM 5.3 task agent execution.
 */
object PromptRewriter {

    private const val TAG = "PromptRewriter"

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
     * Automatically injects user memory (contacts, friends, project groups, preferences).
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

        // Search Mem0 Cloud memory with fallback to local vault
        val (memorySection, _) = runBlocking {
            HybridMemoryRepository.searchMemories(raw)
        }

        return try {
            val systemPrompt = """You are Saarthi, a friendly AI voice assistant.
$memorySection

Rules:
1. Strict Language Auto-Switching: Match the EXACT language/dialect of the user's LATEST speech turn!
   - If user spoke English -> respond in natural English.
   - If user spoke Telugu -> respond in natural Telugu.
   - If user spoke Hinglish / Code-mix -> respond in natural Hinglish / Code-mix.
   - NEVER default to Hindi when the user spoke in English or Telugu!
2. Exit & Cancellation Intent: Check if user wants to cancel, exit, or declines adding anything else!
   - Phrases: "no", "cancel", "stop", "nothing else", "no thanks", "nahi", "nayi", "vaddu", "voddhu", "exit", "bhai bas", "kuch nahi".
   - IF user declines/cancels: set isCancelled=true, isConfirmed=false, spokenResponse="Okay, no problem! Let me know if you need anything else.", cleanedEnglishTask="".
3. Pre-Task Confirmation & Intent:
   - Check if user is confirming: 'proceed', 'go ahead', 'yes', 'start', 'ha', 'sahi hai', 'sare', 'avunu', 'cheyyi', 'seri', 'ok'.
   - IF confirmed: set isConfirmed=true, isCancelled=false, spokenResponse="Starting task now.", cleanedEnglishTask="<Clean English Instruction for GLM 5.3>".
   - IF initial request or changes: set isConfirmed=false, isCancelled=false, respond in user's language asking for confirmation.
4. Output ONLY a valid JSON object with keys:
   - "spokenResponse": (string)
   - "isConfirmed": (boolean)
   - "isCancelled": (boolean)
   - "cleanedEnglishTask": (string)"""

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

                    // Record turn asynchronously into Mem0
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
     * Rewrite user prompt for AI agent execution using AI with memory context.
     */
    fun optimize(userPrompt: String): String {
        val raw = userPrompt.trim()
        if (raw.isBlank()) return raw

        val (memorySection, _) = runBlocking {
            HybridMemoryRepository.searchMemories(raw)
        }

        return try {
            val systemPrompt = """You are an AI prompt & intent optimizer. Your job is to extract clear action intent, exact contact/group names from memory, and product constraints from user requests and translate them into a clean, unambiguous English instruction prompt for an Android task agent (GLM 5.3).
$memorySection"""

            val prompt = """Optimize and translate this request into a clear English task instruction for an Android app agent:
"$raw"

Rules:
1. Always output a clean English instruction prompt.
2. Resolve any contact nicknames, group aliases, or personal references using the user memory provided above! (e.g., "major project group" -> "Final Year Project", "my sister" -> "Priya").
3. Preserve specific size/quantity/volume constraints requested by the user (e.g. "small pepsi bottle" -> "Pepsi small bottle (250ml/330ml)", "1L amul milk" -> "Amul Milk 1L").
4. For e-commerce search, guide the agent to search core brand keywords first ("Pepsi") and then select the requested size/variant ("small bottle").
5. Preserve app name, target contacts, and action intent.
6. If the request contains MULTIPLE items or tasks (e.g. 4 products to add to cart and a WhatsApp message to send), list ALL items and actions explicitly so the agent executes every single item in order without stopping!
7. Output ONLY the optimized English action task. No preamble."""

            val result = LlmSessionManager.singleShotInteraction(systemPrompt, prompt, 0.2)
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
