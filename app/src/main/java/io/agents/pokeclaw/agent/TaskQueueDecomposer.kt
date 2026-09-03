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

    /**
     * Try to decompose a compound task prompt into atomic sub-tasks.
     * Returns list of atomic task text strings, or empty list if task is single-intent.
     */
    fun decompose(taskPrompt: String): List<String> {
        val clean = taskPrompt.trim()
        if (clean.isBlank()) return emptyList()

        val lower = clean.lowercase()

        // Check if prompt is a unified message-to-cart request
        val isMessageToCartPattern = lower.contains("message") &&
                (lower.contains("order") || lower.contains("buy") || lower.contains("add")) &&
                (lower.contains("on ") || lower.contains("from ") || lower.contains("using "))

        // Split on major sequential clauses (", then ", " then ", " after that ", " and then ")
        val parts = if (isMessageToCartPattern) {
            clean.split(Regex("""(?i),\s*then\s+|\s+then\s+|\s+after\s+that\s+|\s+and\s+then\s+"""))
                .map { it.trim() }
                .filter { it.length > 3 }
        } else {
            clean.split(Regex("""(?i)\s+(?:then|after\s+that|and\s+then|,)\s+"""))
                .map { it.trim() }
                .filter { it.length > 3 }
        }

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
        XLog.i(TAG, "Enqueued ${parts.size} atomic sub-tasks into TaskQueueManager")
    }
}
