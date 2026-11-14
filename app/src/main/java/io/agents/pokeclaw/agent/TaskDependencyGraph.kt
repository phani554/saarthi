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
        val outcome = task.dependsOnId?.let { taskResultStore[it] }
        if (outcome != null && !outcome.isSuccess && task.failureBehavior == QueuedTask.FailureBehavior.CANCEL_DEPENDENTS) {
            XLog.w(TAG, "Prerequisite '${task.dependsOnId}' failed. Cancelling dependent task '${task.messageId}'")
            return task.copy(taskText = "") // Empty text signals cancelled task
        }
        if (task.stepCount <= 1 && outcome == null) return task
        // The step's own words stay the task text (routing and playbooks read it); the agent also gets the
        // whole request and what the step before it found, so "send the cart total" knows the total.
        val context = buildContext(task, outcome)
        XLog.i(TAG, "Step ${task.stepIndex + 1}/${task.stepCount} '${task.messageId}' gets context (prereq=${outcome?.isSuccess})")
        return task.copy(agentPromptOverride = TaskPromptEnvelope.build(emptyList(), task.taskText, context))
    }

    internal fun buildContext(task: QueuedTask, outcome: TaskOutcome?): String = buildString {
        if (task.parentGoal.isNotBlank()) {
            append("Her whole request: \"").append(task.parentGoal.trim()).append("\"\n")
        }
        append("This is step ").append(task.stepIndex + 1).append(" of ").append(task.stepCount)
            .append(". Do only this step now; the other steps run separately.\n")
        when {
            outcome == null -> Unit
            outcome.isSuccess -> {
                append("The previous step finished: ").append(outcome.summary.trim().take(600)).append("\n")
                append("Use its result (amounts, items, names) directly; do not open apps again to re-check it unless it is missing.")
            }
            else -> {
                append("The previous step FAILED: ").append(outcome.summary.trim().take(300)).append("\n")
                append("Do not claim it succeeded. If this step tells someone about it, tell them truthfully that it could not be done.")
            }
        }
    }

    fun clear() {
        taskResultStore.clear()
    }
}
