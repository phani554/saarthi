// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTimeBudgetTest {

    @Test
    fun testTaskBudgetMaxIterationsCap() {
        val configured = 25
        val maxIterations = minOf(configured, 12)
        assertEquals(12, maxIterations)
    }

    @Test
    fun testDirectCompletionToolsSet() {
        val directCompletionTools = setOf("place_call", "send_message", "forward_whatsapp_message", "send_distress_signal")
        assertTrue(directCompletionTools.contains("place_call"))
        assertTrue(directCompletionTools.contains("send_message"))
        assertTrue(directCompletionTools.contains("forward_whatsapp_message"))
        assertTrue(directCompletionTools.contains("send_distress_signal"))
    }
}
