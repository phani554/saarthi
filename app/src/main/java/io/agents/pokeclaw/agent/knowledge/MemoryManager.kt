// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.knowledge

import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.utils.XLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Manages user memory and preferences learned from user interactions.
 * Language-agnostic: uses AI (Gemini / Sarvam 105B) to extract structured facts
 * from Telugu, Hindi, Hinglish, Tamil, Bengali, or English inputs.
 * Persists learned memory in vault/user_preferences.md via KBManager.
 */
object MemoryManager {

    private const val TAG = "MemoryManager"
    private const val MEMORY_FILE_PATH = "user_preferences.md"

    private val PREFERENCE_PATTERNS = listOf(
        Pattern.compile("""(?i)\b(?:i\s+(?:prefer|like|love|hate|dislike|use|want|need)|my\s+(?:favorite|preferred|address|phone|email|number|mom|dad|wife|husband|home|office|friend|sister|brother|contact)|always\s+(?:order|buy|use|pick)|never\s+(?:order|buy|use)|i\s+am\s+(?:vegetarian|vegan|allergic|gluten-free))\b.*"""),
        Pattern.compile("""(?i)\b(?:remember\s+that|note\s+that|keep\s+in\s+mind\s+that)\s+(.+)"""),
        Pattern.compile("""(?i)\b(?:in\s+(?:the\s+)?(?:group|whatsapp|chat)|reply\s+(?:on\s+my\s+behalf|to|in)|tell\s+(?:them|group|everyone|mom|dad|my)|when\s+(?:anyone|someone)\s+asks|if\s+anyone\s+asks)\b.*"""),
        Pattern.compile("""(?i)\b[a-z0-9_\s]+\s+is\s+my\s+[a-z0-9_\s]+\b.*"""),
        Pattern.compile("""(?i)\b(?:order|buy|text|message|send\s+message\s+to)\s+[a-z0-9_\s]+\b.*""")
    )

    /**
     * Get the formatted user preferences section for system prompts.
     */
    fun getMemoryPromptSection(): String {
        val result = KBManager.read(MEMORY_FILE_PATH)
        val text = result.getOrNull()?.trim()
        if (text.isNullOrEmpty()) {
            return ""
        }
        return "\n\n## User Preferences & Memory\nThese are user preferences and personal facts learned from previous chats. Use them to personalize actions and answers:\n\n$text"
    }

    /**
     * Scan an incoming user prompt/message and extract preference statements to save.
     * Uses Gemini/Interaction AI to ensure language is not a barrier (Telugu, Hindi, English, etc.).
     */
    fun learnFromMessage(userText: String) {
        if (userText.isBlank()) return
        val raw = userText.trim()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

        // 1. Fast regex pattern match
        val lines = raw.split("\n", ".", ";")
        for (line in lines) {
            val cleanLine = line.trim()
            if (cleanLine.length < 5) continue

            for (pattern in PREFERENCE_PATTERNS) {
                val matcher = pattern.matcher(cleanLine)
                if (matcher.find()) {
                    savePreference("- [$timestamp] $cleanLine")
                    break
                }
            }
        }

        // 2. Multi-lingual AI memory extraction (translates facts from Telugu/Hindi/Tamil/etc. into canonical English statements)
        try {
            val systemPrompt = "You extract personal user facts, relationships, group names, and preferences from user messages regardless of language (Telugu, Hindi, Hinglish, Tamil, English, etc.)."
            val prompt = """Analyze this user message for personal facts or relationships:
"$raw"

Rules:
1. If the user states a personal preference, friend, family relation, address, contact mapping, or project group name (e.g. "major project group is Final Year Project", "my friend is Rahul", "naa friend Rahul", "nenu major project group Final Year Project ani pettanu"):
   - Extract a single, concise English fact statement.
2. If NO personal fact is in the message, output ONLY 'NONE'.
3. Output ONLY the clean fact statement or 'NONE'. No preamble."""

            val extracted = LlmSessionManager.singleShotInteraction(systemPrompt, prompt, 0.1)
                ?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")

            if (!extracted.isNullOrBlank() && !extracted.equals("NONE", ignoreCase = true) && extracted.length > 5) {
                savePreference("- [$timestamp] $extracted")
            }
        } catch (e: Exception) {
            XLog.w(TAG, "AI memory extraction failed: ${e.message}")
        }
    }

    /**
     * Explicitly record a personal fact/preference into persistent global memory.
     */
    fun recordFact(fact: String) {
        if (fact.isBlank()) return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        savePreference("- [$timestamp] $fact")
    }

    private fun savePreference(bulletPoint: String) {
        val existing = KBManager.read(MEMORY_FILE_PATH).getOrNull()
        if (existing.isNullOrBlank()) {
            val fm = mapOf(
                "type" to "user_memory",
                "created" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            )
            val initialContent = "# User Preferences & Memory\n\n$bulletPoint\n"
            KBManager.write(MEMORY_FILE_PATH, fm, initialContent)
            XLog.i(TAG, "Created user_preferences.md with: $bulletPoint")
        } else {
            if (!existing.contains(bulletPoint.substringAfter("] ").trim())) {
                KBManager.append(MEMORY_FILE_PATH, "$bulletPoint\n")
                XLog.i(TAG, "Appended preference: $bulletPoint")
            }
        }
    }
}
