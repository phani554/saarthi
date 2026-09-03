// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TaskQueueManagerTest {

    @Before
    fun setUp() {
        TaskQueueManager.clear()
    }

    @Test
    fun testEnqueueAndSize() {
        assertEquals(0, TaskQueueManager.size())
        TaskQueueManager.enqueue(QueuedTask(Channel.LOCAL, "Task 1", "msg_1"))
        TaskQueueManager.enqueue(QueuedTask(Channel.LOCAL, "Task 2", "msg_2"))
        assertEquals(2, TaskQueueManager.size())
    }

    @Test
    fun testClearQueue() {
        TaskQueueManager.enqueue(QueuedTask(Channel.LOCAL, "Task 1", "msg_1"))
        TaskQueueManager.clear()
        assertEquals(0, TaskQueueManager.size())
    }
}
