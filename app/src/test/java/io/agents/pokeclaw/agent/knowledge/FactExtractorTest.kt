// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the memory fact extraction / relevance logic (no Android, no MMKV). */
class FactExtractorTest {

    private fun facts(text: String) = FactExtractor.extract(text).associate { it.key to it.value }

    @Test
    fun englishFamilyFacts() {
        assertEquals("Ravi", facts("my son is Ravi")["person:son"])
        assertEquals("Priya", facts("Priya is my sister")["person:sister"])
        assertEquals("Priya", facts("my sister's name is Priya")["person:sister"])
    }

    @Test
    fun hindiAndTeluguFamilyFacts() {
        assertEquals("Ravi", facts("mera beta Ravi hai")["person:son"])
        assertEquals("Sita", facts("meri beti ka naam Sita hai")["person:daughter"])
        assertEquals("Ravi Kumar", facts("mere bete ka naam ravi kumar hai")["person:son"])
        assertEquals("Ravi", facts("naa koduku peru Ravi")["person:son"])
        assertEquals("Kamala", facts("mera naam Kamala hai")["profile:name"])
    }

    @Test
    fun notNames() {
        assertNull(facts("my son is coming home")["person:son"])
        assertNull(facts("mera beta aa raha hai")["person:son"])
    }

    @Test
    fun healthFacts() {
        assertTrue(facts("mujhe sugar hai").containsKey("health:diabetes"))
        assertTrue(facts("naaku BP undi").containsKey("health:blood_pressure"))
        assertTrue(facts("I have diabetes").containsKey("health:diabetes"))
        // Buying sugar is not diabetes.
        assertFalse(facts("I am going to buy sugar").containsKey("health:diabetes"))
    }

    @Test
    fun secretsAreNeverExtracted() {
        assertTrue(FactExtractor.extract("remember that my UPI PIN is 4321").isEmpty())
        assertTrue(FactExtractor.isSensitive("OTP 123456"))
        assertFalse(FactExtractor.isSensitive("pin code 400001"))
    }

    @Test
    fun forgetCommands() {
        assertTrue(FactExtractor.isForgetCommand("yeh bhool jao"))
        assertTrue(FactExtractor.isForgetCommand("forget that"))
        assertTrue(FactExtractor.isForgetCommand("mat yaad rakho"))
        assertFalse(FactExtractor.isForgetCommand("forget that my son is Ramesh"))
        assertFalse(FactExtractor.isForgetCommand("order milk"))
    }

    @Test
    fun orderPreferenceFromTask() {
        val p = FactExtractor.orderPreferences("Order Amul milk 1 litre on Blinkit").associate { it.key to it.value }
        assertEquals("Amul 1 L (Blinkit)", p["pref:milk"])
        val two = FactExtractor.orderPreferences("order 2 litre Amul Gold milk and 1 loaf Britannia bread on Zepto")
            .associate { it.key to it.value }
        assertEquals("Amul Gold 2 L (Zepto)", two["pref:milk"])
        assertEquals("Britannia 1 loaf (Zepto)", two["pref:bread"])
        assertTrue(FactExtractor.orderPreferences("order milk").isEmpty())
    }

    @Test
    fun contactUseFromTask() {
        assertEquals("Ravi" to "call", FactExtractor.contactUse("Ravi ko call karo"))
        assertEquals("Son" to "message", FactExtractor.contactUse("beta ko message bhejo"))
        assertNull(FactExtractor.contactUse("read messages from Ravi"))
    }

    @Test
    fun relevantFactsComeFirstAndCoreAlwaysIncluded() {
        val now = System.currentTimeMillis()
        val all = listOf(
            MemoryFact("1", "profile:name", "Kamala", updatedAt = now),
            MemoryFact("2", "person:son", "Ravi", updatedAt = now),
            MemoryFact("3", "pref:milk", "Amul 1 L (Blinkit)", updatedAt = now),
            MemoryFact("4", "health:diabetes", "Diabetes (sugar)", updatedAt = now),
            MemoryFact("5", "note:temple", "Goes to the temple on Tuesday", updatedAt = now),
        )
        val picked = FactExtractor.select(all, "Blinkit se doodh mangao").map { it.key }
        assertTrue("profile:name" in picked)
        assertTrue("person:son" in picked)
        assertTrue("pref:milk" in picked)
        assertFalse("note:temple" in picked)
    }

    @Test
    fun selectRespectsCaps() {
        val many = (1..40).map { MemoryFact("$it", "pref:item$it", "Brand number $it for milk", updatedAt = it.toLong()) }
        val picked = FactExtractor.select(many, "milk", maxFacts = 12, maxChars = 1200)
        assertTrue(picked.size <= 12)
        assertTrue(picked.sumOf { FactExtractor.render(it).length + 3 } <= 1200)
    }
}
