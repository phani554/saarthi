// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MessageToCartPipelineTest {

    @Test
    fun testMatchMessageToCartTier1() {
        val prompt = "Read Kaamya's message and order the items on Flipkart Minutes"
        val result = TaskParser.parse(prompt)
        assertNotNull(result)
        assertEquals("message_to_cart", result?.toolName)
        assertEquals("Kaamya", result?.toolParams?.get("contact"))
        assertEquals("Flipkart Minutes", result?.toolParams?.get("store_app"))
    }
}
