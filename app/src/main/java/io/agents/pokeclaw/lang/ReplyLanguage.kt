// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.lang

/**
 * The four languages Saathi replies in. Pure Kotlin (no Android), so it is unit-tested on the JVM.
 *
 * Script rule (owner, 2026-11-14): English is written in Latin, Hindi and Marathi in Devanagari,
 * Telugu in Telugu script. Romanised Hindi ("Hinglish") speakers get Hindi replies in Devanagari,
 * because Sarvam's hi-IN voice reads Devanagari much better than romanised Hindi.
 */
enum class ReplyLanguage(val code: String, val englishName: String, val script: String, val nativeName: String) {
    EN("en-IN", "English", "Latin", "English"),
    HI("hi-IN", "Hindi", "Devanagari", "हिंदी"),
    MR("mr-IN", "Marathi", "Devanagari", "मराठी"),
    TE("te-IN", "Telugu", "Telugu", "తెలుగు");

    /** The line given to an LLM that writes text the user hears or reads. */
    fun promptInstruction(): String = "Reply in $englishName in $script script."

    companion object {
        /** Only en/hi/mr/te map; anything else (ta-IN, bn-IN, "unknown", null) is null. */
        @JvmStatic
        fun fromCode(code: String?): ReplyLanguage? {
            val c = code?.trim()?.lowercase().orEmpty()
            return when {
                c.startsWith("en") -> EN
                c.startsWith("hi") -> HI
                c.startsWith("mr") -> MR
                c.startsWith("te") -> TE
                else -> null
            }
        }
    }
}
