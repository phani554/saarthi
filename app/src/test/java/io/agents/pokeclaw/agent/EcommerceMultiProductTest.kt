// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class EcommerceMultiProductTest {

    @Test
    fun testExtractMultiProductsList() {
        val prompt = "Order Amul Taaza 1L Milk, Pepsi 250ml, and Brown Bread from Blinkit"
        val items = EcommerceAutomationHelper.extractMultiProducts(prompt)
        assertEquals(3, items.size)
        assertEquals("Amul Taaza 1L Milk", items[0])
        assertEquals("Pepsi 250ml", items[1])
        assertEquals("Brown Bread", items[2])
    }
}
