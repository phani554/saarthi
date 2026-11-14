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
        // Only when the whole phrase is the role itself ("call Dr Sharma" / "Guard Ramesh" stay name lookups).
        if (isRoleOnly(clean)) io.agents.pokeclaw.care.CareRoles.fromWords(clean)?.let { role ->
            io.agents.pokeclaw.care.CareProfileStore.get().first(role)?.takeIf { it.hasPhone }?.let {
                XLog.i(TAG, "ContactAliasResolver care role: '$clean' -> $role")
                return it.digits
            }
        }

        // Local memory facts (< 1ms): "beta" / "my son" -> person:son, "project group" -> alias:project_group.
        // Only confirmed facts: a guessed (pending) name must never decide who gets called.
        fromMemoryFacts(clean)?.let {
            XLog.i(TAG, "ContactAliasResolver memory fact match for '$clean'")
            return it
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
                val aliasCore = clean.lowercase().replace(FILLER_WORDS, " ").replace(Regex("\\s+"), " ").trim()
                    .ifEmpty { clean.lowercase() }
                for (line in memories.split("\n")) {
                    if (!line.contains(aliasCore, ignoreCase = true)) continue
                    val resolved = extractAliasTarget(line, aliasCore) ?: continue
                    XLog.i(TAG, "ContactAliasResolver Mem0 match: '$clean' -> '$resolved'")
                    return resolved
                }
            }
            clean
        } catch (e: Exception) {
            XLog.w(TAG, "ContactAliasResolver Mem0 check failed: ${e.message}")
            clean
        }
    }

    /** Confirmed person:/alias: fact for [phrase], or null. */
    private fun fromMemoryFacts(phrase: String): String? = try {
        val core = phrase.lowercase().replace(FILLER_WORDS, " ").replace(Regex("\\s+"), " ").trim()
        if (core.isEmpty()) null else {
            val rel = FactExtractor.relationOf(core)
            val byRelation = rel?.let { MemoryFactStore.get("person:$it") }?.takeIf { !it.pending }?.value
            byRelation ?: MemoryFactStore.get("alias:" + core.replace(' ', '_'))?.takeIf { !it.pending }?.value
        }
    } catch (_: Exception) { null }

    private val FILLER_WORDS = Regex("""(?i)\b(my|our|the|ka|ki|ke|apna|apni|apne|hamara|hamari|hamare|mera|meri|mere)\b""")
    private val ROLE_ONLY = Regex(
        """^(watchman|watchmen|chowkidar|security guard|guard|gatekeeper|darwan|family doctor|doctor|gharelu doctor|dr\.?|chemist|pharmacy|pharmacist|medical store|medical shop|dawai wala|dawa dukan|medicine shop|emergency contact|loved one|caretaker)$"""
    )

    /** "watchman", "my family doctor", "our doctor" -> true; "Dr Sharma", "Guard Ramesh" -> false. */
    private fun isRoleOnly(phrase: String): Boolean {
        val core = phrase.lowercase().replace(FILLER_WORDS, " ").replace(Regex("\\s+"), " ").trim()
        return core.isNotEmpty() && ROLE_ONLY.matches(core)
    }

    /**
     * Pull only the contact name out of a memory line such as
     * "- [2026-11-14 10:30] My sister is Priya", "Priya is my sister" or "bhai = Rahul".
     * Returns null when the line doesn't state a mapping for [aliasCore].
     */
    private fun extractAliasTarget(line: String, aliasCore: String): String? {
        val body = line.trim()
            .replace(Regex("""^[-*•]\s*"""), "")
            .replace(Regex("""^\[[^\]]*]\s*"""), "")
            .trim().trimEnd('.', '!', ',', ';')
        fun roleMatches(role: String): Boolean {
            val r = role.lowercase().replace(FILLER_WORDS, " ").replace(Regex("\\s+"), " ").trim()
            return r.isNotEmpty() && (r == aliasCore || r.contains(aliasCore) || aliasCore.contains(r))
        }
        fun validName(n: String): String? {
            val name = n.trim().trim('"', '\'').trimEnd('.', '!', ',', ';').trim()
            if (name.isEmpty() || name.length >= 50) return null
            if (Regex("""(?i)\b(is|my|number)\b""").containsMatchIn(name)) return null
            return name
        }
        // "Priya is my sister"
        Regex("""(?i)^(.+?)\s+is\s+my\s+(.+)$""").find(body)?.let { m ->
            if (roleMatches(m.groupValues[2])) validName(m.groupValues[1])?.let { return it }
        }
        // "My sister is Priya" / "my sister's name is Priya"
        Regex("""(?i)^my\s+(.+?)(?:'s\s+name)?\s+is\s+(?:called\s+|named\s+)?(.+)$""").find(body)?.let { m ->
            if (roleMatches(m.groupValues[1])) validName(m.groupValues[2])?.let { return it }
        }
        // "bhai = Rahul" / "alias: bhai -> Rahul"
        Regex("""^(.+?)\s*(?:=|->)\s*(.+)$""").find(body)?.let { m ->
            val left = m.groupValues[1].substringAfterLast(':')
            if (roleMatches(left)) validName(m.groupValues[2])?.let { return it }
        }
        return null
    }

    private fun isPlainNameOrNumber(name: String): Boolean {
        val lower = name.lowercase()
        val isAliasTrigger = lower.contains("my ") || lower.contains("sister") || lower.contains("bhai") ||
                lower.contains("caretaker") || lower.contains("wife") || lower.contains("husband") ||
                lower.contains("brother") || lower.contains("friend") || lower.contains("group") ||
                lower.split(Regex("\\s+")).any { FactExtractor.relationOf(it) != null }
        return !isAliasTrigger
    }
}
