// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.care

import com.google.gson.Gson
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

/** One person Saathi can call or message. */
data class CareContact(
    // Default "" so every field has a default: Kotlin then emits a no-arg constructor and Gson
    // fills missing JSON fields with these defaults instead of null.
    val role: String = "",         // watchman | family_doctor | chemist | loved_one | doctor
    val name: String = "",
    val phone: String = "",
    val relation: String = "",     // for loved ones: "son", "daughter"...
    val note: String = "",
    /** Learned from conversation and not yet confirmed by the user: never called or texted by SOS. */
    val pending: Boolean = false,
) {
    val digits: String get() = phone.filter { it.isDigit() || it == '+' }
    val hasPhone: Boolean get() = digits.count { it.isDigit() } >= 7
    fun label(): String = when {
        name.isNotBlank() && relation.isNotBlank() -> "$name ($relation)"
        name.isNotBlank() -> name
        else -> CareRoles.displayName(role)
    }
}

data class Medicine(
    val name: String,
    val dose: String = "",
    val frequency: String = "",   // e.g. "1-0-1 after food"
    val duration: String = "",
    val notes: String = "",
    val prescribedBy: String = "",
    val prescribedOn: String = "",
)

data class CareProfile(
    val contacts: List<CareContact> = emptyList(),
    val medicines: List<Medicine> = emptyList(),
    val medicalHistory: List<String> = emptyList(),   // conditions, allergies, surgeries
    /** Contact keys (see [CareProfile.keyOf]) in the order to call when distress fires. */
    val distressPriority: List<String> = emptyList(),
) {
    fun byRole(role: String): List<CareContact> = contacts.filter { it.role == role }
    fun first(role: String): CareContact? {
        val all = byRole(role)
        return all.firstOrNull { it.hasPhone && !it.pending } ?: all.firstOrNull { !it.pending }
            ?: all.firstOrNull { it.hasPhone } ?: all.firstOrNull()
    }

    /** Contacts learned from conversation that wait for the user's confirmation. */
    fun pendingContacts(): List<CareContact> = contacts.filter { it.pending }

    /** Distress order: the saved priority first, then any other callable contact (loved ones, watchman, doctor). */
    fun distressOrder(): List<CareContact> {
        // Unconfirmed (learned) contacts are never part of the SOS chain.
        val callable = contacts.filter { it.hasPhone && !it.pending }
        val ordered = distressPriority.mapNotNull { k -> callable.firstOrNull { keyOf(it) == k } }
        val defaultRank = listOf(CareRoles.LOVED_ONE, CareRoles.WATCHMAN, CareRoles.FAMILY_DOCTOR)
        val rest = callable.filter { it !in ordered && it.role in defaultRank }
            .sortedBy { defaultRank.indexOf(it.role) }
        return ordered + rest
    }

    /** Short block the agent sees in its system prompt. Phone numbers stay out of prompts. */
    fun promptSection(): String {
        if (contacts.isEmpty() && medicines.isEmpty() && medicalHistory.isEmpty()) return ""
        return buildString {
            append("\n\n## Care profile (use these when the user mentions them)\n")
            contacts.forEach { append("- ${CareRoles.displayName(it.role)}: ${it.label()}${if (it.hasPhone) " [number saved]" else ""}${if (it.pending) " (unconfirmed)" else ""}\n") }
            if (medicalHistory.isNotEmpty()) append("- Medical history: ${medicalHistory.joinToString("; ")}\n")
            if (medicines.isNotEmpty()) append("- Current medicines: ${medicines.joinToString("; ") { listOf(it.name, it.dose, it.frequency).filter { s -> s.isNotBlank() }.joinToString(" ") }}\n")
            append("- To message the chemist/doctor/watchman/loved one, use message_care_contact(role, message).\n")
        }
    }

    companion object {
        fun keyOf(c: CareContact): String = "${c.role}:${c.digits.ifBlank { c.name.lowercase() }}"
    }
}

object CareRoles {
    const val WATCHMAN = "watchman"
    const val FAMILY_DOCTOR = "family_doctor"
    const val DOCTOR = "doctor"
    const val CHEMIST = "chemist"
    const val LOVED_ONE = "loved_one"
    val ALL = listOf(LOVED_ONE, WATCHMAN, FAMILY_DOCTOR, CHEMIST, DOCTOR)

    fun displayName(role: String): String = when (role) {
        WATCHMAN -> "Building watchman"
        FAMILY_DOCTOR -> "Family doctor"
        DOCTOR -> "Doctor"
        CHEMIST -> "Chemist"
        LOVED_ONE -> "Emergency contact"
        else -> role
    }

    /** Maps spoken/typed words (English, Hindi, Telugu transliterations) to a role. */
    fun fromWords(text: String): String? {
        val t = text.lowercase()
        return when {
            Regex("""\b(watchman|chowkidar|security guard|guard|gatekeeper|darwan|watchmen)\b""").containsMatchIn(t) -> WATCHMAN
            Regex("""\b(family doctor|our doctor|gharelu doctor)\b""").containsMatchIn(t) -> FAMILY_DOCTOR
            Regex("""\b(chemist|pharmacy|pharmacist|medical store|medical shop|dawai wala|dawa dukan|medicine shop)\b""").containsMatchIn(t) -> CHEMIST
            Regex("""\b(emergency contact|loved one|caretaker)\b""").containsMatchIn(t) -> LOVED_ONE
            Regex("""\b(doctor|dr\.?)\b""").containsMatchIn(t) -> DOCTOR
            else -> null
        }
    }
}

/** Persists the profile as one JSON blob in MMKV (same store as the rest of the app's settings). */
object CareProfileStore {
    private const val KEY = "KEY_CARE_PROFILE_V1"
    private val gson = Gson()
    @Volatile private var cache: CareProfile? = null

    @Synchronized
    fun get(): CareProfile {
        cache?.let { return it }
        val loaded = try {
            KVUtils.getString(KEY, "").takeIf { it.isNotBlank() }?.let { gson.fromJson(it, CareProfile::class.java) }
        } catch (e: Exception) {
            XLog.w("CareProfileStore", "Corrupt care profile JSON, starting empty: ${e.message}")
            null
        } ?: CareProfile()
        // Gson can leave Kotlin non-null lists as null when a field is missing in old JSON.
        val safe = CareProfile(
            contacts = (loaded.contacts ?: emptyList()).filter { it.role.isNotBlank() },
            medicines = loaded.medicines ?: emptyList(),
            medicalHistory = loaded.medicalHistory ?: emptyList(),
            distressPriority = loaded.distressPriority ?: emptyList(),
        )
        cache = safe
        return safe
    }

    @Synchronized
    fun save(profile: CareProfile) {
        cache = profile
        KVUtils.putString(KEY, gson.toJson(profile))
        io.agents.pokeclaw.agent.knowledge.MemoryManager.invalidateCache() // prompt shows the new profile
    }

    @Synchronized
    fun update(block: (CareProfile) -> CareProfile): CareProfile {
        val next = block(get())
        save(next)
        return next
    }

    /** Add or replace a contact. Same role + same number (or same name when no number) = replace. */
    fun upsertContact(c: CareContact): CareProfile = update { p ->
        val singleRole = c.role in setOf(CareRoles.WATCHMAN, CareRoles.FAMILY_DOCTOR, CareRoles.CHEMIST)
        val others = p.contacts.filterNot { old ->
            old.role == c.role && (singleRole || CareProfile.keyOf(old) == CareProfile.keyOf(c) ||
                (old.name.isNotBlank() && old.name.equals(c.name, true)))
        }
        val merged = p.contacts.firstOrNull { it.role == c.role && (singleRole || it.name.equals(c.name, true)) }
            ?.let { old -> c.copy(name = c.name.ifBlank { old.name }, phone = c.phone.ifBlank { old.phone }, relation = c.relation.ifBlank { old.relation }) }
            ?: c
        val newKey = CareProfile.keyOf(merged)
        // A replaced contact keeps its place in the SOS order instead of dropping to the end.
        val removedKeys = p.contacts.filter { it !in others }.map { CareProfile.keyOf(it) }.toSet()
        var priority = p.distressPriority.map { if (it in removedKeys) newKey else it }.distinct()
        if (merged.hasPhone && !merged.pending && newKey !in priority &&
            merged.role in setOf(CareRoles.LOVED_ONE, CareRoles.WATCHMAN)) priority = priority + newKey
        if (merged.pending) priority = priority - newKey
        val nextContacts = others + merged
        val validKeys = nextContacts.map { CareProfile.keyOf(it) }.toSet()
        p.copy(contacts = nextContacts, distressPriority = priority.filter { it in validKeys })
    }

    /**
     * Saves a contact heard in conversation as pending (unconfirmed). It never replaces an existing
     * contact and is never added to the SOS chain; the user confirms it with [confirmContact].
     * Returns false when nothing new was saved.
     */
    fun addLearnedContact(c: CareContact): Boolean {
        var added = false
        update { p ->
            val key = CareProfile.keyOf(c)
            val existing = p.contacts.firstOrNull { CareProfile.keyOf(it) == key }
            if (existing != null) {
                p
            } else {
                added = true
                p.copy(contacts = p.contacts + c.copy(pending = true))
            }
        }
        return added
    }

    /** User confirmed a learned contact: saves it like a manual entry (may join the SOS chain). */
    fun confirmContact(c: CareContact): CareProfile {
        update { p -> p.copy(contacts = p.contacts.filterNot { it.pending && CareProfile.keyOf(it) == CareProfile.keyOf(c) }) }
        return upsertContact(c.copy(pending = false))
    }

    /** User rejected a learned contact. */
    fun discardPendingContact(c: CareContact): CareProfile = update { p ->
        p.copy(contacts = p.contacts.filterNot { it.pending && CareProfile.keyOf(it) == CareProfile.keyOf(c) })
    }

    fun addHistory(items: List<String>): CareProfile = update { p ->
        val clean = items.map { it.trim() }.filter { it.length > 2 }
        p.copy(medicalHistory = (p.medicalHistory + clean.filter { n -> p.medicalHistory.none { it.equals(n, true) } }))
    }

    fun addMedicines(meds: List<Medicine>): CareProfile = update { p ->
        val byName = p.medicines.associateBy { it.name.lowercase() }.toMutableMap()
        meds.filter { it.name.isNotBlank() }.forEach { byName[it.name.lowercase()] = it }
        p.copy(medicines = byName.values.toList())
    }
}
