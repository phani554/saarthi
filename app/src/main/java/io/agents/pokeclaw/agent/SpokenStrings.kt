// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.service.VoiceManager
import io.agents.pokeclaw.utils.KVUtils

/**
 * Short lines Saathi says to the user (shown in chat and spoken), in her language.
 * The agent model never reads these: everything it reads stays English (tool errors, confirmation
 * notices). Language = the language Sarvam STT last detected, else the saved voice language,
 * else Hindi. Hindi and Telugu are written in Latin script like the other built-in lines.
 * Quoted message text is passed through verbatim, never translated.
 */
object SpokenStrings {

    enum class Lang { HI, TE, EN }

    @JvmStatic
    fun lang(): Lang {
        val code = (try { VoiceManager.lastDetectedLanguageCode } catch (_: Exception) { "" })
            .ifBlank { KVUtils.getSarvamLanguageCode() }
            .lowercase()
        return when {
            code.startsWith("te") -> Lang.TE
            code.startsWith("en") -> Lang.EN
            else -> Lang.HI
        }
    }

    @JvmStatic
    fun confirmSend(contact: String, message: String): String = when (lang()) {
        Lang.HI -> "$contact ko '$message' bhejun? Haan ya na?"
        Lang.TE -> "$contact ki '$message' pampanaa? Avunu leda vaddu?"
        Lang.EN -> "Send '$message' to $contact? Yes or no?"
    }

    /** [callType]: phone | whatsapp_voice | whatsapp_video */
    @JvmStatic
    fun confirmCall(contact: String, callType: String): String {
        val kind = when (callType) {
            "whatsapp_video" -> "WhatsApp video"
            "whatsapp_voice" -> "WhatsApp"
            else -> "phone"
        }
        return when (lang()) {
            Lang.HI -> "$contact ko $kind call karun? Haan ya na?"
            Lang.TE -> "$contact ki $kind call cheyyanaa? Avunu leda vaddu?"
            Lang.EN -> "Call $contact on $kind? Yes or no?"
        }
    }

    @JvmStatic
    fun declined(): String = when (lang()) {
        Lang.HI -> "Theek hai, nahi kiya."
        Lang.TE -> "Sare, cheyyaledu."
        Lang.EN -> "Okay, I didn't do it."
    }

    @JvmStatic
    fun confirmShoppingList(items: List<String>, store: String): String {
        val list = items.joinToString(", ")
        return when (lang()) {
            Lang.HI -> "List mein yeh hai: $list. $store ke cart mein daalun? Haan ya na?"
            Lang.TE -> "List lo ivi unnayi: $list. $store cart lo veyyanaa? Avunu leda vaddu?"
            Lang.EN -> "The list has: $list. Add them to the $store cart? Yes or no?"
        }
    }

    /** Task start line. [app] may be blank when the target app is not known. */
    @JvmStatic
    fun taskStart(app: String): String = when (lang()) {
        Lang.HI -> if (app.isBlank()) "Theek hai, kar rahi hoon." else "Theek hai, $app khol rahi hoon."
        Lang.TE -> if (app.isBlank()) "Sare, chestunnanu." else "Sare, $app open chestunnanu."
        Lang.EN -> if (app.isBlank()) "Okay, doing it now." else "Okay, opening $app."
    }

    private val APP_NAMES = listOf(
        "WhatsApp", "Blinkit", "Zepto", "Instamart", "Swiggy", "Flipkart", "BigBasket", "Amazon",
        "YouTube", "Messages"
    )

    /** First known app named in [text] ("" when none), for the start line. */
    @JvmStatic
    fun appNameIn(text: String): String {
        val lower = text.lowercase()
        if (lower.contains("flipkart minutes")) return "Flipkart Minutes"
        if (lower.contains("big basket")) return "BigBasket"
        return APP_NAMES.firstOrNull { Regex("\\b" + it.lowercase() + "\\b").containsMatchIn(lower) } ?: ""
    }

    @JvmStatic
    fun aiNotSetUp(): String = when (lang()) {
        Lang.HI -> "AI set nahi hai. Settings › AI model kholiye."
        Lang.TE -> "AI set cheyyaledu. Settings › AI model teravandi."
        Lang.EN -> AI_NOT_SET_UP_EN
    }

    /** English refusal text for logs, errors and the agent side. */
    const val AI_NOT_SET_UP_EN = "AI is not set up. Open Settings › AI model."
}
