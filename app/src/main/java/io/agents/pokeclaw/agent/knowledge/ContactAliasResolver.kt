// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.knowledge

import android.provider.ContactsContract
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.utils.XLog

/**
 * Resolves nicknames, abbreviations, and semantic shortcuts (e.g. "major project group", "bhai")
 * to the actual contact or WhatsApp group name (e.g. "Final Year Project", "Rahul")
 * using learned memory and AI reasoning.
 */
object ContactAliasResolver {

    private const val TAG = "ContactAliasResolver"

    /**
     * Resolve a raw contact or group reference to its actual display name.
     * Uses learned memory from MemoryManager and single-shot AI matching.
     */
    fun resolve(rawName: String): String {
        val clean = rawName.trim()
        if (clean.isBlank()) return clean

        // Fast local device contact lookup first
        val deviceName = lookupDeviceContact(clean)
        if (!deviceName.isNullOrBlank()) {
            XLog.i(TAG, "ContactAliasResolver: fast device contact match '$clean' -> '$deviceName'")
            return deviceName
        }

        val memory = MemoryManager.getMemoryPromptSection()

        // If memory contains an explicit mapping, try fast AI resolution
        val systemPrompt = "You resolve contact nicknames, abbreviations, and indirect references to their exact target contact or WhatsApp group display name based on user memory."
        val prompt = """User memory:
$memory

Raw contact/group reference: "$clean"

Task: What is the exact display name of this contact or WhatsApp group?
Rules:
1. If user memory links "$clean" to an actual group or person name (e.g. "major project group" -> "Final Year Project"), output ONLY the exact group/person name.
2. If no alias is in memory, output the cleaned name.
3. Reply with ONLY the resolved display name. No quotes, no explanation."""

        return try {
            val result = LlmSessionManager.singleShotCloud(systemPrompt, prompt, 0.1)
                ?: LlmSessionManager.singleShotLocal(systemPrompt, prompt, 0.1)

            val resolved = result?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
            if (!resolved.isNullOrBlank() && resolved.length < 50) {
                XLog.i(TAG, "ContactAliasResolver: '$clean' -> '$resolved'")
                resolved
            } else {
                clean
            }
        } catch (e: Exception) {
            XLog.w(TAG, "ContactAliasResolver failed, returning raw name '$clean'", e)
            clean
        }
    }

    private fun lookupDeviceContact(name: String): String? {
        return try {
            val ctx = ClawApplication.instance
            val resolver = ctx.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$name%")

            resolver.query(uri, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(0)
                    if (!displayName.isNullOrBlank()) {
                        return displayName
                    }
                }
            }
            null
        } catch (e: Exception) {
            XLog.w(TAG, "lookupDeviceContact error for '$name'", e)
            null
        }
    }
}
