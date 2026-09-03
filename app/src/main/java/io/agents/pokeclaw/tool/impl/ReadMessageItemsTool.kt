// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.ContactListUiUtils
import io.agents.pokeclaw.utils.ContactMatchUtils
import io.agents.pokeclaw.utils.XLog

/**
 * Direct Tier 1 Read Message Items Tool (`read_message_items`).
 * Opens WhatsApp, navigates to contact chat, allows a 1.0s UI settle pause for full chat text rendering,
 * reads the latest message text accurately, closes WhatsApp, and returns the clean extracted text.
 */
class ReadMessageItemsTool : BaseTool() {

    override fun getName(): String = "read_message_items"

    override fun getDisplayName(): String = "Read Message Items"

    override fun getDescriptionEN(): String =
        "Reads latest message text accurately from a WhatsApp contact after allowing chatroom UI to render."

    override fun getDescriptionCN(): String =
        "在允许聊天页面 UI 渲染后，准确读取 WhatsApp 联系人的最新消息文本。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("contact", "string", "Contact name to read message from (e.g. 'Kaamya')", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService()
            ?: return ToolResult.error("Accessibility service is not running")

        val contactName = requireString(params, "contact").trim()
        if (contactName.isBlank()) return ToolResult.error("contact parameter is required")

        XLog.i("ReadMessageItemsTool", "Executing direct Tier 1 read_message_items for contact '$contactName'")

        val opened = ContactListUiUtils.prepareForContactLookup(service, "com.whatsapp", 3, 600L)
        if (!opened) return ToolResult.error("Could not open WhatsApp chat list")

        val normalized = ContactMatchUtils.buildNormalizedAliases(contactName)
        val digits = ContactMatchUtils.buildDigitAliases(contactName)
        val found = ContactListUiUtils.searchOrScrollAndFindAndClick(service, contactName, normalized, digits, 6, 400L)

        if (!found) return ToolResult.error("Could not find contact '$contactName' in WhatsApp")

        // 1.0s UI Settle Pause for WhatsApp chatroom screen to open and render message TextViews
        XLog.i("ReadMessageItemsTool", "Pausing 1.0s for WhatsApp chatroom screen to fully render message text")
        try { Thread.sleep(1000L) } catch (_: Exception) {}

        val root = service.rootInActiveWindow ?: return ToolResult.error("Screen unavailable after chat open")
        val messageText = extractLatestChatMessage(root)

        // Close WhatsApp completely after reading message
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        try { Thread.sleep(300L) } catch (_: Exception) {}
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

        if (messageText.isBlank()) return ToolResult.error("No message text found in chat with '$contactName'")

        XLog.i("ReadMessageItemsTool", "Successfully extracted message text from '$contactName': '$messageText'")
        return ToolResult.success(messageText)
    }

    private fun extractLatestChatMessage(root: AccessibilityNodeInfo): String {
        val messages = mutableListOf<String>()
        collectMessageTextNodes(root, messages)
        return messages.lastOrNull().orEmpty()
    }

    private fun collectMessageTextNodes(node: AccessibilityNodeInfo?, results: MutableList<String>) {
        if (node == null || !node.isVisibleToUser) return
        val resId = node.viewIdResourceName.orEmpty().lowercase()
        val text = node.text?.toString().orEmpty().trim()
        if (resId.contains("message_text") || resId.contains("text_content") || (node.className != null && node.className.toString().contains("TextView") && text.length > 3)) {
            if (!text.contains("AM") && !text.contains("PM") && !text.contains("Yesterday") && !text.contains("Today")) {
                results.add(text)
            }
        }
        for (i in 0 until node.childCount) {
            collectMessageTextNodes(node.getChild(i), results)
        }
    }
}
