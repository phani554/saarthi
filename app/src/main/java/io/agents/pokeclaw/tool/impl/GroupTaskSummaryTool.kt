// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.ContactListUiUtils
import io.agents.pokeclaw.utils.ContactMatchUtils
import io.agents.pokeclaw.utils.XLog
import java.util.LinkedHashSet

/**
 * Tool to extract actionable tasks, TODOs, and gists from a monitored WhatsApp group chat.
 */
class GroupTaskSummaryTool : BaseTool() {

    companion object {
        private const val TAG = "GroupTaskSummaryTool"
        private val ACTIONABLE_KEYWORDS = arrayOf(
            "todo", "task", "need to", "please", "assign", "complete",
            "by tomorrow", "due", "order", "check", "fix", "send", "update",
            "remember", "bring", "buy", "meet", "call"
        )
    }

    override fun getName(): String = "get_group_task_summary"

    override fun getDisplayName(): String = "Get Group Task Summary"

    override fun getDescriptionEN(): String =
        "Opens a WhatsApp or messaging group chat, extracts actionable tasks, assignments, and TODOs from recent messages, and returns a concise gist summary."

    override fun getDescriptionCN(): String =
        "打开 WhatsApp 或消息群组，从最近的消息中提取可执行的任务、分工和 TODO，并返回简明摘要。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("group_name", "string", "Name of the group chat to inspect (e.g. 'Project Team', 'Family')", true),
        ToolParameter("limit", "integer", "Optional: maximum number of recent messages to analyze (default 20)", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService()
            ?: return ToolResult.error("Accessibility service is not running")

        val groupName = requireString(params, "group_name").trim()

        XLog.i(TAG, "execute: inspecting group chat '$groupName'")

        // 1. Open WhatsApp
        val opened = OpenAppTool.openAppWithInterceptHandling(service, "com.whatsapp")
        if (!opened) {
            return ToolResult.error("Failed to open WhatsApp")
        }
        try { Thread.sleep(2000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }

        // 2. Prepare contact lookup & open target group chat
        if (!ContactListUiUtils.prepareForContactLookup(service, "com.whatsapp", 3, 1000)) {
            return ToolResult.error("Could not reach searchable WhatsApp chat list")
        }

        val normalized = ContactMatchUtils.buildNormalizedAliases(groupName)
        val digit = ContactMatchUtils.buildDigitAliases(groupName)
        val chatOpened = ContactListUiUtils.searchOrScrollAndFindAndClick(service, groupName, normalized, digit, 8, 800)
        if (!chatOpened) {
            return ToolResult.error("Could not find or open group chat '$groupName'")
        }

        try { Thread.sleep(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }

        // 3. Inspect active chat screen for messages
        val root = service.rootInActiveWindow
            ?: return ToolResult.error("Failed to read group chat screen")

        val messages = mutableListOf<String>()
        collectChatMessages(root, messages)

        if (messages.isEmpty()) {
            return ToolResult.success("Opened chat '$groupName', but no recent text messages were visible on screen.")
        }

        // Run AI single-shot summarization on collected chat transcript
        val transcript = messages.joinToString("\n").take(3000)
        val systemPrompt = "You are an intelligent WhatsApp Chat Summarizer."
        val prompt = """The following are recent messages extracted from the WhatsApp chat '$groupName':
$transcript

Task: Provide a concise, clear 3-bullet-point summary of the main topics discussed, decisions made, and pending tasks or action items in this chat.
Keep the summary plain, clear, and direct. Output ONLY the 3 bullet points."""

        val aiSummary = try {
            LlmSessionManager.singleShotCloud(systemPrompt, prompt, 0.2)
                ?: LlmSessionManager.singleShotLocal(systemPrompt, prompt, 0.2)
        } catch (e: Exception) {
            null
        }

        if (!aiSummary.isNullOrBlank()) {
            return ToolResult.success("### WhatsApp Chat Summary for '$groupName':\n\n$aiSummary")
        }

        // Fallback: Keyword extraction
        val actionItems = mutableListOf<String>()
        for (msg in messages) {
            val lower = msg.lowercase()
            for (kw in ACTIONABLE_KEYWORDS) {
                if (lower.contains(kw)) {
                    actionItems.add(msg)
                    break
                }
            }
        }

        val sb = StringBuilder()
        sb.append("### Gist of Tasks for Chat: '").append(groupName).append("'\n\n")
        sb.append("**Total Recent Messages Inspected**: ").append(messages.size).append("\n\n")

        if (actionItems.isNotEmpty()) {
            sb.append("**Actionable Tasks & Assignments Identified**:\n")
            actionItems.distinct().forEach { item ->
                sb.append("- ").append(item).append("\n")
            }
        } else {
            sb.append("**Key Message Highlights**:\n")
            messages.takeLast(5).distinct().forEach { item ->
                sb.append("- ").append(item).append("\n")
            }
        }

        return ToolResult.success(sb.toString())
    }

    private fun collectChatMessages(node: AccessibilityNodeInfo?, results: MutableList<String>) {
        if (node == null || !node.isVisibleToUser) return

        val text = node.text?.toString()?.trim().orEmpty()
        val className = node.className?.toString().orEmpty()

        if (text.length > 3 && !isSystemUiText(text) && (className.contains("TextView") || className.contains("View"))) {
            if (!results.contains(text)) {
                results.add(text)
            }
        }

        for (i in 0 until node.childCount) {
            collectChatMessages(node.getChild(i), results)
        }
    }

    private fun isSystemUiText(text: String): Boolean {
        val lower = text.lowercase()
        return lower == "whatsapp" || lower == "chats" || lower == "calls" || lower == "updates" ||
                lower.matches(Regex("""\d{1,2}:\d{2}\s*(?:am|pm)?""")) || lower == "type a message"
    }
}
