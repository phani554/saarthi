// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

/**
 * Decides, per task, whether a Flipkart search belongs in Flipkart Minutes (10-minute groceries and
 * daily needs) or in the regular Flipkart store (clothes, shoes, electronics...). Code only switches to
 * Minutes when this says MINUTES; when the item is unclear (or not known yet, e.g. it comes from a
 * WhatsApp message) the agent gets one extra step: decide the store from the item and the installed apps.
 */
object ShoppingStoreRouter {

    enum class Mode { MINUTES, MAIN, UNKNOWN }

    /** Mode of the task now running; read by the Flipkart search helpers. */
    @Volatile
    var current: Mode = Mode.UNKNOWN

    private val QUICK = listOf(
        "milk", "bread", "egg", "eggs", "butter", "curd", "dahi", "paneer", "cheese", "ghee",
        "atta", "rice", "dal", "sugar", "salt", "oil", "tea", "chai", "coffee", "masala", "spice",
        "vegetable", "vegetables", "sabzi", "fruit", "fruits", "tomato", "tomatoes", "onion", "onions",
        "potato", "potatoes", "cucumber", "banana", "apple", "lemon", "coriander", "chilli",
        "snack", "snacks", "chips", "biscuit", "biscuits", "chocolate", "kitkat", "dairy milk", "namkeen",
        "maggi", "noodles", "juice", "cold drink", "pepsi", "coke", "ice cream", "water bottle",
        "soap", "shampoo", "toothpaste", "toothbrush", "detergent", "surf", "dishwash", "tissue",
        "diaper", "diapers", "sanitary", "grocery", "groceries", "kirana",
    )

    private val MAIN = listOf(
        "frock", "dress", "saree", "sari", "kurta", "kurti", "shirt", "t-shirt", "tshirt", "jeans", "trouser",
        "pant", "pants", "lehenga", "dupatta", "jacket", "sweater", "shoe", "shoes", "sandal", "sandals",
        "chappal", "slipper", "watch", "phone", "mobile", "laptop", "tablet", "headphone", "headphones",
        "earphone", "earphones", "earbuds", "charger", "tv", "television", "fridge", "refrigerator",
        "washing machine", "mixer", "grinder", "cooker", "toy", "toys", "bag", "backpack", "book", "books",
        "bedsheet", "curtain", "furniture", "mattress", "spectacles", "jewellery", "jewelry",
    )

    private fun hasWord(lower: String, w: String) = Regex("""(?<![a-z])${Regex.escape(w)}(?![a-z])""").containsMatchIn(lower)

    fun isShopping(task: String): Boolean = Regex(
        """(?i)\b(flipkart|minutes|amazon|blinkit|zepto|instamart|bigbasket|myntra|meesho|cart|order|buy|price|availab\w*|shop\w*)\b"""
    ).containsMatchIn(task)

    fun modeFor(task: String): Mode {
        val lower = task.lowercase()
        if (hasWord(lower, "minutes") || hasWord(lower, "minute")) return Mode.MINUTES
        val quick = QUICK.any { hasWord(lower, it) }
        val main = MAIN.any { hasWord(lower, it) }
        return when {
            quick && !main -> Mode.MINUTES
            main && !quick -> Mode.MAIN
            else -> Mode.UNKNOWN
        }
    }

    /** Prompt section for the agent; empty when the task is not about shopping. */
    fun promptSection(task: String, mode: Mode): String {
        if (!isShopping(task)) return ""
        return "\n\n## Store choice\n" + storeLine(mode) + "\n\n" + WHO_FOR
    }

    /**
     * Clothes are searched for the person they are for. Phone log 2026-11-15 00:30: Kamya (an adult woman)
     * asked for a red frock, the agent searched "Red Frock" and Flipkart showed girls' frocks.
     */
    const val WHO_FOR = """## Who it is for (clothes, shoes, gifts)
Before searching clothes or shoes, work out who they are for and put that in the search text:
- Use what you know about the person: their relation in memory (daughter, son, wife, grandson...), how they write in the chat, and their name.
- Adult woman: search "<colour> dress for women" (in India "frock" alone shows girls' frocks), or "<item> for women". Adult man: "<item> for men".
- Child: only when memory or the message says it is for a child (grandchild, "beti ke liye", an age under 14, a kids' size). Then search "<item> for girls/boys".
- Not sure: assume an adult if the person sends their own WhatsApp messages, and say in one short line who you searched for."""

    private fun storeLine(mode: Mode): String {
        return when (mode) {
            Mode.MINUTES -> "This item is a quick-commerce item: on Flipkart use the Minutes tab (Saathi switches to it right after Flipkart opens)."
            Mode.MAIN -> "This item is NOT a 10-minute item: on Flipkart search the regular Flipkart store, never the Minutes tab. If Flipkart opens on Minutes, go back to the main Flipkart home first."
            Mode.UNKNOWN -> """
                The right store for the item is not clear yet. Do this extra step before searching:
                1. Know the item (if it comes from a message, read the message first).
                2. Call get_installed_apps once to see which shopping apps she has.
                3. Decide: groceries, snacks, fruit and vegetables, dairy, daily household items -> quick commerce (Flipkart Minutes tab, Blinkit, Zepto or Instamart, whichever is installed or named). Clothes (frock, saree, kurta), shoes, electronics, toys, books, home goods -> the regular store (main Flipkart, Amazon...), never Flipkart Minutes.
                4. Write your choice in one short line in your reply text, then continue in that store.
            """.trimIndent()
        }
    }
}
