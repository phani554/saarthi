package io.agents.pokeclaw.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatchUtilsTest {

    @Test
    fun `matches contact name ignoring case and punctuation`() {
        assertTrue(
            ContactMatchUtils.matchesTarget(
                "Monica (Work)",
                null,
                "monica"
            )
        )
    }

    @Test
    fun `matches exact word boundary and avoids false substring matches`() {
        assertTrue(
            ContactMatchUtils.matchesTarget(
                "Message to Mom",
                null,
                "Mom"
            )
        )
        assertFalse(
            ContactMatchUtils.matchesTarget(
                "Tomorrow at 5pm",
                null,
                "Mom"
            )
        )
        assertFalse(
            ContactMatchUtils.matchesTarget(
                "Moments of joy",
                null,
                "Mom"
            )
        )
    }

    @Test
    fun `matches contact name prefix and exact full name`() {
        assertTrue(
            ContactMatchUtils.matchesTarget(
                "Kamya Gupta",
                null,
                "Kamya"
            )
        )
        assertTrue(
            ContactMatchUtils.matchesTarget(
                "Kamya Gupta",
                null,
                "Kamya Gupta"
            )
        )
        assertFalse(
            ContactMatchUtils.matchesTarget(
                "Kamyab",
                null,
                "Kamya"
            )
        )
    }

    @Test
    fun `matches phone numbers across formatting differences`() {
        assertTrue(
            ContactMatchUtils.matchesTarget(
                "+1 (604) 555-1234",
                null,
                "16045551234"
            )
        )
    }

    @Test
    fun `matches last digits when app omits country code`() {
        val normalizedAliases = ContactMatchUtils.buildNormalizedAliases("+1 604 555 1234")
        val digitAliases = ContactMatchUtils.buildDigitAliases("+1 604 555 1234")

        assertTrue(
            ContactMatchUtils.matchesCandidate(
                "604-555-1234",
                normalizedAliases,
                digitAliases
            )
        )
    }

    @Test
    fun `does not match unrelated contact`() {
        assertFalse(
            ContactMatchUtils.matchesTarget(
                "Alex",
                null,
                "Monica"
            )
        )
    }
}
