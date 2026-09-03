// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReadMessageItemsToolTest {

    @Test
    fun testReadMessageItemsToolDefinition() {
        val tool = ReadMessageItemsTool()
        assertEquals("read_message_items", tool.getName())
        assertNotNull(tool.getDescriptionEN())
        assertEquals(1, tool.getParameters().size)
    }
}
