// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.lang.ReplyLanguage
import io.agents.pokeclaw.lang.ReplyLanguageState

/**
 * Short lines Saathi says to the user (shown in chat and spoken), in the reply language.
 * The agent model never reads these: everything it reads stays English (tool errors, confirmation
 * notices). Language = ReplyLanguageState (follows the language the user last spoke or typed, or
 * the fixed choice in Settings › Reply language). English is written in Latin, Hindi and Marathi in
 * Devanagari, Telugu in Telugu script, so the voice is chosen from the text (see LanguageDetector).
 * Quoted message text is passed through verbatim, never translated.
 */
object SpokenStrings {

    enum class Lang { HI, MR, TE, EN }

    @JvmStatic
    fun lang(): Lang = when (try { ReplyLanguageState.replyLanguage() } catch (_: Throwable) { ReplyLanguage.HI }) {
        ReplyLanguage.EN -> Lang.EN
        ReplyLanguage.HI -> Lang.HI
        ReplyLanguage.MR -> Lang.MR
        ReplyLanguage.TE -> Lang.TE
    }

    private inline fun pick(hi: () -> String, mr: () -> String, te: () -> String, en: () -> String): String =
        when (lang()) {
            Lang.HI -> hi()
            Lang.MR -> mr()
            Lang.TE -> te()
            Lang.EN -> en()
        }

    @JvmStatic
    fun confirmSend(contact: String, message: String): String = pick(
        { "$contact को '$message' भेजूँ? हाँ या ना?" },
        { "$contact ला '$message' पाठवू का? हो की नाही?" },
        { "$contact కి '$message' పంపనా? అవును లేదా వద్దు?" },
        { "Send '$message' to $contact? Yes or no?" }
    )

    /** [callType]: phone | whatsapp_voice | whatsapp_video */
    @JvmStatic
    fun confirmCall(contact: String, callType: String): String {
        val kind = when (callType) {
            "whatsapp_video" -> "WhatsApp video"
            "whatsapp_voice" -> "WhatsApp"
            else -> "phone"
        }
        return pick(
            { "$contact को $kind कॉल करूँ? हाँ या ना?" },
            { "$contact ला $kind कॉल करू का? हो की नाही?" },
            { "$contact కి $kind కాల్ చేయనా? అవును లేదా వద్దు?" },
            { "Call $contact on $kind? Yes or no?" }
        )
    }

    @JvmStatic
    /** Her reply to "send ...? yes or no?" was something else: the message was not sent. */
    fun notSentCancelled(): String = pick(
        { "वह संदेश नहीं भेजा गया।" },
        { "तो संदेश पाठवला नाही." },
        { "ఆ సందేశం పంపలేదు." },
        { "That message was not sent." }
    )

    @JvmStatic
    fun declined(): String = pick(
        { "ठीक है, नहीं किया।" },
        { "ठीक आहे, केलं नाही." },
        { "సరే, చేయలేదు." },
        { "Okay, I didn't do it." }
    )

    @JvmStatic
    fun confirmShoppingList(items: List<String>, store: String): String {
        val list = items.joinToString(", ")
        return pick(
            { "लिस्ट में यह है: $list. $store के कार्ट में डालूँ? हाँ या ना?" },
            { "यादीत हे आहे: $list. $store च्या कार्टमध्ये टाकू का? हो की नाही?" },
            { "లిస్ట్‌లో ఇవి ఉన్నాయి: $list. $store కార్ట్‌లో వేయనా? అవును లేదా వద్దు?" },
            { "The list has: $list. Add them to the $store cart? Yes or no?" }
        )
    }

    /** Task start line. [app] may be blank when the target app is not known. */
    @JvmStatic
    fun taskStart(app: String): String = pick(
        { if (app.isBlank()) "ठीक है, कर रही हूँ।" else "ठीक है, $app खोल रही हूँ।" },
        { if (app.isBlank()) "ठीक आहे, करते." else "ठीक आहे, $app उघडते." },
        { if (app.isBlank()) "సరే, చేస్తున్నాను." else "సరే, $app ఓపెన్ చేస్తున్నాను." },
        { if (app.isBlank()) "Okay, doing it now." else "Okay, opening $app." }
    )

    @JvmStatic
    fun didNotHear(): String = pick(
        { "मुझे आपकी आवाज़ नहीं आई। कृपया फिर से बोलिए।" },
        { "मला तुमचा आवाज आला नाही. कृपया पुन्हा बोला." },
        { "మీ మాట వినిపించలేదు. దయచేసి మళ్ళీ చెప్పండి." },
        { "I couldn't hear you. Please say it again." }
    )

    @JvmStatic
    fun cancelledOk(): String = pick(
        { "ठीक है, कोई बात नहीं। और कुछ चाहिए तो बताइए।" },
        { "ठीक आहे, काही हरकत नाही. अजून काही हवं असेल तर सांगा." },
        { "సరే, పర్వాలేదు. ఇంకేమైనా కావాలంటే చెప్పండి." },
        { "Okay, no problem. Tell me if you need anything else." }
    )

    /** Fallback question when the model gave no line of its own. */
    @JvmStatic
    fun askConfirm(): String = pick(
        { "क्या मैं यह करूँ? हाँ या ना बोलिए।" },
        { "हे करू का? हो किंवा नाही सांगा." },
        { "ఇది చేయనా? అవును లేదా వద్దు చెప్పండి." },
        { "Shall I do this? Say yes or no." }
    )

    /** Offline fallback: repeat the (English) task and ask for a yes. */
    @JvmStatic
    fun understoodAskYes(task: String): String = pick(
        { "मैंने समझा: $task. शुरू करने के लिए 'हाँ' बोलिए।" },
        { "मला समजलं: $task. सुरू करण्यासाठी 'हो' म्हणा." },
        { "నాకు అర్థమైంది: $task. మొదలుపెట్టడానికి 'అవును' అనండి." },
        { "I understood: $task. Say yes to start." }
    )

    @JvmStatic
    fun sosStopped(): String = pick(
        { "ठीक है, SOS बंद कर दिया।" },
        { "ठीक आहे, SOS बंद केलं." },
        { "సరే, SOS ఆపేశాను." },
        { "Okay, SOS is stopped." }
    )

    @JvmStatic
    fun taskStopped(): String = pick(
        { "काम रोक दिया।" },
        { "काम थांबवलं." },
        { "పని ఆపేశాను." },
        { "Task stopped." }
    )

    /** The agent finished without an answer: don't claim success in words. */
    @JvmStatic
    fun doneCheckScreen(): String = pick(
        { "हो गया, स्क्रीन देख लीजिए।" },
        { "झालं, स्क्रीन बघा." },
        { "అయిపోయింది, స్క్రీన్ చూడండి." },
        { "Done. Please check the screen." }
    )

    @JvmStatic
    fun callingNow(contact: String): String = pick(
        { "$contact को कॉल कर रही हूँ।" },
        { "$contact ला कॉल करते आहे." },
        { "$contact కి కాల్ చేస్తున్నాను." },
        { "Calling $contact now." }
    )

    @JvmStatic
    fun numberOnDialer(number: String): String = pick(
        { "मैंने नंबर $number डायलर पर डाल दिया है। देखकर कॉल बटन दबाइए।" },
        { "मी नंबर $number डायलरवर टाकला आहे. तपासून कॉल बटण दाबा." },
        { "$number నంబర్ డయలర్‌లో పెట్టాను. చూసి కాల్ బటన్ నొక్కండి." },
        { "I have put $number on the dialer. Please check it and press call." }
    )

    @JvmStatic
    fun whatsAppCallStart(contact: String, video: Boolean): String {
        val kind = if (video) "WhatsApp video" else "WhatsApp"
        return pick(
            { "$contact को $kind कॉल लगा रही हूँ।" },
            { "$contact ला $kind कॉल लावते." },
            { "$contact కి $kind కాల్ చేస్తున్నాను." },
            { "Starting a $kind call with $contact." }
        )
    }

    @JvmStatic
    fun sosCallingForHelp(name: String): String = pick(
        { "मदद के लिए $name को कॉल कर रही हूँ।" },
        { "मदतीसाठी $name ला कॉल करते." },
        { "సహాయం కోసం $name కి కాల్ చేస్తున్నాను." },
        { "Calling $name for help." }
    )

    @JvmStatic
    fun sosPressGreen(name: String): String = pick(
        { "$name को कॉल करने के लिए हरा कॉल बटन दबाइए।" },
        { "$name ला कॉल करण्यासाठी हिरवं कॉल बटण दाबा." },
        { "$name కి కాల్ చేయడానికి ఆకుపచ్చ కాల్ బటన్ నొక్కండి." },
        { "Please press the green call button to call $name." }
    )

    @JvmStatic
    fun sosNoNumber(): String = pick(
        { "कोई इमरजेंसी नंबर सेव नहीं है। Care profile में नंबर जोड़िए।" },
        { "कोणताही इमर्जन्सी नंबर सेव्ह नाही. Care profile मध्ये नंबर जोडा." },
        { "ఎమర్జెన్సీ నంబర్ ఏదీ సేవ్ చేయలేదు. Care profile లో నంబర్ జోడించండి." },
        { "No emergency number is saved. Please add one in Care profile." }
    )

    @JvmStatic
    fun apiKeyProblem(): String = pick(
        { "AI key काम नहीं कर रही है। Settings में नई key डालिए।" },
        { "AI key चालत नाही. Settings मध्ये नवी key टाका." },
        { "AI key పనిచేయడం లేదు. Settings లో కొత్త key పెట్టండి." },
        { "The AI key has expired or ran out. Please update it in Settings." }
    )

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
    fun aiNotSetUp(): String = pick(
        { "AI सेट नहीं है। Settings › AI model खोलिए।" },
        { "AI सेट केलेलं नाही. Settings › AI model उघडा." },
        { "AI సెట్ చేయలేదు. Settings › AI model తెరవండి." },
        { AI_NOT_SET_UP_EN }
    )

    /** English refusal text for logs, errors and the agent side. */
    const val AI_NOT_SET_UP_EN = "AI is not set up. Open Settings › AI model."
}
