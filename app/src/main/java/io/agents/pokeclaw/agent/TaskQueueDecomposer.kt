// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.utils.XLog

/**
 * Atomic Task Decomposition Engine.
 * Intelligently splits compound user tasks into atomic single-intent sub-tasks.
 * Preserves unified message-to-cart pipelines ("read X's message and order on Y") as single atomic sub-tasks.
 */
object TaskQueueDecomposer {

    private const val TAG = "TaskDecomposer"

    private val SPLIT = Regex(
        """(?i),?\s*\b(?:and\s+then|after\s+that|then)\b\s+|,?\s+and\s+(?=(?:send|tell|message|share|inform|whatsapp|text|call|ask)\b)"""
    )

    /**
     * Try to decompose a compound task prompt into atomic sub-tasks.
     * Returns list of atomic task text strings, or empty list if task is single-intent.
     */
    fun decompose(taskPrompt: String): List<String> {
        val clean = taskPrompt.trim()
        if (clean.isBlank()) return emptyList()

        // Only explicit sequencing words split a task. Commas never do: "milk, bread and eggs on blinkit"
        // is one order, not verb-less sub-tasks.
        // "... and send / tell / message / share ..." also starts a new step: a cart plus a message to
        // someone are two jobs, and the message needs the first job's result.
        val parts = clean.split(SPLIT)
            .map { it.trim().trimEnd(',').trim() }
            .filter { it.length > 3 }

        if (parts.size <= 1) return emptyList()

        XLog.i(TAG, "Decomposed compound prompt (len=${clean.length}) into ${parts.size} atomic sub-tasks")
        return parts
    }

    /**
     * Enqueue a decomposed sub-task list into TaskQueueManager with dynamic dependency links.
     */
    fun enqueueSubTasks(parts: List<String>, channel: Channel, baseMessageId: String, parentGoal: String = parts.joinToString(", then ")) {
        parts.forEachIndexed { index, subTaskText ->
            val subMsgId = "${baseMessageId}_sub_$index"
            // Every later step gets the step before it (its result, or that it failed): "send the cart
            // total" is useless without the cart step's total. The old keyword test missed "send"/"share".
            val dependsOnId = if (index > 0) "${baseMessageId}_sub_${index - 1}" else null

            TaskQueueManager.enqueue(
                QueuedTask(
                    channel = channel,
                    taskText = subTaskText,
                    messageId = subMsgId,
                    dependsOnId = dependsOnId,
                    failureBehavior = QueuedTask.FailureBehavior.ADAPT_MESSAGE,
                    parentGoal = parentGoal,
                    stepIndex = index,
                    stepCount = parts.size,
                )
            )
        }
        XLog.i(TAG, "Enqueued ${parts.size} atomic sub-tasks into TaskQueueManager")
    }
}
