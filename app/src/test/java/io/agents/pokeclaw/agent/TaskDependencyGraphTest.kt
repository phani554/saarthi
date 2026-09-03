// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.channel.Channel
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskDependencyGraphTest {

    @Before
    fun setUp() {
        TaskDependencyGraph.clear()
    }

    @Test
    fun testSuccessAdaptation() {
        // Record prerequisite outcome for task 0
        TaskDependencyGraph.recordOutcome("task_0", isSuccess = true, summary = "Amul Taaza 1L Milk added to cart")

        val dependentTask = QueuedTask(
            channel = Channel.LOCAL,
            taskText = "inform Mom that milk has been ordered and will be delivered in a few minutes",
            messageId = "task_1",
            dependsOnId = "task_0"
        )

        val adapted = TaskDependencyGraph.adaptDependentTask(dependentTask)
        assertTrue(adapted.taskText.contains("Milk has been ordered"))
        assertTrue(adapted.taskText.contains("Amul Taaza 1L Milk added to cart"))
        assertTrue(adapted.taskText.contains("Mom"))
    }

    @Test
    fun testFailureAdaptation() {
        // Record prerequisite failure outcome for task 0
        TaskDependencyGraph.recordOutcome("task_0", isSuccess = false, summary = "Amul Taaza 1L Milk is out of stock on Blinkit")

        val dependentTask = QueuedTask(
            channel = Channel.LOCAL,
            taskText = "inform Mom that milk has been ordered and will be delivered in a few minutes",
            messageId = "task_1",
            dependsOnId = "task_0",
            failureBehavior = QueuedTask.FailureBehavior.ADAPT_MESSAGE
        )

        val adapted = TaskDependencyGraph.adaptDependentTask(dependentTask)
        assertTrue(adapted.taskText.contains("Could not order milk"))
        assertTrue(adapted.taskText.contains("Amul Taaza 1L Milk is out of stock on Blinkit"))
    }
}
