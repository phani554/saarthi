// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class ContactExactScoringTest {

    @Test
    fun testExactMatchPrecedence() {
        val rawTarget = "Kamya"
        val aliases = ContactMatchUtils.buildNormalizedAliases(rawTarget)
        val digits = ContactMatchUtils.buildDigitAliases(rawTarget)

        // Exact candidate "kamya" vs Prefix candidate "kamya mom"
        assertTrue(ContactMatchUtils.matchesCandidate("kamya", aliases, digits))
        assertTrue(ContactMatchUtils.matchesCandidate("kamya mom", aliases, digits))

        // Confirm normalizeText matches alias exactly
        val normKamya = ContactMatchUtils.normalizeText("kamya")
        val normMom = ContactMatchUtils.normalizeText("kamya mom")

        assertTrue(aliases.contains(normKamya))
        assertTrue(!aliases.contains(normMom))
    }
}
