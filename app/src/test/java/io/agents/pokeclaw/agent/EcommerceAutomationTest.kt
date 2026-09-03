// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class EcommerceAutomationTest {

    @Test
    fun testExtractMultiProducts() {
        val prompt = "Order 1L amul milk, pepsi 250ml, and brown bread from Blinkit"
        val products = EcommerceAutomationHelper.extractMultiProducts(prompt)
        assertEquals(3, products.size)
        assertEquals("1L amul milk", products[0])
        assertEquals("pepsi 250ml", products[1])
        assertEquals("brown bread", products[2])
    }

    @Test
    fun testSingleProductExtractReturnsEmpty() {
        val prompt = "Order 1L amul milk from Blinkit"
        val products = EcommerceAutomationHelper.extractMultiProducts(prompt)
        assertEquals(0, products.size)
    }
}
