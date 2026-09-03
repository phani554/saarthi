// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.TaskOrchestrator
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.utils.XLog
import java.util.ArrayDeque

data class QueuedTask(
    val channel: Channel,
    val taskText: String,
    val messageId: String,
    val agentPromptOverride: String? = null,
    val dependsOnId: String? = null,
    val failureBehavior: FailureBehavior = FailureBehavior.ADAPT_MESSAGE
) {
    enum class FailureBehavior {
        CANCEL_DEPENDENTS,
        ADAPT_MESSAGE
    }
}

/**
 * Thread-safe FIFO Task Queue Manager.
 * Features dynamic task dependency adaptation to prevent false reporting on dependent sub-tasks.
 */
object TaskQueueManager {

    private const val TAG = "TaskQueueManager"
    private val queue = ArrayDeque<QueuedTask>()
    private val lock = Any()

    fun enqueue(task: QueuedTask) {
        synchronized(lock) {
            queue.addLast(task)
            XLog.i(TAG, "Task enqueued: '${task.taskText}' (dependsOn=${task.dependsOnId}, queue size=${queue.size})")
        }
    }

    fun processNext(orchestrator: TaskOrchestrator) {
        synchronized(lock) {
            if (queue.isEmpty()) return
            if (orchestrator.isTaskRunning()) return

            val rawTask = queue.removeFirst()
            val nextTask = TaskDependencyGraph.adaptDependentTask(rawTask)

            if (nextTask.taskText.isBlank()) {
                XLog.w(TAG, "Skipping cancelled dependent task '${rawTask.messageId}'")
                processNext(orchestrator)
                return
            }

            XLog.i(TAG, "Processing next queued task: '${nextTask.taskText}' (dependsOn=${nextTask.dependsOnId})")
            orchestrator.startNewTask(
                channel = nextTask.channel,
                task = nextTask.taskText,
                messageID = nextTask.messageId,
                agentPromptOverride = nextTask.agentPromptOverride
            )
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
            TaskDependencyGraph.clear()
            XLog.i(TAG, "Task queue and dependency graph cleared")
        }
    }

    fun size(): Int = synchronized(lock) { queue.size }
}
