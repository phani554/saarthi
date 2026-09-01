// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
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
            "com.grofers.customerapp:id/tv_title",
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

        private val NEGATIVE_KEYWORDS = arrayOf(
            "wishlist", "favorite", "favourite", "address", "note", "review",
            "coupon", "location", "sort", "filter", "notify", "heart"
        )

        private val EXACT_ADD_TEXTS = arrayOf(
            "add", "+ add", "add +", "+", "添加", "加入"
        )

        private val MULTIWORD_ADD_TEXTS = arrayOf(
            "add to cart", "add item", "add to basket", "add to bag",
            "add to order", "加入购物车", "加入購物車"
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

        val root = service.rootInActiveWindow
            ?: return ToolResult.error("Screen unavailable")

        // Check if item is already added to cart on active screen
        if (isAlreadyInCart(root)) {
            XLog.i(TAG, "execute: product already added to cart")
            return ToolResult.success("Product is already in cart")
        }

        // 1. Try ID matches first (ensuring negative keywords like wishlist are excluded)
        for (viewId in ADD_CART_IDS) {
            val matches = service.findNodesById(viewId)
            for (node in matches) {
                if (node.isVisibleToUser && !isNegativeMatch(node) && matchesProductContext(node, productName)) {
                    val target = findClickableTarget(node)
                    val clicked = service.clickNode(target)
                    XLog.i(TAG, "execute: clicked ID match $viewId, result=$clicked")
                    if (clicked) {
                        return ToolResult.success("Added product to cart (via ID match)")
                    }
                }
            }
        }

        // 2. Structural text search for "ADD" / "Add to Cart"
        val targetNode = findAddButtonInTree(root, productName)
        if (targetNode != null) {
            val target = findClickableTarget(targetNode)
            val clicked = service.clickNode(target)
            XLog.i(TAG, "execute: clicked structural text match '${targetNode.text ?: targetNode.contentDescription}', result=$clicked")
            if (clicked) {
                return ToolResult.success("Added product to cart")
            }
        }

        return ToolResult.error("Add to cart button not found on screen")
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

    private fun isAlreadyInCart(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || !node.isVisibleToUser) return false
        val text = node.text?.toString().orEmpty().lowercase()
        val desc = node.contentDescription?.toString().orEmpty().lowercase()
        if (text.contains("1 in cart") || desc.contains("1 in cart") ||
            text.contains("item added") || desc.contains("item added") ||
            (text.contains("+") && text.contains("-") && (text.contains("1") || text.contains("2")))) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (isAlreadyInCart(node.getChild(i))) return true
        }
        return false
    }

    private fun findClickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (node.isClickable) return node
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }
        return node
    }

    private fun matchesProductContext(node: AccessibilityNodeInfo, productName: String): Boolean {
        if (productName.isEmpty()) return true
        val keywords = productName.split(" ").map { it.trim() }.filter { it.length > 2 }
        if (keywords.isEmpty()) return true

        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 6) {
            val text = parent.text?.toString().orEmpty().lowercase()
            val desc = parent.contentDescription?.toString().orEmpty().lowercase()
            val combined = "$text $desc"
            for (kw in keywords) {
                if (combined.contains(kw)) {
                    return true
                }
            }
            parent = parent.parent
            depth++
        }
        return true
    }

    private fun findAddButtonInTree(node: AccessibilityNodeInfo?, productName: String): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null

        if (isNegativeMatch(node)) return null

        val text = node.text?.toString().orEmpty().trim().lowercase()
        val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()

        var isMatch = false

        // 1. Check exact match for short keywords ("add", "+", etc.)
        for (exact in EXACT_ADD_TEXTS) {
            if (text == exact || desc == exact) {
                isMatch = true
                break
            }
        }

        // 2. Check multi-word phrase matches
        if (!isMatch) {
            for (multi in MULTIWORD_ADD_TEXTS) {
                if (text == multi || desc == multi || text.contains(multi) || desc.contains(multi)) {
                    isMatch = true
                    break
                }
            }
        }

        if (isMatch && matchesProductContext(node, productName)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val match = findAddButtonInTree(node.getChild(i), productName)
            if (match != null) return match
        }
        return null
    }
}
