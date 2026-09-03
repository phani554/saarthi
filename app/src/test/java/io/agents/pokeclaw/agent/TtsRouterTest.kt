// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TtsRouterTest {

    @Before
    fun setUp() {
        TaskExecutionState.instance.reset()
    }

    @Test
    fun testIdleStateHasNativeTtsDisabled() {
        assertEquals(AgentTaskState.Idle, TaskExecutionState.instance.currentState.value)
        val isExecuting = TaskExecutionState.instance.currentState.value is AgentTaskState.Executing
        assertEquals(false, isExecuting)
    }

    @Test
    fun testExecutingStateHasNativeTtsEnabled() {
        TaskExecutionState.instance.startTask("Test task")
        TaskExecutionState.instance.incrementStep()
        val isExecuting = TaskExecutionState.instance.currentState.value is AgentTaskState.Executing
        assertEquals(true, isExecuting)
    }

    @Test
    fun testCallInProgressStateBlocksAllTts() {
        TaskExecutionState.instance.setState(AgentTaskState.CallInProgress)
        val state = TaskExecutionState.instance.currentState.value
        assertEquals(AgentTaskState.CallInProgress, state)
    }
}
