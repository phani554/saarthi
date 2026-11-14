// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.knowledge

import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.utils.XLog

/**
 * The user's memory: structured facts ([MemoryFact]) kept locally in MMKV by [MemoryFactStore].
 *
 * - [learnFromMessage]: facts the user states (English / Hindi / Telugu regex, then an AI pass off-thread).
 * - [rememberTaskOutcome]: cheap preferences from finished tasks (usual orders, who is called, apps used).
 * - [getMemoryPromptSection] (query): the core profile plus the facts relevant to the current request.
 * - [forgetLast]: "yeh bhool jao" / "forget that" undoes the last learned fact.
 * Secrets (PIN, OTP, passwords, card / Aadhaar numbers) are never stored.
 */
object MemoryManager {

    private const val TAG = "MemoryManager"
    private const val MAX_PROMPT_FACTS = 12
    private const val MAX_PROMPT_CHARS = 1200
    private const val FORGET_DEDUPE_MS = 20_000L

    @Volatile
    private var cachedMemoryPrompt: String? = null
    @Volatile
    private var lastCacheTime: Long = 0L
    private const val CACHE_TTL_MS = 60_000L // 1 minute in-memory cache

    internal fun isSensitive(text: String): Boolean = FactExtractor.isSensitive(text)

    /** Plain statements kept as notes when no structured fact matched. Task commands are not notes. */
    private val NOTE_PATTERNS = listOf(
        Regex("""(?i)\b(?:remember\s+that|note\s+that|keep\s+in\s+mind\s+that|yaad\s+rakho\s+ki|yaad\s+rakhna\s+ki|gurthu\s+pettuko)\s+(.{4,200})"""),
        Regex("""(?i)\bmy\s+(?:home\s+)?(?:address|favou?rite\s+\w+|preferred\s+\w+|office|house)\s+is\s+.{3,200}"""),
        Regex("""(?i)\b(?:i\s+)?(?:always|never)\s+(?:order|buy|use|pick|eat|drink)\s+.{3,150}"""),
        Regex("""(?i)\bmera\s+(?:ghar|pata|address)\s+.{3,200}"""),
    )

    private val extractionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // ── Prompt ──────────────────────────────────────────────────────────────

    /**
     * Memory section for system prompts without a request (chat, auto-reply, rewriter): the core profile plus
     * the most used / newest facts, capped. Cached for a minute.
     */
    fun getMemoryPromptSection(): String {
        val now = System.currentTimeMillis()
        val currentCache = cachedMemoryPrompt
        if (currentCache != null && (now - lastCacheTime) < CACHE_TTL_MS) {
            return currentCache
        }
        // Local only. Cloud Mem0 results are added separately by callers that have a real query
        // (calling HybridMemoryRepository from here used to recurse forever).
        val formatted = buildPromptSection("")
        cachedMemoryPrompt = formatted
        lastCacheTime = now
        return formatted
    }

    /**
     * Memory section for one request: the core profile (name, language, key people) plus the facts most
     * relevant to [query] (words, Hindi/Telugu synonyms, names, apps), at most ~12 facts / ~1200 chars.
     */
    fun getMemoryPromptSection(query: String): String =
        if (query.isBlank()) getMemoryPromptSection() else buildPromptSection(query)

    private fun buildPromptSection(query: String): String {
        val facts = try { MemoryFactStore.all() } catch (e: Exception) {
            XLog.w(TAG, "memory facts unavailable: ${e.message}"); emptyList()
        }
        val chosen = FactExtractor.select(facts, query, MAX_PROMPT_FACTS, MAX_PROMPT_CHARS)
        val language = languageLine()
        val care = try { io.agents.pokeclaw.care.CareProfileStore.get().promptSection() } catch (_: Exception) { "" }
        if (chosen.isEmpty() && language.isBlank()) return care
        val body = buildString {
            append("\n\n## What you know about the user\n")
            append("Use these to personalise actions and answers. Facts marked (not confirmed) are guesses: check with the user before acting on them.\n")
            if (language.isNotBlank()) append("- ").append(language).append('\n')
            chosen.forEach { append("- ").append(FactExtractor.render(it)).append('\n') }
        }
        return body + care
    }

    private fun languageLine(): String {
        val code = try { io.agents.pokeclaw.utils.KVUtils.getSarvamLanguageCode() } catch (_: Exception) { "" }
        val name = when (code.substringBefore('-').lowercase()) {
            "hi" -> "Hindi"; "te" -> "Telugu"; "en" -> "English"; "mr" -> "Marathi"; "ta" -> "Tamil"
            "kn" -> "Kannada"; "bn" -> "Bengali"; "gu" -> "Gujarati"; "ml" -> "Malayalam"; "pa" -> "Punjabi"
            "" -> return ""
            else -> code
        }
        return "Voice language setting: $name"
    }

    fun invalidateCache() { cachedMemoryPrompt = null }

    /**
     * All local facts as plain lines (no network, no recursion). Used by HybridMemoryRepository's local
     * fallback and by alias lookups. Secrets are never stored, so none can appear here.
     */
    fun localMemoryText(): String = try {
        MemoryFactStore.all().joinToString("\n") { "- " + FactExtractor.render(it) }
    } catch (_: Exception) { "" }

    /** Local facts relevant to [query], as plain lines (blank query = core + top facts). */
    fun relevantFactsText(query: String): String = try {
        FactExtractor.select(MemoryFactStore.all(), query, MAX_PROMPT_FACTS, MAX_PROMPT_CHARS)
            .joinToString("\n") { "- " + FactExtractor.render(it) }
    } catch (_: Exception) { "" }

    // ── Learning from what the user says ────────────────────────────────────

    /**
     * Scan an incoming user prompt/message and save the facts it states.
     * "yeh bhool jao" / "forget that" instead forgets the last learned fact.
     */
    fun learnFromMessage(userText: String) {
        if (userText.isBlank()) return
        val raw = userText.trim()

        if (FactExtractor.isForgetCommand(raw)) {
            val what = forgetLastOnce()
            XLog.i(TAG, "Voice forget: ${if (what == null) "nothing to forget" else "done"}")
            return
        }
        // Never learn from, or send to the AI extractor, anything that looks like a PIN/OTP/password/card.
        if (isSensitive(raw)) {
            XLog.w(TAG, "learnFromMessage: skipped a message that looks like a PIN/OTP/password/card number")
            return
        }

        val batch = System.nanoTime()
        val structured = FactExtractor.extract(raw)
        for (c in structured) {
            saveFact(c.key, c.value, FactSource.SAID, 0.85f, pending = false, batch = batch)
        }
        // Explicit statements with no known shape ("remember that ...", "my address is ...") stay as notes.
        if (structured.isEmpty()) {
            raw.split('\n', ';', '।').map { it.trim() }.filter { it.length >= 8 }.forEach { line ->
                val m = NOTE_PATTERNS.firstNotNullOfOrNull { it.find(line) } ?: return@forEach
                val note = (m.groups[1]?.value ?: m.value).trim().trimEnd('.', '!')
                if (note.length >= 5) saveFact("note:" + FactExtractor.slug(note).take(40), note, FactSource.SAID, 0.8f, pending = false, batch = batch)
            }
        }

        // Fast, offline: "watchman ka number 98..." -> care profile (pending) right away; undoable by "forget that".
        try {
            io.agents.pokeclaw.care.CareMemoryLearner.learnFastContact(raw)?.let { c ->
                MemoryFactStore.addJournal(MemoryFactStore.JournalEntry(kind = "care", careRole = c.role, carePhone = c.phone, at = System.currentTimeMillis(), batch = batch))
            }
        } catch (e: Exception) { XLog.w(TAG, "care fast-learn failed: ${e.message}") }

        // AI extraction runs off the caller's thread: it used to add a full LLM round-trip
        // before every task started.
        extractionExecutor.execute { extractWithAi(raw, batch) }
    }

    private fun extractWithAi(raw: String, batch: Long) {
        // Local mode: no AI fact extraction (it was a cloud call); the offline patterns above still run.
        if (LlmSessionManager.isLocalOnly()) return
        try {
            val systemPrompt = "You extract personal user facts, relationships, group names, and preferences from user messages regardless of language."
            val prompt = """Analyze this user message for personal facts or relationships:
"$raw"

The message may be Hindi, Telugu, Marathi or mixed. "fact" = a personal preference, family relation,
address, contact mapping or group name as one concise English sentence, or NONE.
${io.agents.pokeclaw.care.CareMemoryLearner.AI_SCHEMA}"""

            val rawJson = LlmSessionManager.singleShotInteraction(systemPrompt, prompt, 0.1).orEmpty()
            // Care contacts, conditions and medicines go into the care profile; structured memory facts and
            // the plain fact go into memory as guesses (pending) that the user can confirm on the Memory screen.
            val extracted = io.agents.pokeclaw.care.CareMemoryLearner.applyAiJson(rawJson)
            val structured = io.agents.pokeclaw.care.CareMemoryLearner.parseMemoryFacts(rawJson)
            var saved = 0
            for ((k, v) in structured) {
                if (saveFact(k, v, FactSource.LEARNED, 0.6f, pending = true, batch = batch) != null) saved++
            }
            if (saved == 0 && structured.isEmpty() && !extracted.isNullOrBlank() &&
                !extracted.equals("NONE", ignoreCase = true) && extracted.length > 5) {
                // Structure it ourselves when possible; else keep the sentence as a note.
                val parsed = FactExtractor.extract(extracted)
                if (parsed.isNotEmpty()) parsed.forEach { saveFact(it.key, it.value, FactSource.LEARNED, 0.6f, pending = true, batch = batch) }
                else saveFact("note:" + FactExtractor.slug(extracted).take(40), extracted, FactSource.LEARNED, 0.5f, pending = true, batch = batch)
            }
        } catch (e: Exception) {
            XLog.w(TAG, "AI memory extraction failed: ${e.message}")
        }
    }

    /**
     * Explicitly record a personal fact/preference the user stated ("remember that ...").
     * Structured when it matches a known pattern, else kept as a note.
     */
    fun recordFact(fact: String) {
        recordFactLocal(fact, syncCloud = true)
    }

    /** Same as [recordFact]; [syncCloud] = false when the caller already sends it to Mem0. Returns what was saved. */
    fun recordFactLocal(fact: String, syncCloud: Boolean): List<MemoryFact> {
        val text = fact.trim()
        if (text.isBlank()) return emptyList()
        if (isSensitive(text)) {
            XLog.w(TAG, "recordFact: dropped a fact that looks like a PIN/OTP/password/card number")
            return emptyList()
        }
        val parsed = FactExtractor.extract(text)
        val batch = System.nanoTime()
        val saved = if (parsed.isNotEmpty()) {
            parsed.mapNotNull { saveFact(it.key, it.value, FactSource.SAID, 0.9f, pending = false, syncCloud = syncCloud, batch = batch) }
        } else {
            listOfNotNull(saveFact("note:" + FactExtractor.slug(text).take(40), text, FactSource.SAID, 0.8f, pending = false, syncCloud = syncCloud, batch = batch))
        }
        return saved
    }

    /**
     * Correct a saved fact: the fact whose value (or rendered line) contains [oldText] gets [newText].
     * Returns the updated fact, or null when nothing matched (then [newText] is saved as a new fact by the caller).
     */
    fun correctFact(oldText: String, newText: String): MemoryFact? {
        val old = oldText.trim()
        val new = newText.trim()
        if (old.isBlank() || new.isBlank() || isSensitive(new)) return null
        val facts = MemoryFactStore.all()
        val target = facts.firstOrNull { it.value.equals(old, true) }
            ?: facts.firstOrNull { it.value.contains(old, true) }
            ?: facts.firstOrNull { FactExtractor.render(it).contains(old, true) }
            ?: return null
        // "my son is Ramesh" as the new text -> just the value "Ramesh" when the key matches.
        val newValue = FactExtractor.extract(new).firstOrNull { it.key == target.key }?.value ?: new
        val updated = MemoryFactStore.edit(target.id, newValue) ?: return null
        HybridMemoryRepository.recordTurnAsync("user_preference_fact", FactExtractor.render(updated))
        return updated
    }

    private fun saveFact(
        key: String,
        value: String,
        source: String,
        confidence: Float,
        pending: Boolean,
        syncCloud: Boolean = true,
        batch: Long = 0L,
    ): MemoryFact? {
        if (batch != 0L && batch in forgottenBatches) return null
        val f = MemoryFactStore.upsert(key, value, source, confidence, pending, undoable = true, batch = batch) ?: return null
        cachedMemoryPrompt = null
        XLog.i(TAG, "Saved fact ${f.key} (${f.source}${if (f.pending) ", pending" else ""})")
        // Only what the user said goes to the optional Mem0 cloud; guesses and task habits stay on the phone.
        if (syncCloud && source == FactSource.SAID && !f.pending) {
            HybridMemoryRepository.recordTurnAsync("user_preference_fact", FactExtractor.render(f))
        }
        return f
    }

    // ── Learning from finished tasks ────────────────────────────────────────

    /**
     * Cheap, offline learning from a finished task: usual orders ("Amul milk 1 litre on Blinkit" ->
     * pref:milk = "Amul 1 L (Blinkit)"), who the user calls or messages, and which apps they use.
     * Failed tasks teach nothing. Safe to call from any thread.
     */
    fun rememberTaskOutcome(task: String, success: Boolean, summary: String) {
        if (!success || task.isBlank()) return
        extractionExecutor.execute {
            try {
                val request = io.agents.pokeclaw.agent.TaskPromptEnvelope.parse(task).currentRequest
                    .substringBefore("\n\n(Hint:").trim()
                if (request.isBlank() || isSensitive(request)) return@execute

                val batch = System.nanoTime()
                FactExtractor.orderPreferences(request).forEach { c ->
                    saveFact(c.key, c.value, FactSource.TASK, 0.7f, pending = false, syncCloud = false, batch = batch)
                }

                FactExtractor.contactUse(request)?.let { (who, how) ->
                    // "beta ko call karo" -> the son's saved name when known.
                    val name = FactExtractor.relationOf(who)?.let { rel -> MemoryFactStore.get("person:$rel")?.takeIf { !it.pending }?.value }
                        ?: who
                    MemoryFactStore.bump("contact:" + name.lowercase(), name, how)
                }

                val apps = FactExtractor.appsIn(request).toMutableList()
                if (apps.isEmpty() && FactExtractor.contactUse(request)?.second == "WhatsApp") apps += "WhatsApp"
                apps.forEach { MemoryFactStore.bump("app:" + FactExtractor.slug(it), it) }
                cachedMemoryPrompt = null
                XLog.d(TAG, "rememberTaskOutcome: learned from finished task (summaryLen=${summary.length})")
            } catch (e: Exception) {
                XLog.w(TAG, "rememberTaskOutcome failed: ${e.message}")
            }
        }
    }

    // ── Forgetting ──────────────────────────────────────────────────────────

    private val forgottenBatches: MutableSet<Long> = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    @Volatile private var lastForgetAt = 0L
    @Volatile private var lastForgetResult: String? = null

    /**
     * Forget the last thing learned (a fact, or a care number heard in conversation). A changed fact goes back
     * to its previous value; a new one is deleted. Returns a short description to tell the user, or null when
     * there is nothing to forget.
     */
    @Synchronized
    fun forgetLast(): String? {
        // Stale entries (fact already deleted, number already confirmed) are skipped; the journal holds at most 20.
        repeat(25) {
            val entries = MemoryFactStore.popJournalBatch()
            if (entries.isEmpty()) return null
            // The AI pass for that message may still be running: drop what it saves later too.
            entries.map { it.batch }.filter { it != 0L }.forEach { forgottenBatches.add(it) }
            val forgotten = entries.mapNotNull { e -> undoEntry(e) }
            if (forgotten.isNotEmpty()) {
                cachedMemoryPrompt = null
                return forgotten.joinToString("; ")
            }
        }
        return null
    }

    private fun undoEntry(e: MemoryFactStore.JournalEntry): String? {
        if (e.kind == "care") {
            val c = io.agents.pokeclaw.care.CareContact(role = e.careRole, phone = e.carePhone)
            val key = io.agents.pokeclaw.care.CareProfile.keyOf(c)
            val stillPending = io.agents.pokeclaw.care.CareProfileStore.get().contacts
                .any { it.pending && io.agents.pokeclaw.care.CareProfile.keyOf(it) == key }
            if (!stillPending) return null // already confirmed or removed by the user
            io.agents.pokeclaw.care.CareProfileStore.discardPendingContact(c)
            return "${io.agents.pokeclaw.care.CareRoles.displayName(e.careRole)} number"
        }
        val f = MemoryFactStore.undo(e) ?: return null
        val line = FactExtractor.render(f.copy(pending = false))
        return if (e.previousValue.isBlank()) line else "$line (back to ${e.previousValue})"
    }

    /**
     * [forgetLast] for one spoken request. The same words can reach both [learnFromMessage] and the
     * forget_memory tool, so a second call within 20 s returns the first result instead of forgetting more.
     */
    @Synchronized
    fun forgetLastOnce(): String? {
        val now = System.currentTimeMillis()
        if (now - lastForgetAt < FORGET_DEDUPE_MS) return lastForgetResult
        val r = forgetLast()
        lastForgetAt = now
        lastForgetResult = r
        return r
    }

    /** Forget the fact whose key or value matches [what] ("milk", "son"). Returns what was removed. */
    fun forgetMatching(what: String): String? {
        val w = what.trim()
        if (w.isBlank()) return null
        val q = FactExtractor.tokens(w)
        val facts = MemoryFactStore.all()
        val target = facts.firstOrNull { it.value.equals(w, true) }
            ?: facts.firstOrNull { FactExtractor.tokens(it.key.replace(':', ' ').replace('_', ' ')).any { t -> t in q } }
            ?: facts.firstOrNull { it.value.contains(w, true) }
            ?: return null
        MemoryFactStore.delete(target.id)
        cachedMemoryPrompt = null
        return FactExtractor.render(target.copy(pending = false))
    }

    /** Facts matching [query] (or all), as lines for the inspect_memory tool. */
    fun describeFacts(query: String): String {
        val all = MemoryFactStore.all()
        val q = query.trim()
        val list = if (q.isBlank() || q.equals("all", true)) all
        else {
            val t = FactExtractor.tokens(q)
            all.filter { f -> FactExtractor.score(f, t, q.lowercase()) > 0.0 || f.value.contains(q, true) || f.key.contains(q, true) }
        }
        return list.joinToString("\n") { "- " + FactExtractor.render(it) }
    }
}
