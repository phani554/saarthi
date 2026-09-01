// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.knowledge

import io.agents.pokeclaw.utils.XLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Manages user memory and preferences learned from user interactions.
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
     */
    fun learnFromMessage(userText: String) {
        if (userText.isBlank()) return

        val lines = userText.split("\n", ".", ";")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

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
