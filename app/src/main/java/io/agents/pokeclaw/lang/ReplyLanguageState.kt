// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.lang

import android.content.Context
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

/**
 * The language Saathi replies in right now.
 *
 * Auto (default): follows the language of the user's latest spoken or typed turn, switching per
 * turn ([LanguageDecider]: short "haan"/"ok" turns and low-confidence speech keep the previous one).
 * Settings › Reply language can fix English / Hindi / Marathi / Telugu: then speech-to-text is
 * asked for that language and every reply uses it.
 */
object ReplyLanguageState {

    private const val TAG = "ReplyLanguage"
    const val AUTO = "auto"

    @Volatile
    var current: ReplyLanguage = ReplyLanguage.HI

    /** The fixed choice from Settings, or null for Auto. */
    @JvmStatic
    fun fixed(): ReplyLanguage? = try {
        val v = KVUtils.getReplyLanguage()
        if (v.equals(AUTO, ignoreCase = true)) null else ReplyLanguage.fromCode(v)
    } catch (_: Throwable) { null }

    /** The language to reply in now. */
    @JvmStatic
    fun replyLanguage(): ReplyLanguage = fixed() ?: current

    /** Sarvam STT language_code: the fixed language, or "unknown" (auto-detect). */
    @JvmStatic
    fun sttLanguageCode(): String = fixed()?.code ?: "unknown"

    /** A Sarvam STT result. [sttCode] may be any BCP-47 code or blank. */
    @JvmStatic
    fun onSpeech(sttCode: String?, probability: Double?, transcript: String) {
        if (fixed() != null) return
        val before = current
        current = LanguageDecider.decide(before, sttCode?.takeIf { it.isNotBlank() } ?: "unknown", probability, transcript)
        if (current != before) XLog.i(TAG, "Reply language $before -> $current (stt=$sttCode p=$probability)")
    }

    /** Typed text, or a transcript without a language code (Gemini / Android recogniser). */
    @JvmStatic
    fun onText(text: String) {
        if (fixed() != null) return
        val before = current
        current = LanguageDecider.decide(before, null, null, text)
        if (current != before) XLog.i(TAG, "Reply language $before -> $current (text)")
    }

    /** Voice for [text]: judged from its own script (see [LanguageDetector.ttsLanguageFor]). */
    @JvmStatic
    fun ttsLanguageFor(text: String): ReplyLanguage = LanguageDetector.ttsLanguageFor(text, replyLanguage())

    /** Saves the Settings choice ("auto" or a code) and applies it at once. */
    @JvmStatic
    fun setChoice(value: String) {
        val v = if (ReplyLanguage.fromCode(value) == null) AUTO else ReplyLanguage.fromCode(value)!!.code
        KVUtils.setReplyLanguage(v)
        ReplyLanguage.fromCode(v)?.let { current = it }
        try { io.agents.pokeclaw.agent.knowledge.MemoryManager.invalidateCache() } catch (_: Throwable) {}
    }

    /** Label for the Settings row. */
    @JvmStatic
    fun choiceLabel(): String = when (val f = fixed()) {
        null -> "Auto"
        ReplyLanguage.EN -> "English"
        else -> "${f.nativeName} (${f.englishName})"
    }

    /**
     * Simple chooser: Auto / English / Hindi / Marathi / Telugu. [onChosen] runs after saving.
     * Call on the main thread.
     */
    @JvmStatic
    fun showChooser(context: Context, onChosen: () -> Unit = {}) {
        val labels = arrayOf(
            "Auto (reply in the language I speak)",
            "English",
            "हिंदी (Hindi)",
            "मराठी (Marathi)",
            "తెలుగు (Telugu)"
        )
        val values = arrayOf(AUTO, "en-IN", "hi-IN", "mr-IN", "te-IN")
        val selected = when (fixed()) {
            null -> 0
            ReplyLanguage.EN -> 1
            ReplyLanguage.HI -> 2
            ReplyLanguage.MR -> 3
            ReplyLanguage.TE -> 4
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("Reply language")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                setChoice(values[which])
                dialog.dismiss()
                onChosen()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
