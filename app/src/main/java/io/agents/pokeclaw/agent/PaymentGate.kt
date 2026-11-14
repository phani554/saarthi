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
        """^(pay\b.*|.*\bpay\s*(now|₹|rs\.?|inr)\b.*|.*\bplace\s+order\b.*|.*\bproceed\s+to\s+(pay|payment|checkout)\b.*|.*\bbuy\s+now\b.*|.*\bconfirm\s+(payment|order)\b.*|.*\bslide\s+to\s+pay\b.*|.*\bmake\s+payment\b.*|.*\bupi\s+pin\b.*)$""",
        RegexOption.IGNORE_CASE
    )

    @JvmStatic
    fun isPaymentLabel(label: String?): Boolean {
        val clean = label?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (clean.isEmpty() || clean.length > 60) return false
        return PAY_LABEL.matches(clean)
    }

    /** True if the node (or a direct child label of it) is a pay / place-order control. */
    @JvmStatic
    fun isPaymentNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val labels = mutableListOf(node.text?.toString(), node.contentDescription?.toString())
        for (i in 0 until minOf(node.childCount, 6)) {
            val c = node.getChild(i) ?: continue
            labels += c.text?.toString()
            labels += c.contentDescription?.toString()
        }
        val hit = labels.firstOrNull { isPaymentLabel(it) }
        if (hit != null) XLog.w(TAG, "Blocked tap on payment control: '$hit'")
        return hit != null
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
        for (i in 0 until node.childCount) {
            val hit = deepestAt(node.getChild(i), x, y)
            if (hit != null) return hit
        }
        return node
    }
}
