// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.knowledge

import io.agents.pokeclaw.care.CareContact
import io.agents.pokeclaw.care.CareProfileStore
import io.agents.pokeclaw.care.CareRoles
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

/**
 * First-run setup: the basics a family member types once (her name, what to call her, her children
 * and what she calls them, the watchman, health, usual products). Everything is saved as confirmed
 * facts in ONE batched write, so the basic tasks work from day one without her repeating anything.
 */
object OnboardingStore {
    private const val TAG = "OnboardingStore"
    private const val KEY_DONE = "KEY_ONBOARDING_DONE_V1"

    data class Person(
        val name: String,
        /** Her word or English: "son", "beti", "grandson"... */
        val relation: String,
        /** What she calls them, comma separated: "Munna, Chhotu". */
        val nicknames: String = "",
        val phone: String = "",
        /** Call and text them on SOS. */
        val sos: Boolean = true,
    )

    data class Answers(
        val herName: String = "",
        val callHer: String = "",
        /** "auto" or a ReplyLanguage code such as "hi-IN". */
        val language: String = "auto",
        val people: List<Person> = emptyList(),
        val watchmanName: String = "",
        val watchmanPhone: String = "",
        /** Comma separated: "sugar, BP". */
        val health: String = "",
        /** item -> exact product: "garam masala" -> "Everest Garam Masala 100 g". */
        val usualProducts: List<Pair<String, String>> = emptyList(),
    )

    fun isDone(): Boolean = KVUtils.getBoolean(KEY_DONE, false)

    fun markDone() { KVUtils.putBoolean(KEY_DONE, true) }

    /** Facts the answers turn into (key, value). Pure, so it is unit-tested. [resolved] maps a person to their contact name. */
    fun factsFor(a: Answers, resolved: (Person) -> String = { it.name.trim() }): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        a.herName.trim().takeIf { it.isNotEmpty() }?.let { out += "profile:name" to it }
        a.callHer.trim().takeIf { it.isNotEmpty() }?.let { out += "profile:call_me" to it }

        val people = a.people.filter { it.name.isNotBlank() }
        val relOf = people.associateWith { p -> FactExtractor.relationOf(p.relation.trim()) ?: FactExtractor.slug(p.relation.ifBlank { "family" }) }
        val byRel = people.groupBy { relOf.getValue(it) }
        for (p in people) {
            val contact = resolved(p)
            val rel = relOf.getValue(p)
            // "beta" can only mean one person when she has one son; with two, the model asks which.
            if (byRel.getValue(rel).size == 1) out += "person:$rel" to contact
            if (!p.name.trim().equals(contact, ignoreCase = true)) out += "alias:${FactExtractor.slug(p.name)}" to contact
            p.nicknames.split(',', '/', ';').map { it.trim() }.filter { it.length >= 2 }.forEach { nick ->
                out += "alias:${FactExtractor.slug(nick)}" to contact
            }
        }
        val kids = people.filter { relOf.getValue(it) == "son" || relOf.getValue(it) == "daughter" }
        if (kids.isNotEmpty()) {
            out += "profile:children" to "${kids.size}: " + kids.joinToString(", ") { k ->
                val nick = k.nicknames.trim().takeIf { it.isNotEmpty() }?.let { ", she calls them $it" }.orEmpty()
                "${resolved(k)} (${relOf.getValue(k)}$nick)"
            }
        }
        a.health.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { h ->
            out += "health:${FactExtractor.slug(h)}" to h
        }
        a.usualProducts.filter { it.first.isNotBlank() && it.second.isNotBlank() }.forEach { (item, product) ->
            out += "pref:${FactExtractor.slug(item.trim())}" to product.trim()
        }
        return out
    }

    /** Saves everything in one write and marks setup done. Returns how many facts were stored. Off the main thread. */
    fun save(a: Answers): Int {
        val contactNames = HashMap<Person, String>()
        val numbers = HashMap<Person, String>()
        val app = io.agents.pokeclaw.ClawApplication.instance
        a.people.filter { it.name.isNotBlank() }.forEach { p ->
            // Fill the saved contact name (and number) from the phone when the setup left them out.
            val m = try { PhoneContactMatcher.find(app, p.name.trim()) } catch (_: Exception) { null }
            contactNames[p] = m?.displayName ?: p.name.trim()
            numbers[p] = p.phone.trim().ifBlank { m?.number.orEmpty() }
        }
        val facts = factsFor(a) { contactNames[it] ?: it.name.trim() }
        var stored = 0
        MemoryFactStore.batch {
            facts.forEach { (k, v) ->
                if (MemoryFactStore.upsert(k, v, FactSource.SAID, 1.0f, pending = false, undoable = false) != null) stored++
            }
        }
        try {
            a.people.filter { it.name.isNotBlank() && it.sos }.forEach { p ->
                val phone = numbers[p].orEmpty()
                if (phone.count { it.isDigit() } >= 7) {
                    val rel = FactExtractor.relationOf(p.relation.trim()) ?: p.relation.trim()
                    CareProfileStore.upsertContact(CareContact(CareRoles.LOVED_ONE, contactNames[p] ?: p.name.trim(), phone, rel))
                }
            }
            if (a.watchmanPhone.count { it.isDigit() } >= 7) {
                CareProfileStore.upsertContact(CareContact(CareRoles.WATCHMAN, a.watchmanName.trim(), a.watchmanPhone.trim()))
            }
            a.health.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }?.let { CareProfileStore.addHistory(it) }
        } catch (e: Exception) {
            XLog.w(TAG, "Care profile part of setup failed: ${e.message}")
        }
        if (a.language.isNotBlank()) io.agents.pokeclaw.lang.ReplyLanguageState.setChoice(a.language)
        MemoryManager.invalidateCache()
        markDone()
        XLog.i(TAG, "Setup saved: $stored facts, ${a.people.size} people")
        return stored
    }
}
