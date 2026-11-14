// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TaskParserTest {

    @Test
    fun testMatchWhatsAppVideoCall() {
        val result = TaskParser.parse("open a whatsapp video call to kamya gupta")
        assertNotNull(result)
        assertEquals("place_call", result?.toolName)
        assertEquals("kamya gupta", result?.toolParams?.get("contact"))
        assertEquals("whatsapp_video", result?.toolParams?.get("call_type"))
    }

    @Test
    fun testMatchSendMessageCompound() {
        // Compound/ambiguous requests have no explicit separator, so they go to the LLM (parser returns null).
        assertNull(TaskParser.parse("send hi to mom for dinner and ask her what I should bring for dinner"))
        // Explicit form ("send a message to <contact>: <text>") is still handled by the fast path.
        val result = TaskParser.parse("send a message to mom: what should I bring for dinner")
        assertNotNull(result)
        assertEquals("send_message", result?.toolName)
        assertEquals("mom", result?.toolParams?.get("contact"))
        assertEquals("what should I bring for dinner", result?.toolParams?.get("message"))
    }

    @Test
    fun testMatchOpenAppNotTriggeredOnActionVerbs() {
        val result = TaskParser.parse("open a whatsapp video call to kamya gupta")
        assertNotNull(result)
        assertEquals("place_call", result?.toolName) // Not open_app!
    }
}
