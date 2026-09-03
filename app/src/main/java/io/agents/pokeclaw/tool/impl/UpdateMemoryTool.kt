// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import io.agents.pokeclaw.agent.knowledge.KBManager
import io.agents.pokeclaw.agent.knowledge.MemoryManager
import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Memory Update & Correction Tool (`update_memory`).
 * Allows updating or correcting an existing memory entry when it is wrong
 * (e.g. correcting 'finaly year project 1' -> 'Final Year Project').
 */
class UpdateMemoryTool : BaseTool() {

    companion object {
        private const val MEMORY_FILE_PATH = "user_preferences.md"
    }

    override fun getName(): String = "update_memory"

    override fun getDisplayName(): String = "Update Memory"

    override fun getDescriptionEN(): String =
        "Update or correct an existing memory entry in user memory (e.g., replace 'finaly year project 1' with 'Final Year Project')."

    override fun getDescriptionCN(): String =
        "更新或修正用户记忆库中的现有条目（例如：将 'finaly year project 1' 替换为 'Final Year Project'）。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("old_fact", "string", "The incorrect or old memory fact to replace (e.g. 'finaly year project 1')", true),
        ToolParameter("new_fact", "string", "The corrected new memory fact (e.g. 'Final Year Project')", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val oldFact = requireString(params, "old_fact").trim()
        val newFact = requireString(params, "new_fact").trim()

        if (oldFact.isBlank() || newFact.isBlank()) {
            return ToolResult.error("Both old_fact and new_fact are required")
        }

        return try {
            val existing = KBManager.read(MEMORY_FILE_PATH).getOrNull().orEmpty()
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

            val updatedContent = if (existing.contains(oldFact, ignoreCase = true)) {
                existing.replace(Regex("""(?i)- \[[^]]+\] .*?\b""" + Pattern.quote(oldFact) + """\b.*"""), "- [$timestamp] $newFact")
            } else {
                existing + "\n- [$timestamp] $newFact\n"
            }

            val fm = mapOf(
                "type" to "user_memory",
                "updated" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            )
            KBManager.write(MEMORY_FILE_PATH, fm, updatedContent)

            // Sync correction to Mem0 Cloud
            runBlocking {
                HybridMemoryRepository.recordFact(newFact)
            }

            // Invalidate in-memory prompt cache
            MemoryManager.recordFact(newFact)

            XLog.i("UpdateMemoryTool", "Successfully updated memory: '$oldFact' -> '$newFact'")
            ToolResult.success("Successfully updated memory from '$oldFact' to '$newFact'")
        } catch (e: Exception) {
            XLog.e("UpdateMemoryTool", "Failed to update memory", e)
            ToolResult.error("Failed to update memory: ${e.message}")
        }
    }
}
