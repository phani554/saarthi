// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskStateMachineTest {

    @Before
    fun setUp() {
        TaskExecutionState.instance.reset()
    }

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(AgentTaskState.Idle, TaskExecutionState.instance.currentState.value)
    }

    @Test
    fun testStartTaskTransitionsToPlanning() {
        TaskExecutionState.instance.startTask("Order milk on Blinkit")
        val state = TaskExecutionState.instance.currentState.value
        assertTrue("Expected Planning state but got $state", state is AgentTaskState.Planning)
        assertEquals("Order milk on Blinkit", (state as AgentTaskState.Planning).task)
    }

    @Test
    fun testIncrementStepTransitionsToExecuting() {
        TaskExecutionState.instance.startTask("Send message to Mom")
        TaskExecutionState.instance.incrementStep()
        val state = TaskExecutionState.instance.currentState.value
        assertTrue("Expected Executing state but got $state", state is AgentTaskState.Executing)
        assertEquals(1, (state as AgentTaskState.Executing).stepIndex)
    }

    @Test
    fun testCallInProgressTransitionsState() {
        TaskExecutionState.instance.startTask("Call Mom")
        TaskExecutionState.instance.setState(AgentTaskState.CallInProgress)
        assertEquals(AgentTaskState.CallInProgress, TaskExecutionState.instance.currentState.value)
    }

    @Test
    fun testAbandonTaskTransitionsToAborted() {
        TaskExecutionState.instance.startTask("Order milk")
        TaskExecutionState.instance.abandonTask("User cancelled")
        val state = TaskExecutionState.instance.currentState.value
        assertTrue("Expected Aborted state but got $state", state is AgentTaskState.Aborted)
        assertEquals("User cancelled", (state as AgentTaskState.Aborted).reason)
    }
}
