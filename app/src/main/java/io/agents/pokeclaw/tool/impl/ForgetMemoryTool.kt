// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import io.agents.pokeclaw.agent.knowledge.MemoryManager
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog

/**
 * Forget Memory Tool (`forget_memory`): "yeh bhool jao" / "forget that" / "mat yaad rakho".
 * Without `what`, undoes the last thing Saathi learned. With `what` ("milk", "son"), removes that fact.
 */
class ForgetMemoryTool : BaseTool() {

    override fun getName(): String = "forget_memory"

    override fun getDisplayName(): String = "Forget Memory"

    override fun getDescriptionEN(): String =
        "Forget something saved in user memory. Use when the user says 'forget that', 'yeh bhool jao' or 'mat yaad rakho'. " +
            "Leave 'what' empty to forget the last thing learned, or name it (e.g. 'milk', 'son')."

    override fun getDescriptionCN(): String =
        "删除用户记忆中的内容。用户说“忘掉这个”时使用。'what' 为空时删除最近学到的内容，或指定要删除的内容（如 'milk'）。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("what", "string", "Optional: what to forget (e.g. 'milk', 'son'). Empty = the last thing learned.", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val what = optionalString(params, "what", "").trim()
        return try {
            val forgotten = if (what.isBlank() || what.equals("that", true) || what.equals("last", true)) {
                MemoryManager.forgetLastOnce()
            } else {
                MemoryManager.forgetMatching(what)
            }
            XLog.i("ForgetMemoryTool", "forget_memory: ${if (forgotten == null) "nothing" else "done"}")
            ToolResult.success(
                if (forgotten == null) "There was nothing new to forget."
                else "Okay, I have forgotten: $forgotten"
            )
        } catch (e: Exception) {
            XLog.e("ForgetMemoryTool", "Failed to forget memory", e)
            ToolResult.error("Failed to forget memory: ${e.message}")
        }
    }
}
