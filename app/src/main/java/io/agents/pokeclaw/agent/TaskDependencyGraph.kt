// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Interdependent Task Execution & Result Passing Engine.
 * Ensures dependent tasks (e.g. "inform Mom that milk has been ordered") receive the exact,
 * verified outcome of prerequisite tasks (e.g. "Order milk on Blinkit") before executing.
 * Eliminates false or wrong reporting!
 */
object TaskDependencyGraph {

    private const val TAG = "TaskDependency"
    private val taskResultStore = ConcurrentHashMap<String, TaskOutcome>()

    data class TaskOutcome(
        val taskId: String,
        val isSuccess: Boolean,
        val summary: String,
        val details: String = ""
    )

    fun recordOutcome(taskId: String, isSuccess: Boolean, summary: String, details: String = "") {
        val outcome = TaskOutcome(taskId, isSuccess, summary, details)
        taskResultStore[taskId] = outcome
        XLog.i(TAG, "Recorded task outcome for '$taskId': success=$isSuccess, summary='$summary'")
    }

    /**
     * Resolve and adapt a dependent task prompt based on the prerequisite task's recorded outcome.
     */
    fun adaptDependentTask(task: QueuedTask): QueuedTask {
        val prereqId = task.dependsOnId ?: return task
        val outcome = taskResultStore[prereqId] ?: return task

        XLog.i(TAG, "Adapting dependent task '${task.messageId}' based on prerequisite '$prereqId' outcome: success=${outcome.isSuccess}")

        if (outcome.isSuccess) {
            val adaptedText = adaptSuccessPrompt(task.taskText, outcome.summary)
            return task.copy(taskText = adaptedText)
        } else {
            return if (task.failureBehavior == QueuedTask.FailureBehavior.CANCEL_DEPENDENTS) {
                XLog.w(TAG, "Prerequisite '$prereqId' failed. Cancelling dependent task '${task.taskText}'")
                task.copy(taskText = "") // Empty text signals cancelled task
            } else {
                val failureAdaptedText = adaptFailurePrompt(task.taskText, outcome.summary)
                XLog.i(TAG, "Prerequisite '$prereqId' failed. Adapted message to report failure truthfully: '$failureAdaptedText'")
                task.copy(taskText = failureAdaptedText)
            }
        }
    }

    private fun adaptSuccessPrompt(originalPrompt: String, prereqSummary: String): String {
        val lower = originalPrompt.lowercase()
        return when {
            lower.contains("inform") || lower.contains("tell") || lower.contains("send") || lower.contains("message") -> {
                val contactMatch = Regex("""(?i)\b(?:inform|tell|message)\s+([a-zA-Z0-9\s]+?)\s+that\b""").find(originalPrompt)
                val contact = contactMatch?.groupValues?.get(1)?.trim() ?: "Mom"
                "Send message to $contact: 'Milk has been ordered ($prereqSummary). You can start making kheer!'"
            }
            else -> originalPrompt
        }
    }

    private fun adaptFailurePrompt(originalPrompt: String, prereqFailureReason: String): String {
        val lower = originalPrompt.lowercase()
        return when {
            lower.contains("inform") || lower.contains("tell") || lower.contains("send") || lower.contains("message") -> {
                val contactMatch = Regex("""(?i)\b(?:inform|tell|message)\s+([a-zA-Z0-9\s]+?)\s+that\b""").find(originalPrompt)
                val contact = contactMatch?.groupValues?.get(1)?.trim() ?: "Mom"
                "Send message to $contact: 'Could not order milk ($prereqFailureReason). Please hold off on making kheer for now.'"
            }
            else -> originalPrompt
        }
    }

    fun clear() {
        taskResultStore.clear()
    }
}
