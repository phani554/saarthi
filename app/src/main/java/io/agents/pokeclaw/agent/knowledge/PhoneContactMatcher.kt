// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.knowledge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import io.agents.pokeclaw.utils.XLog
import java.util.Locale

/**
 * Maps a name or nickname the user said ("Kamya", "Pinky") to one phone contact, without the model.
 *
 * Search order: the contact's display name, then the Nickname field of the phone's contacts.
 * Only the best-matching tier is kept (exact name > name starts with it > a word of the name).
 * When that leaves more than one number, the number with the most recent call in the call log wins.
 * When no call history separates them and more than one contact is left, it gives up (returns null)
 * rather than guess who gets a message or call.
 */
object PhoneContactMatcher {

    private const val TAG = "PhoneContactMatcher"

    data class Match(val displayName: String, val number: String, val reason: String)

    private data class Candidate(val contactId: Long, val name: String, val number: String, val score: Int)

    fun find(context: Context, phrase: String): Match? {
        val q = phrase.trim()
        if (q.length < 2 || !granted(context, Manifest.permission.READ_CONTACTS)) return null
        val candidates = try {
            (byDisplayName(context, q) + byNickname(context, q))
        } catch (e: Exception) {
            XLog.w(TAG, "Contact search failed: ${e.message}")
            return null
        }
        if (candidates.isEmpty()) return null
        val best = candidates.maxOf { it.score }
        // One entry per number (the same number can sit on two contacts or be saved twice).
        val tier = candidates.filter { it.score == best }.distinctBy { tail(it.number) }
        if (tier.size == 1) {
            val c = tier[0]
            return Match(c.name, c.number, "only match").also { log(q, it, tier.size) }
        }
        val lastCall = lastCallTimes(context, tier.map { tail(it.number) }.toSet())
        val recent = tier.filter { (lastCall[tail(it.number)] ?: 0L) > 0L }
            .maxByOrNull { lastCall[tail(it.number)] ?: 0L }
        if (recent != null) {
            return Match(recent.name, recent.number, "most recent call").also { log(q, it, tier.size) }
        }
        // No call history: one contact with several numbers still names one person (number left open).
        val people = tier.map { it.contactId }.distinct()
        if (people.size == 1) {
            val c = tier[0]
            return Match(c.name, "", "one contact, no call history").also { log(q, it, tier.size) }
        }
        XLog.i(TAG, "'$q' matches ${people.size} contacts and none was called recently; not guessing")
        return null
    }

    private fun log(q: String, m: Match, n: Int) =
        XLog.i(TAG, "'$q' -> '${m.displayName}' (${m.reason}, $n candidate numbers)")

    private fun byDisplayName(context: Context, q: String): List<Candidate> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val out = mutableListOf<Candidate>()
        context.contentResolver.query(uri, proj, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val number = c.getString(2) ?: continue
                val score = nameScore(name, q)
                if (score > 0) out += Candidate(c.getLong(0), name, number, score)
            }
        }
        return out
    }

    /** Nickname saved on the contact ("Pinky" on "Priya Sharma"). Counts as an exact match. */
    private fun byNickname(context: Context, q: String): List<Candidate> {
        val ids = mutableSetOf<Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Nickname.NAME),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Nickname.NAME} LIKE ?",
            arrayOf(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, q),
            null
        )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<Candidate>()
        val placeholders = ids.joinToString(",") { "?" }
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray(),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val number = c.getString(2) ?: continue
                out += Candidate(c.getLong(0), name, number, 3)
            }
        }
        return out
    }

    /** 3 = whole name, 2 = name starts with the phrase as words, 1 = phrase is a word inside the name. */
    internal fun nameScore(name: String, phrase: String): Int {
        val n = norm(name)
        val p = norm(phrase)
        if (p.isEmpty()) return 0
        if (n == p) return 3
        if (n.startsWith("$p ")) return 2
        if (" $n ".contains(" $p ")) return 1
        return 0
    }

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{Nd}]+"""), " ").trim()

    internal fun tail(number: String): String = number.filter { it.isDigit() }.takeLast(10)

    /** Most recent call time per number tail, from the call log (needs READ_CALL_LOG). */
    private fun lastCallTimes(context: Context, tails: Set<String>): Map<String, Long> {
        if (tails.isEmpty() || !granted(context, Manifest.permission.READ_CALL_LOG)) {
            if (tails.isNotEmpty()) XLog.w(TAG, "No call-log permission: cannot pick the recent number")
            return emptyMap()
        }
        val out = HashMap<String, Long>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                var seen = 0
                while (c.moveToNext() && seen < 1000 && out.size < tails.size) {
                    seen++
                    val t = tail(c.getString(0) ?: continue)
                    if (t in tails && t !in out) out[t] = c.getLong(1)
                }
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Call log read failed: ${e.message}")
        }
        return out
    }

    private fun granted(context: Context, perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}
