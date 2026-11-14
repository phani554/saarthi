package io.agents.pokeclaw.agent.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingAndCartTest {
    @Test fun oneSonGetsRelationKeyAndNicknames() {
        val facts = OnboardingStore.factsFor(
            OnboardingStore.Answers(
                herName = "Lakshmi",
                people = listOf(
                    OnboardingStore.Person("Ravi", "son", "Munna, Chhotu"),
                    OnboardingStore.Person("Sita", "beti"),
                ),
            )
        ) { if (it.name == "Ravi") "Ravi Kumar" else it.name }.toMap()
        assertEquals("Ravi Kumar", facts["person:son"])
        assertEquals("Sita", facts["person:daughter"])
        assertEquals("Ravi Kumar", facts["alias:munna"])
        assertEquals("Ravi Kumar", facts["alias:chhotu"])
        assertEquals("Ravi Kumar", facts["alias:ravi"])
        assertTrue(facts["profile:children"]!!.startsWith("2:"))
    }

    @Test fun twoSonsDoNotClaimBeta() {
        val facts = OnboardingStore.factsFor(
            OnboardingStore.Answers(people = listOf(OnboardingStore.Person("Ravi", "son"), OnboardingStore.Person("Mohan", "son")))
        ).toMap()
        assertEquals(null, facts["person:son"])
    }

    @Test fun cartProductBecomesUsual() {
        val prefs = FactExtractor.cartPreferences(
            "order garam masala and milk from Flipkart Minutes",
            "Added to cart.\nITEMS: Everest Garam Masala 100 g ₹78; Amul Taaza Toned Milk 500 ml; Lays Chips"
        ).associate { it.key to it.value }
        assertEquals("Everest Garam Masala 100 g (Flipkart Minutes)", prefs["pref:garam_masala"])
        assertEquals("Amul Taaza Toned Milk 500 ml (Flipkart Minutes)", prefs["pref:milk"])
        assertEquals(2, prefs.size) // chips were not asked for
    }

    @Test fun itemsLineIsHidden() {
        assertEquals("Added to cart.", FactExtractor.stripItemsLine("Added to cart.\nITEMS: Everest Garam Masala 100 g"))
    }
}
