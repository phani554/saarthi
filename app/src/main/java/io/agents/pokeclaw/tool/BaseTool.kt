// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.blankj.utilcode.util.ScreenUtils
import io.agents.pokeclaw.agent.PaymentGate
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.utils.ContactListUiUtils
import io.agents.pokeclaw.utils.ContactMatchUtils

abstract class BaseTool {

    companion object {
        /** Tool description language: true = Chinese, false = English */
        @JvmField
        var useChineseDescription: Boolean = false

        /**
         * Shared wait_after parameter definition used by all tools.
         * Automatically appended to the end of each tool's parameter list by getParametersWithWaitAfter().
         */
        @JvmStatic
        val WAIT_AFTER_PARAM = ToolParameter(
            "wait_after",
            "integer",
            "Optional: milliseconds to wait after this action completes for UI settle (e.g. 700). Default 700.",
            false
        )

        private val SLIDE_TO_PAY = Regex(
            """(slide|swipe|drag)\s+(right\s+)?to\s+(pay|order|place|confirm|buy|checkout)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Gesture-level payment stop for long-press and swipe, which do not go through
         * ClawAccessibilityService.performTap's PaymentGate check. Returns an error message
         * when the gesture touches a pay / place-order control, or a "slide/swipe to pay"
         * slider lies near the gesture path; null when the gesture is allowed.
         */
        @JvmStatic
        fun paymentGestureBlock(
            root: AccessibilityNodeInfo?,
            startX: Int, startY: Int, endX: Int, endY: Int
        ): String? {
            if (root == null) return null
            if (PaymentGate.isPaymentAt(root, startX, startY) || PaymentGate.isPaymentAt(root, endX, endY)) {
                return "Blocked: that is a payment / place-order control. The user must complete payment themselves."
            }
            val pad = 150
            val path = Rect(
                minOf(startX, endX) - pad, minOf(startY, endY) - pad,
                maxOf(startX, endX) + pad, maxOf(startY, endY) + pad
            )
            return if (slideToPayNear(root, path, 0)) {
                "Blocked: a slide/swipe-to-pay control is on that path. The user must complete payment themselves."
            } else null
        }

        /**
         * Tool-side PaymentGate check that also covers what clickNode can end up clicking:
         * the node and its labels up to 3 levels down, then each ancestor up to and including
         * the first clickable one (clickNode clicks that parent without re-checking it).
         */
        @JvmStatic
        fun isPaymentNodeOrAncestor(node: AccessibilityNodeInfo?): Boolean {
            var n: AccessibilityNodeInfo? = node
            var depth = 0
            while (n != null && depth < 8) {
                if (PaymentGate.isPaymentNode(n) || subtreeHasPayLabel(n, 0)) return true
                if (n.isClickable) break
                n = n.parent
                depth++
            }
            return false
        }

        private fun subtreeHasPayLabel(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > 3) return false
            if (PaymentGate.isPaymentLabel(node.text?.toString()) ||
                PaymentGate.isPaymentLabel(node.contentDescription?.toString())) return true
            for (i in 0 until minOf(node.childCount, 12)) {
                if (subtreeHasPayLabel(node.getChild(i), depth + 1)) return true
            }
            return false
        }

        /**
         * True when WhatsApp has the chat with [contact] open: the screen is an open chatroom and
         * the conversation title (by id; the top text band only as a fallback) names the contact.
         * Editable nodes (the search box holding the typed name) never count.
         */
        @JvmStatic
        fun isWhatsAppChatWith(root: AccessibilityNodeInfo?, contact: String): Boolean {
            if (root == null) return false
            if (!ContactListUiUtils.isOpenChatroom(root)) return false
            val names = ContactMatchUtils.buildNormalizedAliases(contact)
            val digits = ContactMatchUtils.buildDigitAliases(contact)
            val titles = mutableListOf<AccessibilityNodeInfo>()
            for (id in arrayOf("com.whatsapp:id/conversation_contact_name", "com.whatsapp.w4b:id/conversation_contact_name")) {
                try { root.findAccessibilityNodeInfosByViewId(id)?.let { titles.addAll(it) } } catch (_: Exception) { }
            }
            if (titles.isEmpty()) collectTopText(root, titles, 0)
            return titles.any { n ->
                !n.isEditable && ContactMatchUtils.matchesTarget(n.text, n.contentDescription, names, digits)
            }
        }

        private fun collectTopText(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
            if (node == null || depth > 40 || !node.isVisibleToUser) return
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.top < 350 && (node.text != null || node.contentDescription != null)) out.add(node)
            for (i in 0 until node.childCount) collectTopText(node.getChild(i), out, depth + 1)
        }

        private fun slideToPayNear(node: AccessibilityNodeInfo?, area: Rect, depth: Int): Boolean {
            if (node == null || depth > 40) return false
            val r = Rect()
            node.getBoundsInScreen(r)
            if (Rect.intersects(r, area)) {
                val t = node.text?.toString().orEmpty()
                val d = node.contentDescription?.toString().orEmpty()
                if (SLIDE_TO_PAY.containsMatchIn(t) || SLIDE_TO_PAY.containsMatchIn(d)) return true
            }
            for (i in 0 until node.childCount) {
                if (slideToPayNear(node.getChild(i), area, depth + 1)) return true
            }
            return false
        }
    }

    abstract fun getName(): String
    abstract fun getParameters(): List<ToolParameter>
    abstract fun execute(params: @JvmSuppressWildcards Map<String, Any>): ToolResult

    /**
     * Returns the tool parameter list plus the shared wait_after parameter.
     * Used by ToolBridge when registering tool specifications.
     */
    fun getParametersWithWaitAfter(): List<ToolParameter> {
        val params = getParameters().toMutableList()
        // Do not add wait_after to observation tools like wait / finish / get_screen_info
        if (getName() !in listOf("wait", "finish", "get_screen_info", "take_screenshot", "get_installed_apps", "find_node_info", "scroll_to_find", "list_scheduled_tasks", "schedule_task", "cancel_scheduled_task")) {
            params.add(WAIT_AFTER_PARAM)
        }
        return params
    }

    /**
     * Execute the tool with tuned 700ms UI settle pause for maximum perception accuracy across all major shopping sites.
     * Called by ToolRegistry.executeTool().
     */
    fun executeWithWaitAfter(params: @JvmSuppressWildcards Map<String, Any>): ToolResult {
        val result = execute(params)
        val waitMs = optionalLong(params, "wait_after", 700L)
        if (waitMs > 0 && getName() in listOf("tap", "tap_node", "find_and_tap", "input_text", "swipe", "open_app", "add_to_cart")) {
            try {
                Thread.sleep(Math.min(waitMs, 1000L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return result
    }

    /** English description, subclasses must implement */
    abstract fun getDescriptionEN(): String

    /** Chinese description, subclasses must implement */
    abstract fun getDescriptionCN(): String

    /** Returns description based on language toggle */
    fun getDescription(): String =
        if (useChineseDescription) getDescriptionCN() else getDescriptionEN()

    /** Display name shown to the user; subclasses may override */
    open fun getDisplayName(): String = getName()

    // === Parameter helpers ===

    protected fun requireString(params: @JvmSuppressWildcards Map<String, Any>, key: String): String {
        return params[key]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: $key")
    }

    protected fun requireInt(params: @JvmSuppressWildcards Map<String, Any>, key: String): Int {
        val value = params[key] ?: throw IllegalArgumentException("Missing required parameter: $key")
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().toInt()
        }
    }

    protected fun requireLong(params: @JvmSuppressWildcards Map<String, Any>, key: String): Long {
        val value = params[key] ?: throw IllegalArgumentException("Missing required parameter: $key")
        return when (value) {
            is Number -> value.toLong()
            else -> value.toString().toLong()
        }
    }

    protected fun optionalInt(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: Int): Int {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().toInt()
        }
    }

    protected fun optionalLong(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: Long): Long {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Number -> value.toLong()
            else -> value.toString().toLong()
        }
    }

    protected fun optionalString(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: String): String {
        return params[key]?.toString() ?: defaultValue
    }

    protected fun optionalBoolean(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: Boolean): Boolean {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value.toString().toBoolean()
        }
    }

    /**
     * Accessibility can briefly disconnect/rebind while Android reshuffles the service.
     * Tools should tolerate that short gap instead of failing immediately.
     */
    @JvmOverloads
    protected fun requireAccessibilityService(timeoutMs: Long = 20_000L): ClawAccessibilityService? {
        return ClawAccessibilityService.getConnectedInstance(timeoutMs)
    }

    // === Screen bounds helpers ===

    /**
     * Get screen size [width, height].
     */
    protected fun getScreenSize(): IntArray {
        return intArrayOf(ScreenUtils.getScreenWidth(), ScreenUtils.getScreenHeight())
    }

    /**
     * Validate that coordinates are within screen bounds.
     * Returns an error message if out of bounds, or null if valid.
     */
    protected fun validateCoordinates(x: Int, y: Int): String? {
        val size = getScreenSize()
        if (x < 0 || x >= size[0] || y < 0 || y >= size[1]) {
            return "Coordinates ($x, $y) out of screen bounds (${size[0]}x${size[1]}). Use get_screen_info to get valid coordinates."
        }
        return null
    }
}
