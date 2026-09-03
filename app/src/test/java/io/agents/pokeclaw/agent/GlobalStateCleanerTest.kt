// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalStateCleanerTest {

    @Test
    fun testGlobalStateCleanup() {
        // Enqueue a task
        TaskQueueManager.enqueue(QueuedTask(Channel.LOCAL, "Test task", "msg_1"))
        assertEquals(1, TaskQueueManager.size())

        // Run cleanAll
        try {
            GlobalTaskStateCleaner.cleanAll("Unit test reset")
        } catch (_: Exception) {}

        // Verify task queue cleared and engine state reset
        assertEquals(0, TaskQueueManager.size())
        assertEquals(AgentTaskState.Idle, TaskExecutionState.instance.currentState.value)
    }
}
