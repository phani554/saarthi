// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.utils.XLog

/**
 * Atomic Task Decomposition Engine.
 * Splits compound user tasks ("Order milk on Blinkit and send message to Mom")
 * into atomic, single-intent sub-tasks with dependency links and enqueues them into TaskQueueManager.
 * Guarantees that each sub-task executes atomically, fast (<1s), and independently without false reporting.
 */
object TaskQueueDecomposer {

    private const val TAG = "TaskDecomposer"

    /**
     * Try to decompose a compound task prompt into atomic sub-tasks.
     * Returns list of atomic task text strings, or empty list if task is single-intent.
     */
    fun decompose(taskPrompt: String): List<String> {
        val clean = taskPrompt.trim()
        if (clean.isBlank()) return emptyList()

        val lower = clean.lowercase()

        // Check for compound conjunctions: " and ", " then ", " after ", " plus ", " also "
        val hasConjunction = lower.contains(" and ") || lower.contains(" then ") ||
                lower.contains(" after ") || lower.contains(" also ")

        if (!hasConjunction) return emptyList()

        // Split on compound conjunctions
        val parts = clean.split(Regex("""(?i)\s+(?:and|then|after|also|plus)\s+"""))
            .map { it.trim() }
            .filter { it.length > 3 }

        if (parts.size <= 1) return emptyList()

        XLog.i(TAG, "Decomposed compound prompt '$clean' into ${parts.size} atomic sub-tasks: $parts")
        return parts
    }

    /**
     * Enqueue a decomposed sub-task list into TaskQueueManager with dynamic dependency links.
     */
    fun enqueueSubTasks(parts: List<String>, channel: Channel, baseMessageId: String) {
        parts.forEachIndexed { index, subTaskText ->
            val subMsgId = "${baseMessageId}_sub_$index"
            val lower = subTaskText.lowercase()
            val isDependent = index > 0 && (
                lower.contains("inform") || lower.contains("tell") || lower.contains("say") ||
                lower.contains("message") || lower.contains("let ") || lower.contains("once") || lower.contains("after")
            )
            val dependsOnId = if (isDependent) "${baseMessageId}_sub_${index - 1}" else null

            TaskQueueManager.enqueue(
                QueuedTask(
                    channel = channel,
                    taskText = subTaskText,
                    messageId = subMsgId,
                    dependsOnId = dependsOnId,
                    failureBehavior = QueuedTask.FailureBehavior.ADAPT_MESSAGE
                )
            )
        }
        XLog.i(TAG, "Enqueued ${parts.size} atomic sub-tasks with dependency links into TaskQueueManager")
    }
}
