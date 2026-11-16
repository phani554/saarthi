// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

/**
 * Stops the model typing the whole instruction into a shop's search bar
 * ("Order a dress from Flipkart. Find one, add it to the cart..." went in as the search text).
 * A search is a few product words: "red dress for women", "amul taaza 1l".
 */
object SearchQueryGuard {

    const val MAX_WORDS = 6

    private val INSTRUCTION_WORDS = Regex(
        """(?<![\p{L}])(order|add it|add to (the )?cart|cart|checkout|payment|stop before|find one|so i can|please|open|flipkart|amazon|blinkit|zepto|whatsapp|send|call)(?![\p{L}])""",
        RegexOption.IGNORE_CASE
    )

    /** Null when [query] is fine to type; otherwise the error to hand back to the model. */
    fun check(query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val words = q.split(Regex("\\s+"))
        val sentence = q.contains('.') && words.size > 3
        val instruction = INSTRUCTION_WORDS.findAll(q).count() >= 2
        if (words.size <= MAX_WORDS && !sentence && !instruction) return null
        return "Search text rejected: '${q.take(60)}' is an instruction, not a search. " +
            "Pass only the product words (at most $MAX_WORDS), e.g. find_search_bar(query=\"red dress for women\")."
    }
}
