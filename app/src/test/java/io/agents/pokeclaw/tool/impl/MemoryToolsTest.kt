// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MemoryToolsTest {

    @Test
    fun testInspectMemoryToolDefinition() {
        val inspect = InspectMemoryTool()
        assertEquals("inspect_memory", inspect.getName())
        assertNotNull(inspect.getDisplayName())
        assertEquals(1, inspect.getParameters().size)
    }

    @Test
    fun testUpdateMemoryToolDefinition() {
        val update = UpdateMemoryTool()
        assertEquals("update_memory", update.getName())
        assertNotNull(update.getDisplayName())
        assertEquals(2, update.getParameters().size)
    }
}
