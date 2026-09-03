// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class VariantBottomSheetTest {

    @Test
    fun testAddToCartToolDefinition() {
        val tool = AddToCartTool()
        assertEquals("add_to_cart", tool.getName())
    }
}
