// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import io.agents.pokeclaw.tool.impl.WhatsAppForwardLogic.Bubble
import io.agents.pokeclaw.tool.impl.WhatsAppForwardLogic.Pick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppForwardLogicTest {
    @Test fun exactBeatsContains() {
        val bubbles = listOf(Bubble("Doctor appointment at 5 pm tomorrow", 300), Bubble("appointment at 5 pm", 500))
        assertEquals(Pick.Found(1), WhatsAppForwardLogic.pick(bubbles, "Appointment at 5 PM"))
    }

    @Test fun newestOfSameTextWins() {
        val bubbles = listOf(Bubble("Good morning", 200), Bubble("Good morning", 800))
        assertEquals(Pick.Found(1), WhatsAppForwardLogic.pick(bubbles, "good morning"))
    }

    @Test fun truncatedPrefixMatches() {
        val bubbles = listOf(Bubble("Please bring milk and bread on your way home tonight", 400))
        assertEquals(Pick.Found(0), WhatsAppForwardLogic.pick(bubbles, "please bring milk and bread"))
    }

    @Test fun looseMatchesWithDifferentTextsAreAmbiguous() {
        val bubbles = listOf(Bubble("call the doctor today", 200), Bubble("doctor said call tomorrow, doctor", 400))
        assertTrue(WhatsAppForwardLogic.pick(bubbles, "doctor") is Pick.Ambiguous)
    }

    @Test fun noMatch() {
        assertEquals(Pick.None, WhatsAppForwardLogic.pick(listOf(Bubble("hello", 1)), "goodbye"))
        assertEquals(Pick.None, WhatsAppForwardLogic.pick(emptyList(), "goodbye"))
    }

    @Test fun forwardControl() {
        assertTrue(WhatsAppForwardLogic.isForwardControl("com.whatsapp:id/menuitem_forward", null))
        assertTrue(WhatsAppForwardLogic.isForwardControl(null, "Forward"))
        assertFalse(WhatsAppForwardLogic.isForwardControl(null, "Forwarded"))
        assertFalse(WhatsAppForwardLogic.isForwardControl("com.whatsapp:id/forwarded_label", "Forwarded message"))
    }
}
