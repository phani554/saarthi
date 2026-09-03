// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import io.agents.pokeclaw.agent.QueuedTask
import io.agents.pokeclaw.agent.TaskQueueManager
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog

/**
 * Mid-Execution Sub-Task Queueing Tool.
 * Allows the agent to anticipate an upcoming complex sub-task or follow-up action mid-execution
 * and enqueue it into TaskQueueManager, keeping the current task loop atomic and fast (<1s).
 */
class EnqueueSubtaskTool : BaseTool() {

    override fun getName(): String = "enqueue_subtask"

    override fun getDisplayName(): String = "Enqueue Sub-Task"

    override fun getDescriptionEN(): String =
        "Anticipates an upcoming complex sub-task or follow-up action and enqueues it to execute atomically next."

    override fun getDescriptionCN(): String =
        "预判接下来的复杂子任务或后续操作，并入队以在下一个独立任务中原子化执行。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("task_text", "string", "The clean instruction text for the sub-task to enqueue", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val subTaskText = requireString(params, "task_text").trim()
        if (subTaskText.isBlank()) return ToolResult.error("task_text is required")

        val subMsgId = "subtask_" + System.currentTimeMillis()
        TaskQueueManager.enqueue(
            QueuedTask(
                channel = Channel.LOCAL,
                taskText = subTaskText,
                messageId = subMsgId
            )
        )

        XLog.i("EnqueueSubtaskTool", "Enqueued sub-task mid-execution: '$subTaskText'")
        return ToolResult.success("Successfully enqueued sub-task '$subTaskText' for atomic execution")
    }
}
