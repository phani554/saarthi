// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class ChainOfThoughtLearningsTest {

    @Test
    fun testCoTReasoningPromptGeneration() {
        val prompt = ChainOfThoughtLearnings.getReasoningPrompt(
            ChainOfThoughtLearnings.IssueType.SUB_SCREEN_OVERLAY,
            "Pop-up dialog detected"
        )
        assertTrue(prompt.contains("SUB_SCREEN_OVERLAY"))
        assertTrue(prompt.contains("Pop-up dialog detected"))
        assertTrue(prompt.contains("Press system_key"))
    }

    @Test
    fun testCoTUnresponsiveButtonPrompt() {
        val prompt = ChainOfThoughtLearnings.getReasoningPrompt(
            ChainOfThoughtLearnings.IssueType.UNRESPONSIVE_BUTTON,
            "Tapped node n3"
        )
        assertTrue(prompt.contains("UNRESPONSIVE_BUTTON"))
        assertTrue(prompt.contains("Tapped node n3"))
    }
}
