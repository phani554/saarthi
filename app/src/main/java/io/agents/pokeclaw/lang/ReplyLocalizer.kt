// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.lang

import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.utils.XLog

/**
 * Puts a user-facing line (the agent's final answer, a tool's result) into the reply language.
 * The agent itself keeps working in English; only what the user hears and reads is rewritten.
 * Text already in the right script is returned as is, without a model call. On any failure the
 * original text is returned. Blocking: never call on the main thread.
 */
object ReplyLocalizer {

    private const val TAG = "ReplyLocalizer"

    @JvmStatic
    fun localize(text: String, lang: ReplyLanguage = ReplyLanguageState.replyLanguage()): String {
        if (text.isBlank()) return text
        if (LanguageDetector.isWrittenIn(text, lang)) return text
        return try {
            val system = "You rewrite one short message from Saathi, a phone helper, for an elderly user. " +
                lang.promptInstruction() + " " +
                "Keep the meaning exactly. Keep names, app names, phone numbers, amounts, times and anything " +
                "inside quotes exactly as written: quoted text is a message the user dictated and is never translated. " +
                "Use short, simple, polite sentences. Output only the rewritten message."
            val out = LlmSessionManager.singleShotInteraction(system, text, 0.2)
                ?.trim()
                ?.removeSurrounding("\"")
                ?.trim()
            if (out.isNullOrBlank() || out.length > text.length * 4 + 200 || out.contains('{')) {
                text
            } else {
                XLog.i(TAG, "Localized reply to ${lang.code} (${text.length} -> ${out.length} chars)")
                out
            }
        } catch (e: Exception) {
            XLog.w(TAG, "localize failed, keeping original: ${e.message}")
            text
        }
    }
}
