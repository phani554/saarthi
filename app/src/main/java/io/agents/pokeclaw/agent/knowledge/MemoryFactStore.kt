// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.knowledge

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.util.UUID

/**
 * Local store of [MemoryFact]s: one JSON list in MMKV (same store as the care profile).
 *
 * - Same key -> newer value replaces older, the older value goes into [MemoryFact.history].
 * - Same key + same value -> ignored (a duplicate).
 * - A small journal of learned changes lets "forget that" undo the last one.
 * - The old plain `user_preferences.md` is migrated once and copied to `user_preferences_backup_v1.md`.
 */
object MemoryFactStore {
    private const val TAG = "MemoryFactStore"
    private const val KEY_FACTS = "KEY_MEMORY_FACTS_V1"
    private const val KEY_JOURNAL = "KEY_MEMORY_FACTS_JOURNAL_V1"
    private const val KEY_MIGRATED = "KEY_MEMORY_FACTS_MIGRATED_V1"
    private const val LEGACY_FILE = "user_preferences.md"
    private const val BACKUP_FILE = "user_preferences_backup_v1.md"
    private const val MAX_FACTS = 300
    private const val MAX_JOURNAL = 20
    private const val MAX_HISTORY = 3

    private val gson = Gson()
    private val listType = object : TypeToken<List<MemoryFact>>() {}.type
    private val journalType = object : TypeToken<List<JournalEntry>>() {}.type

    /** One learned change that "forget that" can undo. kind = "fact" or "care". */
    data class JournalEntry(
        val kind: String = "fact",
        val factId: String = "",
        /** Value before this change; blank = the fact was new (undo deletes it). */
        val previousValue: String = "",
        val careRole: String = "",
        val carePhone: String = "",
        val at: Long = 0L,
        /** Entries learned from the same message share a batch, so "forget that" undoes them together. */
        val batch: Long = 0L,
    )

    @Volatile private var cache: List<MemoryFact>? = null
    @Volatile private var journal: List<JournalEntry>? = null

    /** Bumped on every change; the UI and prompt cache can compare it. */
    @Volatile var version: Long = 0L
        private set

    // ── Read ────────────────────────────────────────────────────────────────

    @Synchronized
    fun all(): List<MemoryFact> {
        cache?.let { return it }
        migrateLegacyOnce()
        val loaded = try {
            KVUtils.getString(KEY_FACTS, "").takeIf { it.isNotBlank() }
                ?.let { gson.fromJson<List<MemoryFact>>(it, listType) }
        } catch (e: Exception) {
            XLog.w(TAG, "Corrupt memory facts JSON, starting empty: ${e.message}")
            null
        } ?: emptyList()
        // Gson can leave Kotlin non-null fields as null when JSON is old or partial.
        @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
        val safe = loaded.filterNotNull()
            .filter { it.key != null && it.value != null && it.key.isNotBlank() && it.value.isNotBlank() }
            .map { f ->
                f.copy(
                    id = (f.id ?: "").ifBlank { newId() },
                    source = f.source ?: FactSource.SAID,
                    history = f.history ?: emptyList(),
                    detail = f.detail ?: "",
                )
            }
        cache = safe
        return safe
    }

    fun get(key: String): MemoryFact? = all().firstOrNull { it.key.equals(key, ignoreCase = true) }

    fun byId(id: String): MemoryFact? = all().firstOrNull { it.id == id }

    // ── Write ───────────────────────────────────────────────────────────────

    /**
     * Add or update a fact. Returns the stored fact, or null when it was a duplicate, empty or a secret.
     * [undoable] = true for things learned from what the user said or did (undoable by "forget that").
     */
    @Synchronized
    fun upsert(
        key: String,
        value: String,
        source: String,
        confidence: Float,
        pending: Boolean,
        undoable: Boolean = true,
        batch: Long = 0L,
    ): MemoryFact? {
        val k = normaliseKey(key)
        val v = value.trim().take(200)
        if (k.isBlank() || v.isBlank() || FactExtractor.isSensitive("$k $v")) return null
        val now = System.currentTimeMillis()
        val facts = all()
        val old = facts.firstOrNull { it.key.equals(k, ignoreCase = true) }
        if (old != null && old.value.trim().equals(v, ignoreCase = true)) {
            // Duplicate. A plain statement from the user confirms a guessed fact, nothing else changes.
            if (old.pending && !pending && source == FactSource.SAID) {
                val confirmed = old.copy(pending = false, source = FactSource.SAID, confidence = maxOf(old.confidence, confidence), updatedAt = now)
                save(facts.map { if (it.id == old.id) confirmed else it })
                return confirmed
            }
            return null
        }
        // A guess never overwrites something the user said or confirmed; it waits as its own pending note.
        if (old != null && !old.pending && pending && old.source == FactSource.SAID) {
            XLog.i(TAG, "Kept confirmed '$k'; a guessed new value was not applied")
            return null
        }
        val fact = if (old == null) {
            MemoryFact(newId(), k, v, source, confidence, now, now, pending)
        } else {
            old.copy(
                value = v, source = source, confidence = confidence, updatedAt = now, pending = pending,
                history = (listOf(old.value) + old.history).distinct().take(MAX_HISTORY),
            )
        }
        val next = if (old == null) trim(facts + fact) else facts.map { if (it.id == old.id) fact else it }
        save(next)
        if (undoable) addJournal(JournalEntry("fact", fact.id, old?.value.orEmpty(), at = now, batch = batch))
        return fact
    }

    /** Counter facts (contacts called, apps used): count + 1, newest detail. Not journaled. */
    @Synchronized
    fun bump(key: String, value: String, detail: String = "") {
        val k = normaliseKey(key)
        if (k.isBlank() || value.isBlank() || FactExtractor.isSensitive("$k $value")) return
        val now = System.currentTimeMillis()
        val facts = all()
        val old = facts.firstOrNull { it.key.equals(k, ignoreCase = true) }
        val next = if (old == null) {
            trim(facts + MemoryFact(newId(), k, value.trim(), FactSource.TASK, 0.6f, now, now, false, emptyList(), 1, detail))
        } else {
            facts.map { if (it.id == old.id) it.copy(count = it.count + 1, updatedAt = now, detail = detail.ifBlank { it.detail }) else it }
        }
        save(next)
    }

    /** Edit from the memory screen or a correction: user's own words, so confirmed. Not journaled. */
    @Synchronized
    fun edit(id: String, newValue: String): MemoryFact? {
        val v = newValue.trim()
        if (v.isBlank() || FactExtractor.isSensitive(v)) return null
        val facts = all()
        val old = facts.firstOrNull { it.id == id } ?: return null
        if (old.value == v && !old.pending) return old
        val f = old.copy(
            value = v, pending = false, source = FactSource.SAID, confidence = 1f, updatedAt = System.currentTimeMillis(),
            history = if (old.value == v) old.history else (listOf(old.value) + old.history).distinct().take(MAX_HISTORY),
        )
        save(facts.map { if (it.id == id) f else it })
        return f
    }

    @Synchronized
    fun confirm(id: String): MemoryFact? {
        val facts = all()
        val old = facts.firstOrNull { it.id == id } ?: return null
        val f = old.copy(pending = false, confidence = maxOf(old.confidence, 0.9f), updatedAt = System.currentTimeMillis())
        save(facts.map { if (it.id == id) f else it })
        return f
    }

    @Synchronized
    fun delete(id: String): MemoryFact? {
        val facts = all()
        val old = facts.firstOrNull { it.id == id } ?: return null
        save(facts.filterNot { it.id == id })
        return old
    }

    /** "Forget everything": all facts and the journal. The backup of the old memory file is also removed. */
    @Synchronized
    fun clearAll() {
        save(emptyList())
        journal = emptyList()
        KVUtils.putString(KEY_JOURNAL, "[]")
        try {
            KBManager.write(BACKUP_FILE, mapOf("type" to "user_memory_backup", "cleared" to "true"), "")
            KBManager.write(LEGACY_FILE, mapOf("type" to "user_memory", "cleared" to "true"), "")
        } catch (_: Exception) { }
    }

    // ── Journal ("forget that") ─────────────────────────────────────────────

    @Synchronized
    fun addJournal(e: JournalEntry) {
        val next = (loadJournal() + e).takeLast(MAX_JOURNAL)
        journal = next
        KVUtils.putString(KEY_JOURNAL, gson.toJson(next))
    }

    /**
     * Removes and returns the newest journal entry plus the entries learned from the same message
     * (same non-zero batch), newest first. Empty when there is nothing to forget.
     */
    @Synchronized
    fun popJournalBatch(): List<JournalEntry> {
        val j = loadJournal()
        val last = j.lastOrNull() ?: return emptyList()
        val taken = if (last.batch == 0L) listOf(last) else j.takeLastWhile { it.batch == last.batch }
        val next = j.dropLast(taken.size)
        journal = next
        KVUtils.putString(KEY_JOURNAL, gson.toJson(next))
        return taken.reversed()
    }

    /** Undo a fact change: back to the previous value, or delete it when it was new. Returns what was forgotten. */
    @Synchronized
    fun undo(e: JournalEntry): MemoryFact? {
        val facts = all()
        val f = facts.firstOrNull { it.id == e.factId } ?: return null
        return if (e.previousValue.isBlank()) {
            save(facts.filterNot { it.id == f.id })
            f
        } else {
            val restored = f.copy(
                value = e.previousValue,
                history = f.history.drop(1),
                updatedAt = System.currentTimeMillis(),
            )
            save(facts.map { if (it.id == f.id) restored else it })
            f
        }
    }

    private fun loadJournal(): List<JournalEntry> {
        journal?.let { return it }
        val j = try {
            KVUtils.getString(KEY_JOURNAL, "").takeIf { it.isNotBlank() }
                ?.let { gson.fromJson<List<JournalEntry>>(it, journalType) }?.filterNotNull()
        } catch (_: Exception) { null } ?: emptyList()
        journal = j
        return j
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun save(facts: List<MemoryFact>) {
        cache = facts
        version++
        KVUtils.putString(KEY_FACTS, gson.toJson(facts))
        MemoryManager.invalidateCache()
    }

    /** Over the cap: drop the oldest unconfirmed / least useful facts first, never profile or people. */
    private fun trim(facts: List<MemoryFact>): List<MemoryFact> {
        if (facts.size <= MAX_FACTS) return facts
        val keep = facts.filter { it.category == "profile" || (it.category == "person" && !it.pending) }
        val rest = facts.filter { it !in keep }
            .sortedWith(compareByDescending<MemoryFact> { !it.pending }.thenByDescending { it.count }.thenByDescending { it.updatedAt })
            .take(maxOf(0, MAX_FACTS - keep.size))
        return keep + rest
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

    internal fun normaliseKey(key: String): String {
        val k = key.trim().lowercase()
        if (k.isBlank()) return ""
        val cat = if (k.contains(':')) k.substringBefore(':').trim() else "note"
        val name = k.substringAfter(':', k).trim().replace(Regex("""\s+"""), "_").take(40)
        val category = when (cat) {
            "person", "people", "family", "relation" -> "person"
            "health", "medical", "condition" -> "health"
            "pref", "preference", "prefs" -> "pref"
            "profile", "user", "me" -> "profile"
            "contact" -> "contact"
            "app" -> "app"
            "alias", "group" -> "alias"
            else -> "note"
        }
        return if (name.isBlank()) "" else "$category:${FactExtractor.canonicalName(category, name)}"
    }

    /**
     * One-time migration of the old plain `user_preferences.md` bullets into facts. The file is copied to
     * [BACKUP_FILE] first and left in place. Lines that look like secrets are skipped.
     */
    private fun migrateLegacyOnce() {
        val done = try { KVUtils.getBoolean(KEY_MIGRATED, false) } catch (_: Exception) { return }
        if (done) return
        try {
            val legacy = KBManager.read(LEGACY_FILE).getOrNull().orEmpty()
            if (legacy.isNotBlank()) {
                KBManager.write(BACKUP_FILE, mapOf("type" to "user_memory_backup"), legacy)
                val now = System.currentTimeMillis()
                val facts = mutableListOf<MemoryFact>()
                for (raw in legacy.lines()) {
                    val line = raw.trim()
                    if (!line.startsWith("-") && !line.startsWith("*")) continue
                    val body = line.removePrefix("-").removePrefix("*").trim()
                        .replace(Regex("""^\[[^\]]*]\s*"""), "").trim()
                    if (body.length < 4 || FactExtractor.isSensitive(body)) continue
                    val structured = FactExtractor.extract(body)
                    if (structured.isNotEmpty()) {
                        structured.forEach { c ->
                            val k = normaliseKey(c.key)
                            facts.removeAll { it.key == k } // later lines are newer
                            facts += MemoryFact(newId(), k, c.value, FactSource.MIGRATED, 0.7f, now, now, false)
                        }
                    } else {
                        val k = normaliseKey("note:" + FactExtractor.slug(body).take(40))
                        if (facts.none { it.key == k }) facts += MemoryFact(newId(), k, body.take(200), FactSource.MIGRATED, 0.6f, now, now, false)
                    }
                }
                if (facts.isNotEmpty()) {
                    KVUtils.putString(KEY_FACTS, gson.toJson(trim(facts)))
                    XLog.i(TAG, "Migrated ${facts.size} facts from $LEGACY_FILE (backup: $BACKUP_FILE)")
                }
            }
            KVUtils.putBoolean(KEY_MIGRATED, true)
        } catch (e: Exception) {
            XLog.w(TAG, "Memory migration failed, will retry next start: ${e.message}")
        }
    }
}
