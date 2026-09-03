// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.service.ClawAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class AmazonAutomationDriverTest {

    @Test
    fun testAmazonDriverDefinition() {
        assertNotNull(AmazonAutomationDriver)
    }

    @Test
    fun testSearchOnlyWithBlankQueryReturnsError() {
        // Mock-free validation that blank inputs are rejected cleanly
        val result = AmazonAutomationDriver.searchOnly(
            service = mockService(),
            query = "   "
        )
        assertFalse(result.isSuccess)
        assertEquals("Query is blank", result.error)
    }

    @Test
    fun testSearchAndAddToCartWithBlankQueryReturnsError() {
        val result = AmazonAutomationDriver.searchAndAddToCart(
            service = mockService(),
            query = ""
        )
        assertFalse(result.isSuccess)
        assertEquals("Query is blank", result.error)
    }

    private fun mockService(): ClawAccessibilityService {
        return object : ClawAccessibilityService() {
            override fun getRootInActiveWindow(): AccessibilityNodeInfo? = null
        }
    }
}
