// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

/**
 * Classifies the user's reply to "send / call ...? Haan ya na?" (OutboundConfirmGate).
 * Pure Kotlin so it can be unit-tested on the JVM.
 *
 * Rules:
 * - Any clear "no" word (nahi, mat, ruko, cancel, vaddu, नको ...) → NO. The Hindi particle
 *   "na"/"naa"/"ना" right after a yes word is emphatic ("bhej do na" = please send) and not a no.
 * - A reply that adds a new request is OTHER, at any length: a connector such as aur / and / also /
 *   bhi / phir / ani / kuda, or a task verb for a different action (open, order, play, remind ...).
 *   Call verbs count as part of the yes only when the parked action is a call; message verbs only
 *   when it is a message.
 * - Up to [SHORT_MAX] words: YES when the first word is a yes word (old behaviour).
 * - Longer, up to [LONG_MAX] words ("haan bhej do Ravi ko abhi turant please"): YES when it starts
 *   with a yes word or contains a strong yes (haan, yes, ok, avunu, होय ...) anywhere.
 * - Everything else → OTHER.
 */
object ConfirmReplyClassifier {
    enum class Reply { YES, NO, OTHER }

    const val SHORT_MAX = 6
    const val LONG_MAX = 20

    private val NO_WORDS = setOf(
        "no", "nope", "nahi", "nahin", "nai", "na", "naa", "mat", "nako", "cancel", "stop", "ruko", "rehne",
        "vaddu", "vadhu", "kaadu", "ledu", "venda", "naahi", "thamba", "dont", "don't", "never",
        "नहीं", "नही", "ना", "मत", "रुको", "नाही", "नको", "थांबा", "రద్దు", "వద్దు", "కాదు", "లేదు"
    )

    /** "na" used as a polite/emphatic particle after a yes ("haan na", "bhejo na"). */
    private val EMPHATIC_NA = setOf("na", "naa", "ना")

    val YES_WORDS = setOf(
        "yes", "yeah", "yep", "ok", "okay", "okk", "sure", "haan", "han", "haa", "ha", "haanji", "hanji", "ji",
        "theek", "thik", "sahi", "bhejo", "bhej", "karo", "kar", "lagao", "milao", "avunu", "sare", "seri",
        "cheyyi", "pampu", "pampandi", "ho", "hoy", "pathva", "kara", "lava",
        "हाँ", "हां", "हा", "जी", "ठीक", "सही", "भेजो", "भेज", "करो", "लगाओ", "मिलाओ",
        "हो", "होय", "पाठवा", "करा", "लावा",
        "అవును", "సరే", "పంపు", "పంపండి", "చెయ్యి"
    )

    /** Unambiguous yes words that count anywhere in a long reply (not "ho", "ji", "sare", "kar"...). */
    private val STRONG_YES = setOf(
        "yes", "yeah", "yep", "ok", "okay", "okk", "sure", "haan", "haanji", "hanji", "avunu",
        "हाँ", "हां", "होय", "అవును", "సరే"
    )

    /** Words that join a second request onto the yes. */
    private val CONNECTORS = setOf(
        "and", "also", "then", "plus", "aur", "or", "bhi", "phir", "fir", "baad", "ani", "aani", "pan",
        "kuda", "kooda", "mariyu", "tarvata", "taruvata",
        "और", "भी", "फिर", "बाद", "आणि", "पण", "मरियु", "కూడా", "మరియు", "తర్వాత"
    )

    /** Verbs of a different action. */
    private val OTHER_TASK_VERBS = setOf(
        "open", "kholo", "khol", "play", "bajao", "chalao", "order", "mangao", "mangwao", "book", "search",
        "dhundo", "khojo", "remind", "yaad", "alarm", "timer", "photo", "camera", "buy", "kharido", "pay",
        "navigate", "weather", "news", "read", "padho", "suno", "dikhao", "batao",
        "खोलो", "चलाओ", "बजाओ", "मंगाओ", "ढूंढो", "खरीदो", "दिखाओ", "बताओ", "पढ़ो",
        "తెరువు", "ఆర్డర్"
    )

    private val CALL_VERBS = setOf("call", "phone", "fon", "dial", "कॉल", "फोन", "ఫోన్", "కాల్")
    private val MESSAGE_VERBS = setOf("message", "msg", "sms", "text", "whatsapp", "मैसेज", "మెసేజ్")

    private val CALL_TOOLS = setOf("place_call")
    private val MESSAGE_TOOLS = setOf("send_message", "message_care_contact", "forward_whatsapp_message")

    @JvmStatic
    fun tokens(text: String): List<String> = text.lowercase()
        .replace(Regex("[\\p{P}\\p{S}&&[^']]"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }

    /**
     * @param pendingTool the parked tool name (place_call, send_message...), or null when unknown:
     *   then both call and message verbs are taken as part of the yes.
     */
    @JvmStatic
    @JvmOverloads
    fun classify(text: String, pendingTool: String? = null): Reply {
        val t = tokens(text)
        if (t.isEmpty() || t.size > LONG_MAX) return Reply.OTHER

        // No words (except an emphatic "na" that follows a yes word).
        for ((i, w) in t.withIndex()) {
            if (w !in NO_WORDS) continue
            val emphatic = w in EMPHATIC_NA && i > 0 && t.subList(0, i).any { it in YES_WORDS }
            if (!emphatic) return Reply.NO
        }

        val callOk = pendingTool == null || pendingTool in CALL_TOOLS
        val msgOk = pendingTool == null || pendingTool in MESSAGE_TOOLS
        val addsRequest = t.any { w ->
            w in CONNECTORS || w in OTHER_TASK_VERBS ||
                (w in CALL_VERBS && !callOk) || (w in MESSAGE_VERBS && !msgOk)
        }
        if (addsRequest) return Reply.OTHER

        val startsYes = t.first() in YES_WORDS
        if (t.size <= SHORT_MAX) return if (startsYes) Reply.YES else Reply.OTHER
        return if (startsYes || t.any { it in STRONG_YES }) Reply.YES else Reply.OTHER
    }
}
