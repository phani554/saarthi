// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.a11y.NodeRecycler
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
        // Ancestors are obtained here and never returned: recycled (API < 33). [node] is the caller's.
        var p: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (p != null && depth < ANCESTOR_DEPTH) {
            if (isPaymentNode(p)) { NodeRecycler.recycle(p); return true }
            if (p.isClickable) { NodeRecycler.recycle(p); return false }
            val next = p.parent
            NodeRecycler.recycle(p)
            p = next
            depth++
        }
        NodeRecycler.recycle(p)
        return false
    }

    /**
     * Breadth-first scan of the node and its descendants up to [SUBTREE_DEPTH] levels.
     * Descendants are obtained here and recycled (API < 33); [root] belongs to the caller.
     */
    private fun findPaymentLabel(root: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        var found: String? = null
        while (queue.isNotEmpty() && visited < SUBTREE_MAX_NODES && found == null) {
            val (n, d) = queue.removeFirst()
            visited++
            val text = n.text?.toString()
            val desc = n.contentDescription?.toString()
            if (isPaymentLabel(text)) {
                found = text
            } else if (isPaymentLabel(desc)) {
                found = desc
            } else if (d < SUBTREE_DEPTH) {
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    queue.addLast(c to d + 1)
                }
            }
            NodeRecycler.recycleUnless(n, root)
        }
        for ((left, _) in queue) NodeRecycler.recycleUnless(left, root)
        return found
    }

    /** Coordinate taps: check the smallest node containing (x, y) and its clickable ancestor. */
    @JvmStatic
    fun isPaymentAt(root: AccessibilityNodeInfo?, x: Int, y: Int): Boolean {
        val leaf = deepestAt(root, x, y) ?: return false
        var n: AccessibilityNodeInfo? = leaf
        var depth = 0
        var blocked = false
        while (n != null && depth < 4) {
            if (isPaymentNode(n)) { blocked = true; break }
            if (n.isClickable) break
            val next = n.parent
            NodeRecycler.recycleUnless(n, root)
            n = next
            depth++
        }
        NodeRecycler.recycleUnless(n, root)
        return blocked
    }

    /**
     * Deepest node under (x, y). Children that are not on the returned path are recycled; the
     * returned node is the caller's to recycle (it may be [node] itself, i.e. the caller's root).
     */
    private fun deepestAt(node: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (node == null) return null
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.contains(x, y)) return null
        // Last child is drawn on top, so it wins when siblings overlap.
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            val hit = deepestAt(child, x, y)
            if (hit !== child) NodeRecycler.recycle(child)
            if (hit != null) return hit
        }
        return node
    }

    // ── UPI / bank apps: strict mode (owner decision 2026-11-14) ─────────────────────────────
    // Saathi never pays. Inside these apps any tap / type / gesture is refused when its target or the
    // screen looks payment-related, and a PIN is never typed anywhere. Amazon is not listed: it keeps
    // the label-based checks above (pay screens only). Ids checked against the Play Store and the
    // Juspay UPI package list (juspay.io/in/docs/api-reference/docs/express-checkout/upi-package-names).
    @JvmField
    val UPI_BANK_PACKAGES: Set<String> = setOf(
        "com.google.android.apps.nbu.paisa.user",   // Google Pay
        "com.phonepe.app",                          // PhonePe
        "net.one97.paytm",                          // Paytm
        "in.org.npci.upiapp",                       // BHIM
        "com.sbi.lotusintouch",                     // YONO SBI
        "com.sbi.SBIFreedomPlus",                   // SBI Anywhere / YONO Lite
        "com.sbi.upi",                              // SBI Pay
        "com.snapwork.hdfc", "com.snapwork.hdfcbank", "com.enstage.wibmo.hdfc", // HDFC
        "com.csam.icici.bank.imobile", "com.icicibank.pockets",                 // ICICI
        "com.axis.mobile", "com.upi.axispay",                                   // Axis
        "com.kotak.bank.mobile", "com.msf.kbank.mobile",                        // Kotak (new, old)
        "com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge",     // Kotak 811
        "com.fss.pnbpsp",                           // PNB UPI
        "com.mobikwik_new",                         // MobiKwik
        "com.freecharge.android",                   // Freecharge
        "com.dreamplug.androidapp",                 // CRED
        "money.super.payments",                     // super.money
        "com.samsung.android.spay",                 // Samsung Pay / Wallet
    )

    private val PAY_SCREEN = Regex(
        """\b(pay|payment|payments|paying|send money|transfer|amount|upi|pin|mpin|bank|balance|recharge|bill|scan|qr|request money|wallet|debit|credit|card|cvv|otp|proceed|confirm)\b|₹|\brs\.?\s*\d""",
        RegexOption.IGNORE_CASE
    )
    private val PIN_HINT = Regex("""\b(upi\s*pin|m-?pin|atm\s*pin|card\s*pin|pin|passcode|cvv)\b""", RegexOption.IGNORE_CASE)
    private val POSTAL_PIN = Regex("""\b(pin\s*-?\s*code|pincode|postal|zip)\b""", RegexOption.IGNORE_CASE)
    private const val SCREEN_SCAN_MAX_NODES = 400

    @JvmStatic
    fun isUpiOrBankPackage(pkg: CharSequence?): Boolean = pkg != null && pkg.toString() in UPI_BANK_PACKAGES

    /** True when any visible text on the screen of [root] looks payment-related. */
    @JvmStatic
    fun isPaymentScreen(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        var hit = false
        while (queue.isNotEmpty() && visited < SCREEN_SCAN_MAX_NODES && !hit) {
            val n = queue.removeFirst()
            visited++
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            if ((t != null && PAY_SCREEN.containsMatchIn(t)) || (d != null && PAY_SCREEN.containsMatchIn(d))) {
                hit = true
            } else {
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
            }
            NodeRecycler.recycleUnless(n, root) // [root] is the caller's
        }
        for (left in queue) NodeRecycler.recycleUnless(left, root)
        return hit
    }

    /** Strict block for a screen root: a UPI / bank app showing anything payment-related. */
    @JvmStatic
    fun isStrictBlockedScreen(root: AccessibilityNodeInfo?): Boolean {
        if (root == null || !isUpiOrBankPackage(root.packageName)) return false
        val blocked = isPaymentScreen(root)
        if (blocked) XLog.w(TAG, "Blocked action inside UPI/bank app ${root.packageName}")
        return blocked
    }

    /** Strict block for a node target: inside a UPI / bank app, the node or its screen looks payment-related. */
    @JvmStatic
    fun isStrictBlockedNode(node: AccessibilityNodeInfo?, activeRoot: AccessibilityNodeInfo?): Boolean {
        if (node == null || !isUpiOrBankPackage(node.packageName)) return false
        if (isPaymentNode(node)) return true
        val window = try { node.window } catch (_: Exception) { null }
        val windowRoot = try { window?.root } catch (_: Exception) { null }
        NodeRecycler.recycleWindow(window)
        val root = windowRoot ?: activeRoot
        try {
            if (root != null && isUpiOrBankPackage(root.packageName)) return isStrictBlockedScreen(root)
            // No root to read: inside a UPI / bank app, fail closed.
            return true
        } finally {
            NodeRecycler.recycle(windowRoot) // obtained here; activeRoot is the caller's
        }
    }

    /** A PIN / passcode / CVV field: never typed into, in any app. */
    @JvmStatic
    fun isPinField(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
        val id = node.viewIdResourceName.orEmpty().substringAfter(":id/").replace('_', ' ')
        val desc = node.contentDescription?.toString().orEmpty()
        val labels = "$hint $id $desc"
        // "PIN code" / "pincode" in India is the postal code (delivery address forms): not a secret PIN.
        val postal = POSTAL_PIN.containsMatchIn(labels)
        if (!postal && PIN_HINT.containsMatchIn(labels)) return true
        // Password-type field in a UPI / bank app (PIN pads, login PINs).
        return node.isPassword && isUpiOrBankPackage(node.packageName)
    }

    /** Text entry check: block PIN fields everywhere and any typing on a payment screen of a UPI / bank app. */
    @JvmStatic
    fun blocksTextInput(node: AccessibilityNodeInfo?, activeRoot: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (isPinField(node)) {
            XLog.w(TAG, "Blocked typing into a PIN / passcode field")
            return true
        }
        return isStrictBlockedNode(node, activeRoot)
    }
}
