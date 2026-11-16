package io.agents.pokeclaw.agent

import io.agents.pokeclaw.agent.MidTaskSpeechFilter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MidTaskSpeechAndSearchGuardTest {

    @Test fun noiseAndShortWordsAreIgnored() {
        assertEquals(Kind.IGNORE, MidTaskSpeechFilter.classify(""))
        assertEquals(Kind.IGNORE, MidTaskSpeechFilter.classify("haan"))
        assertEquals(Kind.IGNORE, MidTaskSpeechFilter.classify("hello hello hello"))
        assertEquals(Kind.IGNORE, MidTaskSpeechFilter.classify("अच्छा ठीक"))
    }

    @Test fun stopPhrasesCancel() {
        assertEquals(Kind.CANCEL, MidTaskSpeechFilter.classify("stop"))
        assertEquals(Kind.CANCEL, MidTaskSpeechFilter.classify("ruko ruko"))
        assertEquals(Kind.CANCEL, MidTaskSpeechFilter.classify("रुको"))
        assertEquals(Kind.CANCEL, MidTaskSpeechFilter.classify("బాబు ఆపు"))
    }

    @Test fun clearSentenceRedirects() {
        assertEquals(Kind.REDIRECT, MidTaskSpeechFilter.classify("red dress dikhao flipkart par"))
        assertEquals(Kind.REDIRECT, MidTaskSpeechFilter.classify("बेटे को व्हाट्सएप पर फोन लगाओ"))
        // "bus stop ke paas" mentions stop but is a sentence, not a cancel.
        assertEquals(Kind.REDIRECT, MidTaskSpeechFilter.classify("bus stop ke paas wali dukaan se doodh"))
    }

    @Test fun productWordsPassSearchGuard() {
        assertNull(SearchQueryGuard.check("red dress for women"))
        assertNull(SearchQueryGuard.check("Amul Taaza 1L"))
        assertNull(SearchQueryGuard.check("Dr. Manish"))
    }

    @Test fun wholeInstructionIsRejected() {
        assertNotNull(SearchQueryGuard.check(
            "Order a dress from Flipkart. Find one, add it to the cart and stop before payment so I can choose."))
        assertNotNull(SearchQueryGuard.check("order dress flipkart add to cart"))
        assertNotNull(SearchQueryGuard.check("cotton kurti for women with long sleeves size xl"))
    }
}
