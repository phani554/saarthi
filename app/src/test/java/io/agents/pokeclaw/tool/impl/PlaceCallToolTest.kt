// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlaceCallToolTest {

    @Test
    fun testToolNameAndParameters() {
        val tool = PlaceCallTool()
        assertEquals("place_call", tool.getName())
        assertNotNull(tool.getDescriptionEN())
        assertEquals(2, tool.getParameters().size)
    }
}
