// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.tool.impl.SendMessageTool
import io.agents.pokeclaw.utils.ContactListUiUtils
import io.agents.pokeclaw.utils.ContactMatchUtils
import io.agents.pokeclaw.utils.XLog

/**
 * End-To-End Direct Message-To-Cart Pipeline.
 * Handles "Read message from X and order items on Y" in 1 fast deterministic pass:
 * 1. Opens WhatsApp, navigates to contact chat, allows 1.0s UI settle pause for full chat text rendering.
 * 2. Extracts product item list using regex parser.
 * 3. Navigates back from WhatsApp cleanly before opening target store.
 * 4. Executes all item additions in < 0.2s per item without multi-app oscillation or LLM delays!
 * 5. Optionally sends 1-line clean confirmation back to contact.
 */
object MessageToCartPipeline {

    private const val TAG = "MessageToCartPipeline"

    fun execute(service: ClawAccessibilityService, contactName: String, storeApp: String): ToolResult {
        XLog.i(TAG, "Starting MessageToCartPipeline for contact '$contactName' on store '$storeApp'")

        // Step 1: Open WhatsApp and navigate to contact chat
        val opened = ContactListUiUtils.prepareForContactLookup(service, "com.whatsapp", 3, 600L)
        if (!opened) return ToolResult.error("Could not open WhatsApp chat list")

        val normalized = ContactMatchUtils.buildNormalizedAliases(contactName)
        val digits = ContactMatchUtils.buildDigitAliases(contactName)
        val found = ContactListUiUtils.searchOrScrollAndFindAndClick(service, contactName, normalized, digits, 6, 400L)

        if (!found) return ToolResult.error("Could not find contact '$contactName' in WhatsApp")

        // 1.0s UI Settle Pause for WhatsApp chatroom screen to open and render message TextViews
        XLog.i(TAG, "Pausing 1.0s for WhatsApp chatroom screen to fully render message text")
        try { Thread.sleep(1000L) } catch (_: Exception) {}

        // Step 2: Read latest message text from chat screen
        val root = service.rootInActiveWindow ?: return ToolResult.error("Screen unavailable after chat open")
        val messageText = extractLatestChatMessage(root)
        if (messageText.isBlank()) return ToolResult.error("No message found in chat with '$contactName'")

        XLog.i(TAG, "Extracted latest message from '$contactName': '$messageText'")

        // Step 3: Extract items from message text
        val items = parseShoppingItemsFromMessage(messageText)
        if (items.isEmpty()) return ToolResult.error("Could not parse shopping items from message: '$messageText'")

        XLog.i(TAG, "Parsed ${items.size} shopping items: $items")

        // Close WhatsApp completely by pressing Back twice before opening target store
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        try { Thread.sleep(300L) } catch (_: Exception) {}
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        try { Thread.sleep(300L) } catch (_: Exception) {}

        // Step 4: Execute fast multi-product order on target store
        val storePackage = when {
            storeApp.lowercase().contains("flipkart") -> "com.flipkart.android"
            storeApp.lowercase().contains("blinkit") -> "com.grofers.customerapp"
            storeApp.lowercase().contains("zepto") -> "com.zeptoconsumerapp"
            storeApp.lowercase().contains("amazon") -> "com.amazon.mShop.android.shopping"
            else -> "com.flipkart.android"
        }

        service.openApp(storePackage)
        try { Thread.sleep(600L) } catch (_: Exception) {}

        val cartResult = EcommerceAutomationHelper.processMultiProductOrderList(service, items)

        // Step 5: Send a clean, concise 1-line confirmation back to contact in WhatsApp
        if (cartResult.isSuccess) {
            val confirmMsg = "Hi, I've added all requested items (${items.joinToString(", ")}) to the $storeApp cart!"
            XLog.i(TAG, "Sending clean confirmation message to $contactName: '$confirmMsg'")
            val sendTool = SendMessageTool()
            sendTool.execute(mapOf(
                "contact" to contactName,
                "message" to confirmMsg,
                "app" to "WhatsApp"
            ))
        }

        return cartResult
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

    private fun parseShoppingItemsFromMessage(message: String): List<String> {
        val lines = message.split("\n", ",", ";").map { it.trim() }.filter { it.length > 2 }
        val cleanItems = mutableListOf<String>()
        for (line in lines) {
            val cleaned = line.replace(Regex("""^[0-9]+[\.\)\s\-]+"""), "").trim()
            if (cleaned.length > 2) cleanItems.add(cleaned)
        }
        return cleanItems
    }
}
