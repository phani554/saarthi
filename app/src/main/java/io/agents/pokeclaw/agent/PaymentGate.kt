// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.utils.XLog

/**
 * Hard, code-level checkout stop. Before this, "never pay" lived only in the prompt and the
 * playbook, so the model was asked (not prevented) to stop at Pay. Every click/tap from the
 * agent goes through ClawAccessibilityService.clickNode/performTap, which consult this gate.
 */
object PaymentGate {
    private const val TAG = "PaymentGate"

    private val PAY_LABEL = Regex(
        """^(pay\b.*|.*\bpay\s*(now|₹|rs\.?|inr)\b.*|.*\bplace\s+(your\s+)?order\b.*|.*\bproceed\s+to\s+(pay|payment|checkout|buy)\b.*|.*\bbuy\s+now\b.*|.*\border\s+now\b.*|.*\bconfirm\s+(payment|order)\b.*|.*\b(swipe|slide)\s+to\s+(pay|order|place\s+order)\b.*|.*\bmake\s+payment\b.*|.*\bupi\s+pin\b.*)$""",
        RegexOption.IGNORE_CASE
    )

    @JvmStatic
    fun isPaymentLabel(label: String?): Boolean {
        val clean = label?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (clean.isEmpty()) return false
        // Long labels ("Place Order, total ₹1,234 incl. delivery ...") must not skip the gate:
        // test only their head instead of returning false.
        return PAY_LABEL.matches(clean.take(LABEL_HEAD))
    }

    private const val LABEL_HEAD = 80
    private const val SUBTREE_DEPTH = 3
    private const val SUBTREE_MAX_NODES = 60
    private const val ANCESTOR_DEPTH = 6

    /** True if the node or any descendant (up to [SUBTREE_DEPTH] levels) is a pay / place-order control. */
    @JvmStatic
    fun isPaymentNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val hit = findPaymentLabel(node)
        if (hit != null) XLog.w(TAG, "Blocked tap on payment control (label length ${hit.length})")
        return hit != null
    }

    /**
     * True if the node, its subtree, or any ancestor up to and including the nearest clickable
     * ancestor (with that ancestor's subtree) is a pay / place-order control. Use this before
     * clicking a node whose click may be delegated to a clickable parent.
     */
    @JvmStatic
    fun isPaymentNodeOrAncestor(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (isPaymentNode(node)) return true
        if (node.isClickable) return false
        var p: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (p != null && depth < ANCESTOR_DEPTH) {
            if (isPaymentNode(p)) return true
            if (p.isClickable) return false
            p = p.parent
            depth++
        }
        return false
    }

    /** Breadth-first scan of the node and its descendants up to [SUBTREE_DEPTH] levels. */
    private fun findPaymentLabel(root: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < SUBTREE_MAX_NODES) {
            val (n, d) = queue.removeFirst()
            visited++
            val text = n.text?.toString()
            if (isPaymentLabel(text)) return text
            val desc = n.contentDescription?.toString()
            if (isPaymentLabel(desc)) return desc
            if (d < SUBTREE_DEPTH) {
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    queue.addLast(c to d + 1)
                }
            }
        }
        return null
    }

    /** Coordinate taps: check the smallest node containing (x, y) and its clickable ancestor. */
    @JvmStatic
    fun isPaymentAt(root: AccessibilityNodeInfo?, x: Int, y: Int): Boolean {
        val leaf = deepestAt(root, x, y) ?: return false
        var n: AccessibilityNodeInfo? = leaf
        var depth = 0
        while (n != null && depth < 4) {
            if (isPaymentNode(n)) return true
            if (n.isClickable) break
            n = n.parent
            depth++
        }
        return false
    }

    private fun deepestAt(node: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (node == null) return null
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.contains(x, y)) return null
        // Last child is drawn on top, so it wins when siblings overlap.
        for (i in node.childCount - 1 downTo 0) {
            val hit = deepestAt(node.getChild(i), x, y)
            if (hit != null) return hit
        }
        return node
    }
}
