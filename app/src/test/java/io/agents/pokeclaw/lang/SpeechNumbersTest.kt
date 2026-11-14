package io.agents.pokeclaw.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechNumbersTest {

    private val EN = ReplyLanguage.EN
    private val HI = ReplyLanguage.HI
    private val TE = ReplyLanguage.TE

    @Test
    fun `phone number read in two digit groups`() {
        assertEquals("Ravi's number is 9 8 7 6 5, 4 3 2 1 0.",
            SpeechNumbers.normalize("Ravi's number is 98765 43210.", EN))
        assertEquals("Call 9 8 7 6 5, 4 3 2 1 0 now",
            SpeechNumbers.normalize("Call 9876543210 now", EN))
        assertEquals("Call 9 8 7 6 5, 4 3 2 1 0",
            SpeechNumbers.normalize("Call +91 98765-43210", EN))
        assertEquals("Call 9 8 7 6 5, 4 3 2 1 0",
            SpeechNumbers.normalize("Call 91 98765 43210", EN))
    }

    @Test
    fun `phone number inside hindi text`() {
        assertEquals("रवि का नंबर 9 8 7 6 5, 4 3 2 1 0 है",
            SpeechNumbers.normalize("रवि का नंबर 9876543210 है", HI))
    }

    @Test
    fun `rupee amounts get commas and a rupee word`() {
        assertEquals("Total is 1,00,000 rupees.", SpeechNumbers.normalize("Total is ₹100000.", EN))
        assertEquals("Total is 1250 rupees", SpeechNumbers.normalize("Total is Rs. 1,250", EN))
        assertEquals("कुल 2,45,000 रुपये", SpeechNumbers.normalize("कुल ₹2,45,000", HI))
        assertEquals("మొత్తం 99.50 రూపాయలు", SpeechNumbers.normalize("మొత్తం ₹99.50", TE))
        assertEquals("Paid 500 rupees", SpeechNumbers.normalize("Paid ₹500/-", EN))
        assertEquals("Paid 500 rupees", SpeechNumbers.normalize("Paid ₹500.00", EN))
    }

    @Test
    fun `large plain numbers get indian commas, small ones stay`() {
        assertEquals("12,34,567 steps", SpeechNumbers.normalize("1234567 steps", EN))
        assertEquals("10,000 steps", SpeechNumbers.normalize("10000 steps", EN))
        assertEquals("2026 and 450 and 3.14", SpeechNumbers.normalize("2026 and 450 and 3.14", EN))
    }

    @Test
    fun `times and existing commas are kept`() {
        assertEquals("Meeting at 10:30 am", SpeechNumbers.normalize("Meeting at 10:30 am", EN))
        assertEquals("It costs 10,000", SpeechNumbers.normalize("It costs 10,000", EN))
    }

    @Test
    fun `otp is read digit by digit`() {
        assertEquals("Your OTP is 4 8 2 9 1 3", SpeechNumbers.normalize("Your OTP is 482913", EN))
    }

    @Test
    fun `long digit runs are spelled out`() {
        val out = SpeechNumbers.normalize("Order id 123456789012", EN)
        assertTrue(out, out.startsWith("Order id 1 2 3 4 5, 6 7 8 9 0, 1 2"))
    }

    @Test
    fun `indian grouping helper`() {
        assertEquals("1,00,00,000", SpeechNumbers.indianGrouping("10000000"))
        assertEquals("12,345", SpeechNumbers.indianGrouping("12345"))
        assertEquals("1234", SpeechNumbers.indianGrouping("1234"))
    }

    @Test
    fun `text without digits is unchanged`() {
        assertEquals("Okay, calling Ravi.", SpeechNumbers.normalize("Okay, calling Ravi.", EN))
    }
}
