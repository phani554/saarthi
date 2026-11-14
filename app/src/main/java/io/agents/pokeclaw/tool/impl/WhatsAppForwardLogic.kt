// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

/**
 * Pure (JVM-testable) parts of forward_whatsapp_message: which bubble matches the message the
 * user means, and which control is WhatsApp's Forward button.
 */
object WhatsAppForwardLogic {
    const val FORWARD_ID = "com.whatsapp:id/menuitem_forward"
    const val FORWARD_ID_W4B = "com.whatsapp.w4b:id/menuitem_forward"
    val MESSAGE_TEXT_IDS = listOf("com.whatsapp:id/message_text", "com.whatsapp.w4b:id/message_text")

    data class Bubble(val text: String, val bottom: Int)

    sealed class Pick {
        data class Found(val index: Int) : Pick()
        object None : Pick()
        /** Several different bubbles match only loosely: ask for the exact words instead of guessing. */
        data class Ambiguous(val texts: List<String>) : Pick()
    }

    @JvmStatic
    fun normalize(s: String?): String = s.orEmpty()
        .replace(Regex("[\\u200B-\\u200F\\u2060\\uFEFF]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()

    /** 3 exact, 2 one starts with the other (long enough), 1 bubble contains the words, 0 no match. */
    @JvmStatic
    fun score(bubble: String, wanted: String): Int {
        val b = normalize(bubble)
        val w = normalize(wanted)
        if (b.isEmpty() || w.length < 2) return 0
        if (b == w) return 3
        if (w.length >= 12 && (b.startsWith(w) || w.startsWith(b) && b.length >= 12)) return 2
        if (w.length >= 6 && b.contains(w)) return 1
        return 0
    }

    /**
     * Best bubble for [wanted]: highest score; among equal scores the lowest on screen (newest).
     * Loose (score 1) matches with different texts are [Pick.Ambiguous].
     */
    @JvmStatic
    fun pick(bubbles: List<Bubble>, wanted: String): Pick {
        var best = 0
        for (b in bubbles) best = maxOf(best, score(b.text, wanted))
        if (best == 0) return Pick.None
        val top = bubbles.withIndex().filter { score(it.value.text, wanted) == best }
        if (best == 1) {
            val distinct = top.map { normalize(it.value.text) }.distinct()
            if (distinct.size > 1) return Pick.Ambiguous(top.map { it.value.text }.distinct())
        }
        return Pick.Found(top.maxByOrNull { it.value.bottom }!!.index)
    }

    /** WhatsApp's Forward action: its menu id, or a content description that is exactly "Forward". */
    @JvmStatic
    fun isForwardControl(viewId: String?, contentDesc: CharSequence?): Boolean {
        val id = viewId.orEmpty()
        if (id == FORWARD_ID || id == FORWARD_ID_W4B) return true
        return contentDesc?.toString()?.trim()?.equals("Forward", ignoreCase = true) == true
    }
}
