// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Tier1MemoryRoutingTest {

    @Test
    fun testMemoryStatementMatchesTier1() {
        val result = TaskParser.parse("remember that my major project is Final Year Project 1")
        assertTrue(result != null)
        assertEquals("update_memory", result?.toolName)
    }

    @Test
    fun testSetMyProjectMatchesTier1() {
        val result = TaskParser.parse("set my project to Final Year Project 1")
        assertTrue(result != null)
        assertEquals("update_memory", result?.toolName)
    }
}
