// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskQueueDecomposerTest {

    @Test
    fun testDecomposeCompoundTask() {
        val prompt = "Order Amul Taaza 1L Milk on Blinkit, then send a message to Mom saying I bought milk"
        val parts = TaskQueueDecomposer.decompose(prompt)
        assertEquals(2, parts.size)
        assertEquals("Order Amul Taaza 1L Milk on Blinkit", parts[0])
        assertEquals("send a message to Mom saying I bought milk", parts[1])
    }

    @Test
    fun testDecomposeMessageToCartWithThenCall() {
        val prompt = "Read Kaamya's message and order all items on Flipkart Minutes, then make a video call to Kaamya"
        val parts = TaskQueueDecomposer.decompose(prompt)
        assertEquals(2, parts.size)
        assertEquals("Read Kaamya's message and order all items on Flipkart Minutes", parts[0])
        assertEquals("make a video call to Kaamya", parts[1])
    }

    @Test
    fun testDecomposeSingleTaskReturnsEmpty() {
        val prompt = "Order Amul Taaza 1L Milk on Blinkit"
        val parts = TaskQueueDecomposer.decompose(prompt)
        assertTrue(parts.isEmpty())
    }
}
