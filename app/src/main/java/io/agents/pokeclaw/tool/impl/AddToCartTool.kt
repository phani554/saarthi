// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.NodeFinder
import io.agents.pokeclaw.utils.XLog

class AddToCartTool : BaseTool() {

    companion object {
        private const val TAG = "AddToCartTool"

        private val ADD_CART_IDS = arrayOf(
            // Blinkit
            "com.grofers.customerapp:id/add_button",
            "com.grofers.customerapp:id/btn_add",
            "com.grofers.customerapp:id/tv_add",
            "com.grofers.customerapp:id/fyt_add_button",
            "com.grofers.customerapp:id/stepper_add",
            "com.grofers.customerapp:id/v_add_button",
            "com.grofers.customerapp:id/add_to_cart",
            "com.grofers.customerapp:id/add_to_cart_btn",
            "com.grofers.customerapp:id/add_layout",
            "com.grofers.customerapp:id/btn_add_to_cart",
            // Zepto
            "com.zeptoconsumerapp:id/button_add",
            "com.zeptoconsumerapp:id/add_button",
            "com.zeptoconsumerapp:id/tv_add",
            "com.zeptoconsumerapp:id/add_to_cart",
            "com.zeptoconsumerapp:id/btn_add_to_cart",
            // Flipkart
            "com.flipkart.android:id/button_add_to_cart",
            "com.flipkart.android:id/add_to_cart_button",
            "com.flipkart.android:id/add_button",
            "com.flipkart.android:id/tv_add",
            "com.flipkart.android:id/add_cta",
            // Amazon
            "com.amazon.mShop.android.shopping:id/add_to_cart_button",
            "add-to-cart-button",
            "atc-declarative"
        )

        private val ITEMS_LABEL = Regex("""^\d+\s+items?\b""", RegexOption.IGNORE_CASE)
        private val STEPPER_SIGNS = setOf("+", "-", "−", "–")
        private val STOP_WORDS = setOf("the", "of", "and", "pack", "pouch", "with", "for", "in", "a")

        private val NEGATIVE_KEYWORDS = arrayOf(
            "wishlist", "favorite", "favourite", "address", "note", "review",
            "coupon", "location", "sort", "filter", "notify", "heart"
        )
    }

    override fun getName(): String = "add_to_cart"

    override fun getDisplayName(): String = "Add to Cart"

    override fun getDescriptionEN(): String =
        "Optimized tool to add a product to cart in Blinkit, Amazon, Flipkart, Zepto, or other shopping apps."

    override fun getDescriptionCN(): String =
        "优化的购物车添加工具，可在 Blinkit、Amazon、Flipkart 等电商应用中快速将商品加入购物车。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("product_name", "string", "Optional product name to match before adding to cart", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService()
            ?: return ToolResult.error("Accessibility service is not running")

        val productName = optionalString(params, "product_name", "").trim().lowercase()

        // Dismiss soft keyboard if open so lower screen ADD buttons are fully accessible
        service.dismissKeyboard()

        val root = service.rootInActiveWindow
            ?: return ToolResult.error("Screen unavailable")

        // 0. Multi-option variant selection bottom sheet
        val variantResult = handleVariantBottomSheet(service, root, productName)
        if (variantResult != null) return variantResult

        // Already in cart only if a quantity stepper / "in cart" label sits on THIS product's card.
        // (stepper_add is an ADD button id and "added" matched too broadly, so neither counts.)
        val inCartNode = NodeFinder.findNodeByIdOrText(root, "1 in cart", "2 in cart", "3 in cart")
        if (inCartNode != null && productName.isNotBlank() && matchesProductContext(inCartNode, productName)) {
            XLog.i(TAG, "execute: product already added to cart")
            return ToolResult.success("Product is already in cart")
        }

        // 1. ID matches (negative keywords like wishlist excluded). With a product name, only an
        // ADD button whose card mentions the product is used; never fall back to the first ADD.
        val visibleAddNodes = ADD_CART_IDS.flatMap { service.findNodesById(it) }
            .filter { it.isVisibleToUser && !isNegativeMatch(it) }
        val candidates = if (productName.isBlank()) visibleAddNodes
            else visibleAddNodes.filter { matchesProductContext(it, productName) }
        var candidate: AccessibilityNodeInfo? = candidates.firstOrNull()

        // 2. Structural text search for "ADD" / "Add to Cart"
        if (candidate == null) {
            val textNode = findAddButtonInTree(root)
            if (textNode != null && (productName.isBlank() || matchesProductContext(textNode, productName))) {
                candidate = textNode
            }
        }

        if (candidate == null) {
            if (productName.isNotBlank() && visibleAddNodes.isNotEmpty()) {
                return ToolResult.error("No ADD button on screen belongs to '$productName'. Not adding a different product. Scroll or search for it, or call add_to_cart with a shorter product_name.")
            }
            if (isOutOfStock(root, productName)) {
                return ToolResult.error("Product is OUT OF STOCK on screen. Do not retry searching or scrolling for this item. Skip it or call finish.")
            }
            return ToolResult.error("Add to cart button not found on screen")
        }

        // Out-of-stock, scoped to this product's card.
        if (cardIsOutOfStock(candidate)) {
            return ToolResult.error("Product is OUT OF STOCK on screen. Do not retry searching or scrolling for this item. Skip it or call finish.")
        }

        // Click exactly one candidate, then verify; never click a second product.
        return clickAndVerify(service, candidate, "Added product to cart")
    }

    /**
     * Clicks the candidate's clickable target once and polls up to ~1.5 s for proof: the cart
     * count/badge changed, or a quantity stepper appeared on the clicked card.
     */
    private fun clickAndVerify(service: ClawAccessibilityService, node: AccessibilityNodeInfo, okMessage: String): ToolResult {
        val target = findClickableTarget(node)
        if (BaseTool.isPaymentNodeOrAncestor(target)) {
            return ToolResult.error("Blocked: that control is a payment / place-order button. The user must complete payment themselves.")
        }
        val cardBounds = Rect()
        (target.parent ?: target).getBoundsInScreen(cardBounds)
        val before = cartSignature(service.rootInActiveWindow)
        val clicked = service.clickNode(target)
        XLog.i(TAG, "clickAndVerify: clicked '${node.text ?: node.contentDescription ?: node.viewIdResourceName}', result=$clicked")
        if (!clicked) return ToolResult.error("Could not click the ADD button")
        val deadline = System.currentTimeMillis() + 1500L
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(250L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return ToolResult.error("Interrupted while verifying add to cart")
            }
            val now = service.rootInActiveWindow ?: continue
            val after = cartSignature(now)
            if (after.isNotEmpty() && after != before) {
                return ToolResult.success("$okMessage (verified: cart count changed)")
            }
            if (stepperInBounds(now, cardBounds)) {
                return ToolResult.success("$okMessage (verified: quantity stepper on the product card)")
            }
        }
        return ToolResult.error("Tapped ADD but could not confirm the item reached the cart. Call get_screen_info to check before trying again; do not tap other products.")
    }

    /** Text of cart-count/badge nodes and "N items" cart-bar labels, joined. */
    private fun cartSignature(root: AccessibilityNodeInfo?): String {
        val out = StringBuilder()
        collectCartSignals(root, out, 0)
        return out.toString()
    }

    private fun collectCartSignals(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > 40) return
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val isCartId = id.contains("cart") && (id.contains("count") || id.contains("badge") || id.contains("quantity") || id.contains("item"))
        if ((isCartId && text.isNotEmpty()) || ITEMS_LABEL.containsMatchIn(text)) {
            out.append(id).append('=').append(text).append(';')
        }
        for (i in 0 until node.childCount) collectCartSignals(node.getChild(i), out, depth + 1)
    }

    /** True when the area holds a quantity number plus a +/- control, or an "in cart" label. */
    private fun stepperInBounds(root: AccessibilityNodeInfo, area: Rect): Boolean {
        val found = BooleanArray(2) // [0] digit, [1] plus/minus
        scanStepper(root, area, found, 0)
        return found[0] && found[1]
    }

    private fun scanStepper(node: AccessibilityNodeInfo?, area: Rect, found: BooleanArray, depth: Int) {
        if (node == null || depth > 40) return
        val r = Rect()
        node.getBoundsInScreen(r)
        if (Rect.intersects(r, area)) {
            val t = node.text?.toString()?.trim().orEmpty().lowercase()
            val d = node.contentDescription?.toString()?.trim().orEmpty().lowercase()
            if (t.matches(Regex("\\d{1,2}"))) found[0] = true
            if (t in STEPPER_SIGNS || d in STEPPER_SIGNS || d.contains("increase") || d.contains("decrease") ||
                d.contains("add one") || d.contains("remove one")) found[1] = true
            if (t.contains("in cart") || d.contains("in cart")) { found[0] = true; found[1] = true }
        }
        for (i in 0 until node.childCount) scanStepper(node.getChild(i), area, found, depth + 1)
    }

    private fun cardIsOutOfStock(node: AccessibilityNodeInfo): Boolean {
        var card: AccessibilityNodeInfo? = node.parent ?: return false
        repeat(2) {
            val text = subtreeText(card, 4)
            if (text.contains("out of stock") || text.contains("sold out") || text.contains("currently unavailable")) return true
            card = card?.parent ?: return false
        }
        return false
    }

    /** Returns null when no variant sheet is showing; otherwise the verified result. */
    private fun handleVariantBottomSheet(service: ClawAccessibilityService, root: AccessibilityNodeInfo, productName: String): ToolResult? {
        val variantNode = NodeFinder.findNodeByIdOrText(root,
            "com.flipkart.android:id/variant_sheet",
            "com.grofers.customerapp:id/variant_bottom_sheet",
            "select variant", "select pack", "choose size", "available options"
        ) ?: return null
        XLog.i(TAG, "Multi-option variant bottom sheet detected for '$productName'")

        // Size chip: exact (normalized) text match only; "1l" must not match "11l" or "1 litre pack of 2".
        val sizeKey = Regex("""\b\d+(\.\d+)?\s?(ml|l|g|kg)\b""").find(productName)?.value?.replace(" ", "")
        if (sizeKey != null) {
            val chip = findExactLabel(root, sizeKey)
            if (chip != null) {
                service.clickNode(findClickableTarget(chip))
                try { Thread.sleep(400L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }

        val latest = service.rootInActiveWindow ?: root
        val cta = NodeFinder.findNodeByIdOrText(latest,
            "com.flipkart.android:id/add_cta",
            "com.grofers.customerapp:id/add_button",
            "com.zeptoconsumerapp:id/button_add"
        ) ?: findExactLabel(latest, "add") ?: findExactLabel(latest, "+ add") ?: findExactLabel(latest, "add to cart")
        if (cta == null || isNegativeMatch(cta) || !cta.isVisibleToUser) {
            return ToolResult.error("A size/variant sheet is open but its ADD button was not found. Call get_screen_info and pick the variant with tap_node.")
        }
        return clickAndVerify(service, cta, "Selected variant and added product to cart")
    }

    private fun findExactLabel(node: AccessibilityNodeInfo?, label: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        val want = label.lowercase().replace(" ", "")
        val t = node.text?.toString()?.lowercase()?.replace(" ", "").orEmpty()
        val d = node.contentDescription?.toString()?.lowercase()?.replace(" ", "").orEmpty()
        if ((t == want || d == want) && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val hit = findExactLabel(node.getChild(i), label, depth + 1)
            if (hit != null) return hit
        }
        return null
    }

    private fun isNegativeMatch(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString().orEmpty().lowercase()
        val desc = node.contentDescription?.toString().orEmpty().lowercase()
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        for (neg in NEGATIVE_KEYWORDS) {
            if (text.contains(neg) || desc.contains(neg) || id.contains(neg)) {
                return true
            }
        }
        return false
    }

    private fun matchesProductContext(node: AccessibilityNodeInfo, productName: String): Boolean {
        if (productName.isBlank()) return true
        val tokens = tokenize(productName)
        if (tokens.isEmpty()) return true
        // Look at the product card (parent / grandparent subtree), not just the parent's own text.
        var card: AccessibilityNodeInfo? = node.parent ?: return false
        repeat(2) {
            val cardTokens = tokenize(subtreeText(card, 4)).toSet()
            val hits = tokens.count { it in cardTokens }
            if (hits * 10 >= tokens.size * 6) return true // >= 60% of the product's words
            card = card?.parent ?: return false
        }
        return false
    }

    /** Lowercase words, units and punctuation dropped (e.g. "500ml", "pack", "of" ignored). */
    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 && it !in STOP_WORDS && !it.matches(Regex("\\d+(\\.\\d+)?(ml|l|g|gm|kg|pcs|pc)?")) }
            .distinct()

    private fun subtreeText(node: AccessibilityNodeInfo?, depth: Int): String {
        if (node == null || depth < 0) return ""
        val sb = StringBuilder()
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) sb.append(subtreeText(node.getChild(i), depth - 1))
        return sb.toString().lowercase()
    }

    private fun isOutOfStock(root: AccessibilityNodeInfo, productName: String): Boolean {
        // Only when the sold-out label sits on this product's card (or no product was named).
        val found = NodeFinder.findNodeByIdOrText(root, "", "out of stock", "sold out", "currently unavailable")
            ?: return false
        return productName.isBlank() || matchesProductContext(found, productName)
    }

    private fun findAddButtonInTree(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val node = NodeFinder.findNodeByIdOrText(root, "add", "+ add", "add to cart", "com.grofers.customerapp:id/add_button", "com.zeptoconsumerapp:id/button_add")
        return if (node != null && node.isVisibleToUser && !isNegativeMatch(node)) node else null
    }

    private fun findClickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        return NodeFinder.findClickableAncestor(node) ?: node
    }
}
