// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.knowledge

import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Manages user memory and preferences learned from user interactions via Mem0.
 * In-memory caching ensures zero disk I/O bottleneck during prompt assembly.
 */
object MemoryManager {

    private const val TAG = "MemoryManager"
    private const val MEMORY_FILE_PATH = "user_preferences.md"

    @Volatile
    private var cachedMemoryPrompt: String? = null
    @Volatile
    private var lastCacheTime: Long = 0L
    private const val CACHE_TTL_MS = 60_000L // 1 minute in-memory cache

    private val PREFERENCE_PATTERNS = listOf(
        Pattern.compile("""(?i)\b(?:i\s+(?:prefer|like|love|hate|dislike)|my\s+(?:favorite|preferred|address|phone|email|number|mom|dad|wife|husband|home|office|friend|sister|brother|contact)|always\s+(?:order|buy|use|pick)|never\s+(?:order|buy|use)|i\s+am\s+(?:vegetarian|vegan|allergic|gluten-free))\b.*"""),
        Pattern.compile("""(?i)\b(?:remember\s+that|note\s+that|keep\s+in\s+mind\s+that)\s+(.+)"""),
        Pattern.compile("""(?i)\b[a-z0-9_\s]+\s+is\s+my\s+[a-z0-9_\s]+\b.*""")
        // Removed: "order/buy/text/message ..." and "tell mom/in the group ..." patterns saved
        // almost every task command as a "preference", flooding the prompt with noise.
    )

    private val extractionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Get the formatted user preferences section for system prompts.
     * Uses in-memory caching to eliminate blocking disk I/O overhead.
     */
    fun getMemoryPromptSection(): String {
        val now = System.currentTimeMillis()
        val currentCache = cachedMemoryPrompt
        if (currentCache != null && (now - lastCacheTime) < CACHE_TTL_MS) {
            return currentCache
        }

        // Local vault only. Previously this called HybridMemoryRepository.searchMemories(), which
        // (with no Mem0 key) called back into this function: infinite recursion / StackOverflowError.
        // Cloud Mem0 results are added separately by callers that have a real query.
        val text = localMemoryText()
        val care = try { io.agents.pokeclaw.care.CareProfileStore.get().promptSection() } catch (_: Exception) { "" }

        val formatted = (if (text.isBlank()) "" else "\n\n## User Preferences & Memory\nThese are user preferences and personal facts learned from previous chats. Use them to personalize actions and answers:\n\n$text") + care
        cachedMemoryPrompt = formatted
        lastCacheTime = now
        return formatted
    }

    fun invalidateCache() { cachedMemoryPrompt = null }

    /** Raw local memory file (no network, no recursion). Used by HybridMemoryRepository fallback. */
    fun localMemoryText(): String =
        try { KBManager.read(MEMORY_FILE_PATH).getOrNull()?.trim().orEmpty() } catch (_: Exception) { "" }

    /**
     * Scan an incoming user prompt/message and extract preference statements to save.
     */
    fun learnFromMessage(userText: String) {
        if (userText.isBlank()) return
        val raw = userText.trim()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

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

        // Fast, offline: "watchman ka number 98..." -> care profile right away.
        try { io.agents.pokeclaw.care.CareMemoryLearner.learnFast(raw) } catch (e: Exception) { XLog.w(TAG, "care fast-learn failed: ${e.message}") }

        // AI extraction runs off the caller's thread: it used to add a full LLM round-trip
        // before every task started.
        extractionExecutor.submit { extractWithAi(raw, timestamp) }
    }

    private fun extractWithAi(raw: String, timestamp: String) {
        try {
            val systemPrompt = "You extract personal user facts, relationships, group names, and preferences from user messages regardless of language."
            val prompt = """Analyze this user message for personal facts or relationships:
"$raw"

The message may be Hindi, Telugu, Marathi or mixed. "fact" = a personal preference, family relation,
address, contact mapping or group name as one concise English sentence, or NONE.
${io.agents.pokeclaw.care.CareMemoryLearner.AI_SCHEMA}"""

            val rawJson = LlmSessionManager.singleShotInteraction(systemPrompt, prompt, 0.1).orEmpty()
            // Care contacts (watchman, doctor, chemist, loved one), conditions and medicines go into the
            // structured care profile; the plain fact goes to the memory vault as before.
            val extracted = io.agents.pokeclaw.care.CareMemoryLearner.applyAiJson(rawJson)

            if (!extracted.isNullOrBlank() && !extracted.equals("NONE", ignoreCase = true) && extracted.length > 5) {
                savePreference("- [$timestamp] $extracted")
            }
        } catch (e: Exception) {
            XLog.w(TAG, "AI memory extraction failed: ${e.message}")
        }
    }

    /**
     * Explicitly record a personal fact/preference into persistent global memory via Mem0.
     */
    fun recordFact(fact: String) {
        if (fact.isBlank()) return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        savePreference("- [$timestamp] $fact")
    }

    private fun savePreference(bulletPoint: String) {
        cachedMemoryPrompt = null // invalidate cache
        HybridMemoryRepository.recordTurnAsync("user_preference_fact", bulletPoint)
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
