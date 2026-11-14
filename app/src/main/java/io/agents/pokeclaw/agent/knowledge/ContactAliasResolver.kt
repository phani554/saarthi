// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.knowledge

import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.runBlocking

/**
 * Resolves nicknames, abbreviations, and semantic shortcuts (e.g. "major project group", "bhai")
 * to the actual contact or WhatsApp group name (e.g. "Final Year Project", "Rahul")
 * using Mem0 in-memory cache and fast deterministic rules.
 */
object ContactAliasResolver {

    private const val TAG = "ContactAliasResolver"

    /**
     * Resolve a raw contact or group reference to its actual display name.
     * Plain names/numbers return immediately in 0ms.
     * Explicit aliases query Mem0 memory in-memory cache.
     */
    fun resolve(rawName: String): String {
        val clean = rawName.trim()
        if (clean.isBlank()) return clean

        // Care roles ("watchman", "chemist", "family doctor", "my emergency contact") -> saved number.
        // Only when the phrase is basically the role itself ("call Dr Sharma" must stay a name lookup).
        if (clean.split(Regex("\\s+")).size <= 3) io.agents.pokeclaw.care.CareRoles.fromWords(clean)?.let { role ->
            io.agents.pokeclaw.care.CareProfileStore.get().first(role)?.takeIf { it.hasPhone }?.let {
                XLog.i(TAG, "ContactAliasResolver care role: '$clean' -> $role")
                return it.digits
            }
        }

        // Fast path (< 1ms): standard contact names & phone numbers return directly
        if (isPlainNameOrNumber(clean)) {
            XLog.d(TAG, "ContactAliasResolver fast path: '$clean'")
            return clean
        }

        // Fast Mem0 memory query for alias references
        return try {
            val (memories, _) = runBlocking { HybridMemoryRepository.searchMemories("alias $clean") }
            if (memories.isNotBlank()) {
                val lines = memories.split("\n")
                val matched = lines.find { it.contains(clean, ignoreCase = true) }
                if (matched != null) {
                    val resolved = matched.substringAfter(":").substringAfter("=").trim()
                    if (resolved.isNotBlank() && resolved.length < 50) {
                        XLog.i(TAG, "ContactAliasResolver Mem0 match: '$clean' -> '$resolved'")
                        return resolved
                    }
                }
            }
            clean
        } catch (e: Exception) {
            XLog.w(TAG, "ContactAliasResolver Mem0 check failed: ${e.message}")
            clean
        }
    }

    private fun isPlainNameOrNumber(name: String): Boolean {
        val lower = name.lowercase()
        val isAliasTrigger = lower.contains("my ") || lower.contains("sister") || lower.contains("bhai") ||
                lower.contains("caretaker") || lower.contains("wife") || lower.contains("husband") ||
                lower.contains("brother") || lower.contains("friend") || lower.contains("group")
        return !isAliasTrigger
    }
}
