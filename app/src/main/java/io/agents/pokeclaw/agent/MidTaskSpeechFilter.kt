// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

/**
 * What to do with speech heard while a task is running.
 *
 * The mic re-opens after every yes/no, so it also hears the TV, a cough or half a word. Before this, anything
 * that was not "stop" killed the running task and became a new request (a dress search turned into a doctor
 * call). Now only a short stop phrase cancels, only a clear sentence redirects, and the rest is ignored.
 */
object MidTaskSpeechFilter {

    enum class Kind { CANCEL, REDIRECT, IGNORE }

    private const val L = """\p{L}\p{M}"""

    private val CANCEL_WORDS = Regex(
        """(?<![$L])(stop|cancel|wait|rok do|roko|ruko|bhai ruk|vaddu|aagu|thamba|halt|रुको|रोको|रुकिए|बंद करो|थांबा|ఆగు|ఆపు|వద్దు)(?![$L])""",
        RegexOption.IGNORE_CASE
    )

    /** Minimum words and letters for speech to count as a new request mid-task. */
    const val MIN_WORDS = 3
    const val MIN_LETTERS = 10

    fun classify(speech: String): Kind {
        val text = speech.trim().lowercase()
        if (text.isBlank()) return Kind.IGNORE
        val words = text.split(Regex("\\s+")).filter { w -> w.any { it.isLetter() } }
        if (CANCEL_WORDS.containsMatchIn(text) && words.size <= 4) return Kind.CANCEL
        val letters = text.count { it.isLetter() }
        if (words.size < MIN_WORDS || letters < MIN_LETTERS) return Kind.IGNORE
        // "haan haan haan", "hello hello hello": one word repeated is not a request.
        if (words.toSet().size == 1) return Kind.IGNORE
        return Kind.REDIRECT
    }
}
