package io.agents.pokeclaw.agent.knowledge

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneContactMatcherTest {
    @Test fun exactNameBeatsPrefixBeatsWord() {
        assertEquals(3, PhoneContactMatcher.nameScore("Kamya", "kamya"))
        assertEquals(2, PhoneContactMatcher.nameScore("Kamya Gupta Personal", "Kamya"))
        assertEquals(1, PhoneContactMatcher.nameScore("Didi Kamya", "kamya"))
        assertEquals(0, PhoneContactMatcher.nameScore("Kamyashree", "kamya"))
    }

    @Test fun numberTailIgnoresCountryCodeAndSpaces() {
        assertEquals("9876543210", PhoneContactMatcher.tail("+91 98765-43210"))
        assertEquals("9876543210", PhoneContactMatcher.tail("09876543210"))
    }
}
