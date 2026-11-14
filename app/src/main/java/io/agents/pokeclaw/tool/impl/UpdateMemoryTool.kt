// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import io.agents.pokeclaw.agent.knowledge.FactExtractor
import io.agents.pokeclaw.agent.knowledge.MemoryManager
import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog

/**
 * Memory Update & Correction Tool (`update_memory`).
 * Allows updating or correcting an existing memory entry when it is wrong
 * (e.g. correcting 'finaly year project 1' -> 'Final Year Project').
 */
class UpdateMemoryTool : BaseTool() {

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

        if (MemoryManager.isSensitive(newFact)) {
            // Spoken back to the user, so plain words. Nothing is stored or sent anywhere.
            return ToolResult.success("I don't save PINs, OTPs, passwords or card numbers. Please keep them private.")
        }

        return try {
            // Tier 1 "remember that X" passes the same text twice: that is a new fact, not a correction.
            if (oldFact.equals(newFact, ignoreCase = true)) {
                val saved = MemoryManager.recordFactLocal(newFact, syncCloud = true)
                XLog.i("UpdateMemoryTool", "Remembered ${saved.size} fact(s)")
                return ToolResult.success(
                    if (saved.isEmpty()) "I already know that: $newFact"
                    else "Got it, I will remember: " + saved.joinToString("; ") { FactExtractor.render(it.copy(pending = false)) }
                )
            }

            val updated = MemoryManager.correctFact(oldFact, newFact)
            if (updated != null) {
                XLog.i("UpdateMemoryTool", "Corrected memory fact ${updated.key}")
                ToolResult.success("Successfully updated memory from '$oldFact' to '${FactExtractor.render(updated)}'")
            } else {
                MemoryManager.recordFactLocal(newFact, syncCloud = false)
                // Keep the old behaviour of telling Mem0 Cloud (if configured) about corrections.
                HybridMemoryRepository.recordTurnAsync("user_preference_fact", newFact)
                ToolResult.success("'$oldFact' was not found as a saved memory, so nothing was replaced; '$newFact' was added as a new memory.")
            }
        } catch (e: Exception) {
            XLog.e("UpdateMemoryTool", "Failed to update memory", e)
            ToolResult.error("Failed to update memory: ${e.message}")
        }
    }
}
