// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import io.agents.pokeclaw.agent.knowledge.MemoryManager
import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.runBlocking

/**
 * Memory Inspection Tool (`inspect_memory`).
 * Allows inspecting and listing all currently stored user facts, contact nicknames,
 * project names, and preferences from memory (Mem0 Cloud + local vault).
 */
class InspectMemoryTool : BaseTool() {

    override fun getName(): String = "inspect_memory"

    override fun getDisplayName(): String = "Inspect Memory"

    override fun getDescriptionEN(): String =
        "Inspect and list all currently stored user facts, contact nicknames, project group names, and preferences in memory."

    override fun getDescriptionCN(): String =
        "查看并列出当前在记忆库中存储的所有用户事实、联系人昵称、项目组名称和偏好。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("query", "string", "Optional search string to filter memories (e.g. 'project', 'mom', 'milk', 'all')", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val query = optionalString(params, "query", "all").trim()

        return try {
            val (mem0Results, source) = runBlocking {
                HybridMemoryRepository.searchMemories(query)
            }

            val localSection = MemoryManager.getMemoryPromptSection()

            val combined = buildString {
                append("Active Memory Source: ").append(source.name).append("\n\n")
                if (mem0Results.isNotBlank()) {
                    append(mem0Results).append("\n")
                }
                if (localSection.isNotBlank()) {
                    append(localSection)
                }
                if (mem0Results.isBlank() && localSection.isBlank()) {
                    append("No memories found matching query '$query'.")
                }
            }

            XLog.i("InspectMemoryTool", "Inspected memory for query '$query': ${combined.length} chars retrieved")
            ToolResult.success(combined)
        } catch (e: Exception) {
            XLog.e("InspectMemoryTool", "Failed to inspect memory", e)
            ToolResult.error("Failed to inspect memory: ${e.message}")
        }
    }
}
