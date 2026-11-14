package io.agents.pokeclaw.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageDetectorTest {

    private val EN = ReplyLanguage.EN
    private val HI = ReplyLanguage.HI
    private val MR = ReplyLanguage.MR
    private val TE = ReplyLanguage.TE

    // ── Spoken turns (Sarvam code + probability) ──────────────────────────────────────────

    @Test
    fun `english speech switches a hindi conversation to english`() {
        assertEquals(EN, LanguageDecider.decide(HI, "en-IN", 0.95, "What is the weather today?"))
    }

    @Test
    fun `hindi codemix speech gives hindi`() {
        assertEquals(HI, LanguageDecider.decide(EN, "hi-IN", 0.9, "मेरा phone number क्या है"))
    }

    @Test
    fun `marathi speech gives marathi`() {
        assertEquals(MR, LanguageDecider.decide(HI, "mr-IN", 0.88, "मला दूध पाठवा"))
    }

    @Test
    fun `telugu script always gives telugu`() {
        assertEquals(TE, LanguageDecider.decide(EN, "te-IN", 0.9, "అమ్మకి మెసేజ్ పంపండి"))
        assertEquals(TE, LanguageDecider.decide(HI, "kn-IN", 0.3, "అమ్మకి మెసేజ్ పంపండి"))
    }

    @Test
    fun `one word or yes no keeps the previous language`() {
        assertEquals(TE, LanguageDecider.decide(TE, "en-IN", 0.99, "ok"))
        assertEquals(EN, LanguageDecider.decide(EN, "hi-IN", 0.99, "हाँ"))
        assertEquals(MR, LanguageDecider.decide(MR, "hi-IN", 0.99, "haan ji"))
        assertEquals(EN, LanguageDecider.decide(EN, "hi-IN", 0.99, "theek hai"))
    }

    @Test
    fun `low probability keeps previous unless the text is clear`() {
        // Weak Latin text, unsure STT: keep.
        assertEquals(HI, LanguageDecider.decide(HI, "en-IN", 0.4, "Ravi Kumar"))
        // Devanagari with low probability stays within Hindi/Marathi.
        assertEquals(MR, LanguageDecider.decide(MR, "hi-IN", 0.4, "दूध और ब्रेड"))
    }

    @Test
    fun `unsupported stt language keeps previous`() {
        assertEquals(EN, LanguageDecider.decide(EN, "ta-IN", 0.9, "Ravi Kumar Swamy"))
    }

    @Test
    fun `devanagari with english stt code is not english`() {
        assertEquals(HI, LanguageDecider.decide(EN, "en-IN", 0.9, "मुझे दूध चाहिए"))
    }

    // ── Typed text ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `typed english gets english`() {
        assertEquals(EN, LanguageDecider.decide(HI, null, null, "Please call my son and tell him I am late"))
        assertEquals(EN, LanguageDecider.decide(TE, null, null, "What is the time now?"))
    }

    @Test
    fun `typed hinglish gets hindi`() {
        assertEquals(HI, LanguageDecider.decide(EN, null, null, "mujhe 2 litre milk order karo"))
        assertEquals(HI, LanguageDecider.decide(EN, null, null, "Ravi ko bolo main aa rahi hoon"))
    }

    @Test
    fun `more typed english and hinglish`() {
        assertEquals(EN, LanguageDecider.decide(HI, null, null, "Remind me to take my medicine at 9 pm"))
        assertEquals(EN, LanguageDecider.decide(HI, null, null, "Order 2 packets of Amul milk on Blinkit"))
        assertEquals(HI, LanguageDecider.decide(EN, null, null, "Blinkit se 1 kg aloo mangwao"))
        assertEquals(HI, LanguageDecider.decide(EN, "hi-IN", 0.9, "Blinkit se 1 kg aloo mangwao"))
    }

    @Test
    fun `typed romanised marathi gets marathi`() {
        assertEquals(MR, LanguageDecider.decide(EN, null, null, "mala doodh pathva aani bread pan"))
    }

    @Test
    fun `typed romanised telugu gets telugu`() {
        assertEquals(TE, LanguageDecider.decide(EN, null, null, "naaku amma ki call cheyyandi"))
    }

    @Test
    fun `typed devanagari marathi vs hindi`() {
        assertEquals(MR, LanguageDecider.decide(HI, null, null, "मला उद्या आठवण करून द्या आणि औषध सांगा"))
        assertEquals(HI, LanguageDecider.decide(MR, null, null, "मुझे कल दवाई की याद दिलाना"))
    }

    // ── Voice for the reply text ──────────────────────────────────────────────────────────

    @Test
    fun `english reply never gets a hindi voice`() {
        assertEquals(EN, LanguageDetector.ttsLanguageFor("Your balance is 12,500 rupees.", HI))
        assertEquals(EN, LanguageDetector.ttsLanguageFor("Calling Ravi now.", MR))
        // Quoted Hindi message inside an English confirmation does not flip the voice.
        assertEquals(EN, LanguageDetector.ttsLanguageFor("Send 'मैं आ रही हूँ, खाना तैयार रखना' to Ravi? Yes or no?", HI))
    }

    @Test
    fun `devanagari reply keeps hindi or marathi`() {
        assertEquals(HI, LanguageDetector.ttsLanguageFor("ठीक है, कर रही हूँ।", HI))
        assertEquals(MR, LanguageDetector.ttsLanguageFor("ठीक आहे, करते.", MR))
        assertEquals(HI, LanguageDetector.ttsLanguageFor("ठीक है, कर रही हूँ।", EN))
        assertEquals(MR, LanguageDetector.ttsLanguageFor("मला समजलं, आता काम सुरू आहे", EN))
    }

    @Test
    fun `telugu reply gets telugu voice`() {
        assertEquals(TE, LanguageDetector.ttsLanguageFor("సరే, చేస్తున్నాను.", EN))
    }

    @Test
    fun `old romanised hindi line gets hindi voice`() {
        assertEquals(HI, LanguageDetector.ttsLanguageFor("Theek hai, SOS band kar diya.", EN))
    }

    @Test
    fun `isWrittenIn checks script`() {
        assertTrue(LanguageDetector.isWrittenIn("Message sent to Ravi.", EN))
        assertFalse(LanguageDetector.isWrittenIn("Message sent to Ravi.", HI))
        assertTrue(LanguageDetector.isWrittenIn("रवि को मैसेज भेज दिया।", HI))
        assertFalse(LanguageDetector.isWrittenIn("రవికి మెసేజ్ పంపాను.", MR))
    }

    @Test
    fun `short utterance detection`() {
        assertTrue(LanguageDetector.isShortUtterance("haan"))
        assertTrue(LanguageDetector.isShortUtterance("ok ok"))
        assertTrue(LanguageDetector.isShortUtterance("हाँ जी"))
        assertFalse(LanguageDetector.isShortUtterance("call Ravi now"))
    }

    @Test
    fun `fromCode only accepts the four languages`() {
        assertEquals(EN, ReplyLanguage.fromCode("en-IN"))
        assertEquals(MR, ReplyLanguage.fromCode("mr-IN"))
        assertEquals(null, ReplyLanguage.fromCode("ta-IN"))
        assertEquals(null, ReplyLanguage.fromCode("unknown"))
        assertEquals(null, ReplyLanguage.fromCode(null))
    }
}
