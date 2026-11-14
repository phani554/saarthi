// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.a11y.NodeRecycler
import io.agents.pokeclaw.agent.OutboundConfirmGate
import io.agents.pokeclaw.agent.SpokenStrings
import io.agents.pokeclaw.agent.knowledge.ContactAliasResolver
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.ContactListUiUtils
import io.agents.pokeclaw.utils.ContactMatchUtils
import io.agents.pokeclaw.utils.XLog

/**
 * Forwards one WhatsApp message to a contact or group.
 *
 * Flow: (ask the user first through [OutboundConfirmGate]) → open the source chat → find the bubble
 * whose text matches message_text → long-press it → tap Forward (id menuitem_forward, or a
 * content description that is exactly "Forward") → search the target in the picker → pick it and
 * check it is selected → Send → check the picker closed (and, when WhatsApp opens the target chat,
 * that the message is there). Any failed step returns an error; nothing is reported as forwarded
 * unless Send was tapped and the picker closed.
 *
 * The source chat is source_contact, else the WhatsApp chat open on screen when the tool is first
 * called (its title is stored with the parked call, so the run after "haan" reopens it).
 */
class WhatsAppForwardTool : BaseTool() {

    companion object {
        private const val TAG = "WhatsAppForwardTool"
        private const val WA = "com.whatsapp"
        private const val PARAM_SOURCE = "source_contact"
        private val TITLE_IDS = arrayOf("com.whatsapp:id/conversation_contact_name", "com.whatsapp.w4b:id/conversation_contact_name")
        private val SEARCH_IDS = arrayOf("com.whatsapp:id/menuitem_search", "com.whatsapp.w4b:id/menuitem_search")
        private val SEND_IDS = arrayOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
    }

    override fun getName(): String = "forward_whatsapp_message"

    override fun getDisplayName(): String = "Forward WhatsApp Message"

    override fun getDescriptionEN(): String =
        "Forward one WhatsApp message (by its text) from a chat to another contact or group. " +
            "Asks the user before forwarding. Use it when the user says forward; to send new text use send_message."

    override fun getDescriptionCN(): String =
        "Forward one WhatsApp message (by its text) from a chat to another contact or group. Asks the user first."

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("target_contact", "string", "Contact or group to forward the message to", true),
        ToolParameter("message_text", "string", "The text of the message to forward, as shown in the chat", true),
        ToolParameter(PARAM_SOURCE, "string", "Chat that has the message (default: the WhatsApp chat open on screen)", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val rawTarget = optionalString(params, "target_contact", "").trim()
        val text = optionalString(params, "message_text", "").trim()
        if (rawTarget.isBlank()) return ToolResult.error("Missing required parameter: target_contact")
        if (text.length < 2) {
            return ToolResult.error("Missing message_text: say which message to forward (its words as shown in the chat).")
        }
        val service = requireAccessibilityService()
            ?: return ToolResult.error("Accessibility service is not running")
        val target = ContactAliasResolver.resolve(rawTarget)

        // Source chat: given, else the chat open now. Stored in the parked params for the run after "haan".
        var source = optionalString(params, PARAM_SOURCE, "").trim()
        if (source.isBlank()) {
            source = currentChatTitle(service).orEmpty()
            if (source.isBlank()) {
                return ToolResult.error("Nothing was forwarded: open the WhatsApp chat that has the message first, or pass $PARAM_SOURCE.")
            }
        } else {
            source = ContactAliasResolver.resolve(source)
        }
        val withSource = LinkedHashMap<String, Any>(params).apply { put(PARAM_SOURCE, source) }

        // Ask the user first (she answers haan / na); nothing happens on screen until she says yes.
        OutboundConfirmGate.check(getName(), withSource, SpokenStrings.confirmSend(rawTarget, text))?.let { return it }

        return try {
            forward(service, source, target, text)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ToolResult.error("Interrupted. The message may not have been forwarded; check WhatsApp before retrying.")
        } catch (e: Exception) {
            XLog.e(TAG, "forward failed", e)
            ToolResult.error("Forward failed: ${e.message}. Check WhatsApp before retrying (do not forward twice).")
        }
    }

    private fun forward(service: ClawAccessibilityService, source: String, target: String, text: String): ToolResult {
        // 1. Source chat open and verified.
        if (!BaseTool.isWhatsAppChatWith(service.rootInActiveWindow, source)) {
            if (!OpenAppTool.openAppWithInterceptHandling(service, WA)) return ToolResult.error("Could not open WhatsApp.")
            if (!ContactListUiUtils.prepareForContactLookup(service, WA, 3, 300)) {
                return ToolResult.error("Could not reach the WhatsApp chat list to open '$source'.")
            }
            val aliases = ContactMatchUtils.buildNormalizedAliases(source)
            val digits = ContactMatchUtils.buildDigitAliases(source)
            if (!ContactListUiUtils.searchOrScrollAndFindAndClick(service, source, aliases, digits, 6, 200)) {
                return ToolResult.error("Could not find the chat '$source' in WhatsApp.")
            }
            if (!waitFor(2000L) { BaseTool.isWhatsAppChatWith(service.rootInActiveWindow, source) }) {
                return ToolResult.error("Opened a chat but could not confirm it is '$source'. Nothing was forwarded.")
            }
        }

        // 2. Find the bubble (scroll up a little if it is not on screen).
        var bubble: AccessibilityNodeInfo? = null
        for (attempt in 0..3) {
            when (val found = findBubble(service, text)) {
                is BubbleResult.Found -> { bubble = found.node; break }
                is BubbleResult.Ambiguous -> return ToolResult.error(
                    "Several messages in '$source' match '$text'. Nothing was forwarded. Use the exact words of one: " +
                        found.texts.take(3).joinToString(" | ") { "\"${it.take(60)}\"" }
                )
                BubbleResult.None -> if (attempt < 3) scrollChatUp(service)
            }
        }
        val node = bubble ?: return ToolResult.error("No message matching '$text' found in the chat with '$source'. Nothing was forwarded.")

        // 3. Long-press to select it, then 4. tap Forward.
        if (!longPress(service, node)) {
            NodeRecycler.recycle(node)
            return ToolResult.error("Could not long-press the message. Nothing was forwarded.")
        }
        NodeRecycler.recycle(node)
        var forwardBtn: AccessibilityNodeInfo? = null
        waitFor(1500L) { findForwardControl(service.rootInActiveWindow).also { forwardBtn = it } != null }
        val fwd = forwardBtn ?: run {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) // leave selection mode
            return ToolResult.error("The Forward button did not appear after selecting the message. Nothing was forwarded.")
        }
        if (!service.clickNode(fwd)) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return ToolResult.error("Could not tap Forward. Nothing was forwarded.")
        }

        // 5. Picker: search the target and pick it.
        if (!waitFor(2000L) { findSendOrSearch(service.rootInActiveWindow) }) {
            return ToolResult.error("The forward contact list did not open. Nothing was forwarded.")
        }
        if (!searchInPicker(service, target)) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return ToolResult.error("Could not search for '$target' in the forward list. Nothing was forwarded.")
        }
        val aliases = ContactMatchUtils.buildNormalizedAliases(target)
        val digits = ContactMatchUtils.buildDigitAliases(target)
        var row: AccessibilityNodeInfo? = null
        waitFor(2000L) {
            row = ContactListUiUtils.findBestVisibleContactNode(service.rootInActiveWindow, aliases, digits)
                ?.takeIf { !it.isEditable }
            row != null
        }
        val targetRow = row ?: run {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return ToolResult.error("'$target' was not found in the forward list. Nothing was forwarded.")
        }
        if (!ContactListUiUtils.clickContactNodeSafely(service, targetRow)) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return ToolResult.error("Could not pick '$target' in the forward list. Nothing was forwarded.")
        }
        if (!waitFor(1500L) { targetSelected(service.rootInActiveWindow, aliases, digits) }) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return ToolResult.error("Could not confirm '$target' is selected in the forward list. Nothing was forwarded.")
        }

        // 6. Send.
        val send = findSendButton(service.rootInActiveWindow)
            ?: return ToolResult.error("'$target' is selected but the Send button was not found. NOT forwarded; check WhatsApp.")
        if (!service.clickNode(send)) {
            return ToolResult.error("Could not tap Send. NOT forwarded; check WhatsApp.")
        }

        // 7. Verify: the picker closes; when WhatsApp opens the target chat, look for the message there.
        val closed = waitFor(3000L) { findSendButton(service.rootInActiveWindow) == null }
        if (!closed) {
            return ToolResult.error("Tapped Send but the forward screen is still open; it was probably NOT forwarded. Check WhatsApp before retrying (do not forward twice).")
        }
        val root = service.rootInActiveWindow
        if (BaseTool.isWhatsAppChatWith(root, target) && chatHasBubble(root, text)) {
            return ToolResult.success("Forwarded '$text' from $source to $target on WhatsApp (verified in $target's chat).")
        }
        return ToolResult.success("Forwarded '$text' from $source to $target on WhatsApp (Send tapped and the forward screen closed; not checked inside $target's chat).")
    }

    // ── Screen helpers ─────────────────────────────────────────────────────────────────────────

    private sealed class BubbleResult {
        data class Found(val node: AccessibilityNodeInfo) : BubbleResult()
        data class Ambiguous(val texts: List<String>) : BubbleResult()
        object None : BubbleResult()
    }

    /** Title of the open WhatsApp chat, or null when no chat is open. */
    private fun currentChatTitle(service: ClawAccessibilityService): String? {
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName?.toString()?.startsWith(WA) != true || !ContactListUiUtils.isOpenChatroom(root)) return null
        for (id in TITLE_IDS) {
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null } ?: continue
            val title = nodes.firstOrNull { !it.isEditable && !it.text.isNullOrBlank() }?.text?.toString()?.trim()
            NodeRecycler.recycleAll(nodes)
            if (!title.isNullOrBlank()) return title
        }
        return null
    }

    private fun findBubble(service: ClawAccessibilityService, text: String): BubbleResult {
        val root = service.rootInActiveWindow ?: return BubbleResult.None
        val nodes = ArrayList<AccessibilityNodeInfo>()
        for (id in WhatsAppForwardLogic.MESSAGE_TEXT_IDS) {
            try { root.findAccessibilityNodeInfosByViewId(id)?.let { nodes.addAll(it) } } catch (_: Exception) {}
        }
        val visible = nodes.filter { it.isVisibleToUser && !it.text.isNullOrBlank() }
        val bubbles = visible.map { n ->
            val r = Rect()
            n.getBoundsInScreen(r)
            WhatsAppForwardLogic.Bubble(n.text.toString(), r.bottom)
        }
        val result = when (val p = WhatsAppForwardLogic.pick(bubbles, text)) {
            is WhatsAppForwardLogic.Pick.Found -> BubbleResult.Found(visible[p.index])
            is WhatsAppForwardLogic.Pick.Ambiguous -> BubbleResult.Ambiguous(p.texts)
            WhatsAppForwardLogic.Pick.None -> BubbleResult.None
        }
        val keep = (result as? BubbleResult.Found)?.node
        for (n in nodes) NodeRecycler.recycleUnless(n, keep)
        return result
    }

    /** Swipe down on the message list so older messages come into view. */
    private fun scrollChatUp(service: ClawAccessibilityService) {
        val size = getScreenSize()
        val x = size[0] / 2
        service.performSwipe(x, (size[1] * 0.35).toInt(), x, (size[1] * 0.75).toInt(), 350)
        Thread.sleep(500)
    }

    private fun longPress(service: ClawAccessibilityService, bubble: AccessibilityNodeInfo): Boolean {
        // The bubble's long-clickable row (the text view itself usually is not long-clickable).
        var n: AccessibilityNodeInfo? = bubble
        var depth = 0
        while (n != null && depth < 5) {
            if (n.isLongClickable) {
                val ok = n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                NodeRecycler.recycleUnless(n, bubble)
                if (ok) return true
                break
            }
            val next = n.parent
            NodeRecycler.recycleUnless(n, bubble)
            n = next
            depth++
        }
        if (n != null && depth >= 5) NodeRecycler.recycleUnless(n, bubble)
        val r = Rect()
        bubble.getBoundsInScreen(r)
        return service.performLongPress(r.centerX(), r.centerY(), 800)
    }

    /** Forward control by id, or exact "Forward" content description. Null when absent. */
    private fun findForwardControl(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        for (id in arrayOf(WhatsAppForwardLogic.FORWARD_ID, WhatsAppForwardLogic.FORWARD_ID_W4B)) {
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null } ?: continue
            val hit = nodes.firstOrNull { it.isVisibleToUser }
            for (n in nodes) NodeRecycler.recycleUnless(n, hit)
            if (hit != null) return hit
        }
        return findFirst(root, 0) { it.isVisibleToUser && WhatsAppForwardLogic.isForwardControl(it.viewIdResourceName, it.contentDescription) }
    }

    private fun findSendOrSearch(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val send = findSendButton(root)
        if (send != null) { NodeRecycler.recycle(send); return true }
        val search = findSearchControl(root)
        if (search != null) { NodeRecycler.recycle(search); return true }
        return false
    }

    private fun findSearchControl(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        for (id in SEARCH_IDS) {
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null } ?: continue
            val hit = nodes.firstOrNull { it.isVisibleToUser }
            for (n in nodes) NodeRecycler.recycleUnless(n, hit)
            if (hit != null) return hit
        }
        return findFirst(root, 0) {
            it.isVisibleToUser && !it.isEditable && it.contentDescription?.toString()?.trim()?.equals("Search", true) == true
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        for (id in SEND_IDS) {
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null } ?: continue
            val hit = nodes.firstOrNull { it.isVisibleToUser }
            for (n in nodes) NodeRecycler.recycleUnless(n, hit)
            if (hit != null) return hit
        }
        return findFirst(root, 0) {
            it.isVisibleToUser && !it.isEditable && it.contentDescription?.toString()?.trim()?.equals("Send", true) == true
        }
    }

    /** Opens the picker's search (if a search box is not already there) and types the target. */
    private fun searchInPicker(service: ClawAccessibilityService, target: String): Boolean {
        var field = findEditable(service.rootInActiveWindow)
        if (field == null) {
            val search = findSearchControl(service.rootInActiveWindow) ?: return false
            val clicked = service.clickNode(search)
            NodeRecycler.recycle(search)
            if (!clicked) return false
            waitFor(1500L) { findEditable(service.rootInActiveWindow).also { field = it } != null }
        }
        val box = field ?: return false
        val typed = service.setNodeText(box, ContactListUiUtils.cleanSearchQuery(target))
        NodeRecycler.recycle(box)
        if (typed) Thread.sleep(600) // let the list filter
        return typed
    }

    private fun findEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        findFirst(root, 0) { it.isVisibleToUser && it.isEditable }

    /** The target shows as selected: its row is checked/selected, or its name is in the bottom selection bar. */
    private fun targetSelected(root: AccessibilityNodeInfo?, aliases: Set<String>, digits: Set<String>): Boolean {
        if (root == null) return false
        val screen = Rect()
        root.getBoundsInScreen(screen)
        val barTop = screen.top + (screen.height() * 0.7).toInt()
        val hit = findFirst(root, 0) { n ->
            if (n.isEditable || !n.isVisibleToUser) return@findFirst false
            if (!ContactMatchUtils.matchesTarget(n.text, n.contentDescription, aliases, digits)) return@findFirst false
            val r = Rect()
            n.getBoundsInScreen(r)
            r.top >= barTop || n.isSelected || n.isChecked
        }
        NodeRecycler.recycle(hit)
        return hit != null
    }

    private fun chatHasBubble(root: AccessibilityNodeInfo?, text: String): Boolean {
        if (root == null) return false
        for (id in WhatsAppForwardLogic.MESSAGE_TEXT_IDS) {
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null } ?: continue
            val ok = nodes.any { it.isVisibleToUser && WhatsAppForwardLogic.score(it.text?.toString().orEmpty(), text) >= 2 }
            NodeRecycler.recycleAll(nodes)
            if (ok) return true
        }
        return false
    }

    /** Depth-first search; returns the first match (the caller's to recycle) and recycles the rest. */
    private fun findFirst(node: AccessibilityNodeInfo?, depth: Int, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (test(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findFirst(child, depth + 1, test)
            if (hit !== child) NodeRecycler.recycle(child)
            if (hit != null) return hit
        }
        return null
    }

    private inline fun waitFor(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (check()) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(150)
        }
    }
}
