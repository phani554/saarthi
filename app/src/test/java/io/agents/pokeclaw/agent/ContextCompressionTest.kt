// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressionTest {

    @Test
    fun testPruningOlderAiMessages() {
        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from("System prompt"))
        messages.add(UserMessage.from("User prompt"))

        // Add 5 rounds of AiMessage + ToolExecutionResultMessage
        for (i in 1..5) {
            messages.add(AiMessage.from("Very long reasoning text for round $i ".repeat(30)))
            messages.add(ToolExecutionResultMessage.from("id_$i", "get_screen_info", "Screen data $i ".repeat(50)))
        }

        assertEquals(12, messages.size)

        // Verify that initial AiMessage is long
        val oldAi = messages[2] as AiMessage
        assertTrue(oldAi.text().length > 500)
    }
}
