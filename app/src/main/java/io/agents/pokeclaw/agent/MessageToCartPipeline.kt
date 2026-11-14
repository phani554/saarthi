// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

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

    /** Store package for a spoken / typed store name, or null when unknown (never a silent default). */
    fun storePackageFor(storeApp: String): String? {
        val s = storeApp.lowercase()
        return when {
            s.contains("flipkart") -> "com.flipkart.android"
            s.contains("blinkit") -> "com.grofers.customerapp"
            s.contains("zepto") -> "com.zeptoconsumerapp"
            s.contains("amazon") -> "com.amazon.mShop.android.shopping"
            else -> null
        }
    }

    /** Item list as read from the chat, or an error result. Items stay verbatim (her contact's words). */
    data class ReadResult(val items: List<String>, val error: ToolResult?)

    /**
     * Step 1 (before asking the user): open the contact's WhatsApp chat, read the latest incoming
     * message and parse the list. Nothing is added to any cart here.
     */
    fun readItems(service: ClawAccessibilityService, contactName: String): ReadResult {
        XLog.i(TAG, "Reading shopping list from a WhatsApp chat")
        val opened = ContactListUiUtils.prepareForContactLookup(service, "com.whatsapp", 3, 600L)
        if (!opened) return ReadResult(emptyList(), ToolResult.error("Could not open WhatsApp chat list"))

        val normalized = ContactMatchUtils.buildNormalizedAliases(contactName)
        val digits = ContactMatchUtils.buildDigitAliases(contactName)
        val found = ContactListUiUtils.searchOrScrollAndFindAndClick(service, contactName, normalized, digits, 6, 400L)
        if (!found) return ReadResult(emptyList(), ToolResult.error("Could not find contact '$contactName' in WhatsApp"))

        // 1.0s UI settle pause for the chat to render its message TextViews
        try { Thread.sleep(1000L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }

        val root = service.rootInActiveWindow
            ?: return ReadResult(emptyList(), ToolResult.error("Screen unavailable after chat open"))
        val messageText = extractLatestChatMessage(root)
        if (messageText.isBlank()) return ReadResult(emptyList(), ToolResult.error("No message found in chat with '$contactName'"))
        XLog.i(TAG, "Extracted latest incoming message (len=${messageText.length})")

        val items = parseShoppingItemsFromMessage(messageText)
        if (items.isEmpty()) return ReadResult(emptyList(), ToolResult.error("Could not parse shopping items from the latest message"))
        XLog.i(TAG, "Parsed ${items.size} shopping items")
        return ReadResult(items, null)
    }

    /** Step 2 (only after the user said yes to the read-back): add the items on the store. */
    fun addToCart(service: ClawAccessibilityService, storePackage: String, items: List<String>): ToolResult {
        XLog.i(TAG, "Adding ${items.size} confirmed items on $storePackage")
        service.openApp(storePackage)
        try { Thread.sleep(600L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        // No message is sent back to the contact (it used to be, without asking the user).
        return EcommerceAutomationHelper.processMultiProductOrderList(service, items)
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
