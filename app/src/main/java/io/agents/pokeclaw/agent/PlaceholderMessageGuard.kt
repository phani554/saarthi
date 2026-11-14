// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

/**
 * Stops a message that only NAMES a value instead of carrying it, e.g. the local model sent
 * "Flipkart minutes card total" to Kamya (phone log 2026-11-15 00:05) without reading the cart.
 * Returns the instruction for the model, or null when the message is fine.
 */
object PlaceholderMessageGuard {
    private val VALUE_WORDS = Regex("\\b(total|price|cost|amount|bill|cart|card total|kitna|कुल|किंमत|ధర|మొత్తం)\\b", RegexOption.IGNORE_CASE)
    private val HAS_VALUE = Regex("(₹|rs\\.?|inr|rupees?)\\s*\\d|\\d")

    fun check(message: String): String? {
        val m = message.trim()
        if (m.isEmpty() || !VALUE_WORDS.containsMatchIn(m) || HAS_VALUE.containsMatchIn(m)) return null
        // Long natural sentences without a number are suspicious too, but only short labels are blocked.
        if (m.split(Regex("\\s+")).size > 8) return null
        return "NOT SENT: the message \"$m\" names a value but does not contain it. " +
            "First get the real value (for a cart total call read_cart, or open the cart and read the total with ₹), " +
            "then call send_message again with the actual amount and items in the text."
    }
}
