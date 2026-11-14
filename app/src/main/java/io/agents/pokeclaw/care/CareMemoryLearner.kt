// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.care

import io.agents.pokeclaw.utils.XLog
import org.json.JSONObject

/**
 * Turns things said in conversation ("watchman ka number 98xxxxxxx hai", "my son Ravi is my
 * emergency contact 98...", "I have sugar and BP") into structured care-profile entries.
 * Fast regex path works offline; [applyAiJson] applies the richer LLM extraction.
 */
object CareMemoryLearner {
    private const val TAG = "CareMemoryLearner"
    private val PHONE = Regex("""(?:\+?91[\s-]?)?[6-9]\d{4}[\s-]?\d{5}\b|\b\d{3,5}[\s-]?\d{6,8}\b""")

    /** Returns a short description of what was saved, or null. */
    fun learnFast(text: String): String? {
        val c = learnFastContact(text) ?: return null
        return "${CareRoles.displayName(c.role)} number noted (please confirm it in Care profile)"
    }

    /** Same as [learnFast]; returns the pending contact that was saved (so "forget that" can undo it), or null. */
    fun learnFastContact(text: String): CareContact? {
        val role = CareRoles.fromWords(text) ?: return null
        val phone = PHONE.find(text)?.value?.trim() ?: return null
        // Heard numbers are saved as pending: they never replace a saved contact and never join
        // the SOS chain until the user confirms them in Care profile.
        val c = CareContact(role = role, phone = phone)
        if (!CareProfileStore.addLearnedContact(c)) return null
        XLog.i(TAG, "Saved pending $role number from conversation")
        return c
    }

    /** Prompt section asking the model for structured care facts alongside the plain fact. */
    const val AI_SCHEMA = """Return ONLY JSON:
{"fact":"one concise English fact or NONE",
 "contacts":[{"role":"watchman|family_doctor|doctor|chemist|loved_one","name":"","phone":"","relation":""}],
 "conditions":["medical conditions, allergies or surgeries the user says they have"],
 "medicines":[{"name":"","dose":"","frequency":""}],
 "memory":[{"key":"person:son|person:daughter|person:<relation>|profile:name|health:<condition>|pref:<item>|alias:<nickname>","value":""}]}
"memory" = short facts about the user: family names (person:son = "Ravi"), their name, health conditions,
usual brands or shops (pref:milk = "Amul 1 L"), nicknames for contacts or groups (alias:bhai = "Rahul").
Never include PINs, OTPs, passwords, card, bank or Aadhaar numbers.
Only include what the user actually said. Use empty lists when nothing applies."""

    /** The "memory" array of the model's JSON as (key, value) pairs; empty when missing or malformed. */
    fun parseMemoryFacts(raw: String): List<Pair<String, String>> {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        return try {
            val arr = JSONObject(raw.substring(start, end + 1)).optJSONArray("memory") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString("key").trim()
                val v = o.optString("value").trim()
                // Placeholders copied from the schema ("person:<relation>", "a|b") are not facts.
                if (k.isBlank() || v.isBlank() || k.contains('<') || k.contains('|') || !k.contains(':')) null else k to v
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Bad memory JSON from model: ${e.message}")
            emptyList()
        }
    }

    /** Applies the model's JSON. Returns the plain fact (or null). */
    fun applyAiJson(raw: String): String? {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return raw.trim().takeIf { it.isNotBlank() && !it.equals("NONE", true) }
        return try {
            val o = JSONObject(raw.substring(start, end + 1))
            o.optJSONArray("contacts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val role = c.optString("role").trim()
                    if (role !in CareRoles.ALL) continue
                    val name = c.optString("name").trim(); val phone = c.optString("phone").trim()
                    if (name.isBlank() && phone.isBlank()) continue
                    CareProfileStore.addLearnedContact(CareContact(role, name, phone, c.optString("relation").trim()))
                }
            }
            o.optJSONArray("conditions")?.let { arr ->
                CareProfileStore.addHistory((0 until arr.length()).map { arr.optString(it) })
            }
            o.optJSONArray("medicines")?.let { arr ->
                CareProfileStore.addMedicines((0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { m -> Medicine(m.optString("name").trim(), m.optString("dose").trim(), m.optString("frequency").trim()) }
                })
            }
            o.optString("fact").trim().takeIf { it.length > 5 && !it.equals("NONE", true) }
        } catch (e: Exception) {
            XLog.w(TAG, "Bad care JSON from model: ${e.message}")
            null
        }
    }
}
