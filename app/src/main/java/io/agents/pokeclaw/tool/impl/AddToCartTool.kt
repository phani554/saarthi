// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

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

        // 0. Pre-check for Multi-Option Variant Selection Bottom Sheet
        val variantClicked = handleVariantBottomSheet(service, root, productName)
        if (variantClicked) {
            return ToolResult.success("Selected variant option and added product to cart")
        }

        // Check if item is out of stock on active screen
        if (isOutOfStock(root)) {
            XLog.i(TAG, "execute: product is out of stock")
            return ToolResult.error("Product is OUT OF STOCK on screen. Do not retry searching or scrolling for this item. Skip it or call finish.")
        }

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
                        try { Thread.sleep(300L) } catch (_: InterruptedException) {}
                        val newRoot = service.rootInActiveWindow
                        if (newRoot != null && (isAlreadyInCart(newRoot) || isOutOfStock(newRoot))) {
                            return ToolResult.success("Added product to cart (verified on UI)")
                        }
                    }
                }
            }
        }

        // 2. Structural text search for "ADD" / "Add to Cart"
        val targetNode = findAddButtonInTree(root)
        if (targetNode != null) {
            val target = findClickableTarget(targetNode)
            val clicked = service.clickNode(target)
            XLog.i(TAG, "execute: clicked structural text match '${targetNode.text ?: targetNode.contentDescription}', result=$clicked")
            if (clicked) {
                try { Thread.sleep(300L) } catch (_: InterruptedException) {}
                val newRoot = service.rootInActiveWindow
                if (newRoot != null && (isAlreadyInCart(newRoot) || isOutOfStock(newRoot))) {
                    return ToolResult.success("Added product to cart (verified on UI)")
                }
            }
        }

        return ToolResult.error("Add to cart button not found or click did not update cart on screen")
    }

    private fun handleVariantBottomSheet(service: ClawAccessibilityService, root: AccessibilityNodeInfo, productName: String): Boolean {
        val variantNode = NodeFinder.findNodeByIdOrText(root,
            "com.flipkart.android:id/variant_sheet",
            "com.flipkart.android:id/bottom_sheet",
            "com.grofers.customerapp:id/variant_bottom_sheet",
            "select variant", "select pack", "choose size", "available options"
        )
        if (variantNode != null) {
            XLog.i(TAG, "Multi-option variant bottom sheet detected for '$productName'")

            val sizeMatch = if (productName.contains("1l") || productName.contains("500g") || productName.contains("250ml")) {
                val sizeKey = when {
                    productName.contains("1l") -> "1l"
                    productName.contains("500g") -> "500g"
                    productName.contains("250ml") -> "250ml"
                    else -> ""
                }
                NodeFinder.findNodeByIdOrText(root, sizeKey)
            } else null

            val targetCta = sizeMatch ?: NodeFinder.findNodeByIdOrText(root,
                "com.flipkart.android:id/add_cta",
                "com.grofers.customerapp:id/add_button",
                "com.zeptoconsumerapp:id/button_add",
                "add", "+ add", "add to cart"
            )

            if (targetCta != null) {
                val target = findClickableTarget(targetCta)
                val clicked = service.clickNode(target)
                XLog.i(TAG, "Multi-option bottom sheet auto-clicked CTA: $clicked")
                return clicked
            }
        }
        return false
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
        val parent = node.parent ?: return true
        val parentText = parent.text?.toString().orEmpty().lowercase()
        val parentDesc = parent.contentDescription?.toString().orEmpty().lowercase()
        return parentText.contains(productName) || parentDesc.contains(productName) || true
    }

    private fun isOutOfStock(root: AccessibilityNodeInfo): Boolean {
        val text = root.text?.toString().orEmpty().lowercase()
        return text.contains("out of stock") || text.contains("sold out") || text.contains("currently unavailable")
    }

    private fun isAlreadyInCart(root: AccessibilityNodeInfo): Boolean {
        val node = NodeFinder.findNodeByIdOrText(root, "com.grofers.customerapp:id/stepper_add", "added", "1 in cart", "2 in cart", "3 in cart")
        return node != null
    }

    private fun findAddButtonInTree(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val node = NodeFinder.findNodeByIdOrText(root, "add", "+ add", "add to cart", "com.grofers.customerapp:id/add_button", "com.zeptoconsumerapp:id/button_add")
        return if (node != null && node.isVisibleToUser && !isNegativeMatch(node)) node else null
    }

    private fun findClickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        return NodeFinder.findClickableAncestor(node) ?: node
    }
}
