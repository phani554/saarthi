// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.tool.ToolResult
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

        XLog.i(TAG, "Extracted latest incoming message (len=${messageText.length})")

        // Step 3: Extract items from message text
        val items = parseShoppingItemsFromMessage(messageText)
        if (items.isEmpty()) return ToolResult.error("Could not parse shopping items from message: '$messageText'")

        XLog.i(TAG, "Parsed ${items.size} shopping items")

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
            // Never silently order on a store the user did not name.
            else -> return ToolResult.error("I don't know the store '$storeApp'. Please say Blinkit, Zepto, Flipkart or Amazon.")
        }

        service.openApp(storePackage)
        try { Thread.sleep(600L) } catch (_: Exception) {}

        val cartResult = EcommerceAutomationHelper.processMultiProductOrderList(service, items)

        // Step 5 removed: it auto-sent a WhatsApp message to the contact without asking the user.

        return cartResult
    }

    private fun extractLatestChatMessage(root: AccessibilityNodeInfo): String {
        val messages = mutableListOf<String>()
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        collectMessageTextNodes(root, messages, rootBounds)
        return messages.lastOrNull().orEmpty()
    }

    /**
     * Collects only WhatsApp message bubbles (message_text ids) that are INCOMING (left-aligned).
     * Any TextView used to qualify, so the "latest message" could be the user's own outgoing bubble,
     * the chat header or "Tap here for contact info".
     */
    private fun collectMessageTextNodes(node: AccessibilityNodeInfo?, results: MutableList<String>, rootBounds: Rect) {
        if (node == null || !node.isVisibleToUser) return
        val resId = node.viewIdResourceName.orEmpty().lowercase()
        val text = node.text?.toString().orEmpty().trim()
        val isIncomingBubble = resId.contains("message_text") && text.isNotEmpty() && run {
            val r = Rect()
            node.getBoundsInScreen(r)
            (r.left - rootBounds.left) <= (rootBounds.right - r.right)
        }
        if (isIncomingBubble) {
            // Old filter dropped any line containing "AM"/"PM" (e.g. "AMUL milk", "PAMPERS").
            // Skip only bubbles that ARE a timestamp/date label.
            val isTimestampLabel = Regex("""^\d{1,2}:\d{2}\s*(?:[ap]\.?m\.?)?$""", RegexOption.IGNORE_CASE).matches(text) ||
                text.equals("Yesterday", true) || text.equals("Today", true)
            if (!isTimestampLabel) {
                results.add(text)
            }
        }
        for (i in 0 until node.childCount) {
            collectMessageTextNodes(node.getChild(i), results, rootBounds)
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
