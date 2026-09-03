// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.NodeFinder
import io.agents.pokeclaw.utils.XLog

/**
 * Fast E-Commerce Cart Reader Tool.
 * Directly extracts cart item count, total bill amount, individual item titles, and view-cart button state from
 * Blinkit, Zepto, Flipkart Minutes, Amazon, or Instamart in < 100ms without full tree dumps.
 */
class ReadCartTool : BaseTool() {

    override fun getName(): String = "read_cart"

    override fun getDisplayName(): String = "Read Cart"

    override fun getDescriptionEN(): String =
        "Fast reader to inspect cart item count, total bill amount, individual item titles, and view-cart state in Blinkit, Zepto, Flipkart, or Amazon."

    override fun getDescriptionCN(): String =
        "快速查看 Blinkit、Zepto、Flipkart 或 Amazon 购物车中的商品数量、列表和总价。"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService()
            ?: return ToolResult.error("Accessibility service is not running")

        val root = service.rootInActiveWindow
            ?: return ToolResult.error("Screen unavailable")

        val pkg = root.packageName?.toString().orEmpty()

        val cartCountNode = NodeFinder.findNodeByIdOrText(root,
            "com.grofers.customerapp:id/tv_cart_count",
            "com.grofers.customerapp:id/tv_items",
            "com.zeptoconsumerapp:id/tv_cart_count",
            "com.zeptoconsumerapp:id/tv_quantity",
            "com.flipkart.android:id/cart_count",
            "com.flipkart.android:id/tv_cart_count",
            "com.amazon.mShop.android.shopping:id/rs_shopping_cart_count",
            "items in cart", "item in cart", "view cart"
        )

        val cartPriceNode = NodeFinder.findNodeByIdOrText(root,
            "com.grofers.customerapp:id/tv_cart_price",
            "com.grofers.customerapp:id/tv_price",
            "com.zeptoconsumerapp:id/tv_cart_price",
            "com.zeptoconsumerapp:id/tv_price",
            "com.flipkart.android:id/tv_price",
            "com.flipkart.android:id/cart_total"
        )

        val countText = cartCountNode?.text?.toString() ?: cartCountNode?.contentDescription?.toString() ?: ""
        val priceText = cartPriceNode?.text?.toString() ?: cartPriceNode?.contentDescription?.toString() ?: ""

        val viewCartBtn = NodeFinder.findNodeByIdOrText(root,
            "com.grofers.customerapp:id/view_cart_button",
            "com.grofers.customerapp:id/btn_view_cart",
            "com.zeptoconsumerapp:id/btn_view_cart",
            "com.flipkart.android:id/view_cart_cta",
            "view cart", "go to cart"
        )

        // Collect visible cart item titles
        val itemTitles = mutableListOf<String>()
        collectCartItemTitles(root, itemTitles)

        val hasCartBar = cartCountNode != null || cartPriceNode != null || viewCartBtn != null || itemTitles.isNotEmpty()

        return if (hasCartBar) {
            val summary = buildString {
                append("Cart Summary on ").append(pkg).append(":")
                if (countText.isNotBlank()) append(" Count: ").append(countText)
                if (priceText.isNotBlank()) append(" Total: ").append(priceText)
                if (itemTitles.isNotEmpty()) {
                    append(" Item List: ").append(itemTitles.take(8).joinToString(", "))
                }
                if (viewCartBtn != null) append(" [View Cart button active]")
            }
            XLog.i("ReadCartTool", summary)
            ToolResult.success(summary)
        } else {
            ToolResult.success("Cart is currently empty or no cart summary bar visible on $pkg.")
        }
    }

    private fun collectCartItemTitles(node: AccessibilityNodeInfo?, results: MutableList<String>) {
        if (node == null || !node.isVisibleToUser) return

        val resId = node.viewIdResourceName.orEmpty().lowercase()
        val text = node.text?.toString().orEmpty().trim()
        val desc = node.contentDescription?.toString().orEmpty().lowercase()

        // Exclude promotional / suggested item widgets
        if (resId.contains("promotional") || resId.contains("suggestion") || resId.contains("recommend") ||
            desc.contains("suggested for you") || desc.contains("frequently bought") || desc.contains("you may also like")) {
            return // Skip promotional widget sub-trees completely
        }

        if ((resId.contains("item_title") || resId.contains("tv_item_name") || resId.contains("product_name") || resId.contains("title")) && text.length > 2) {
            if (!results.contains(text) && !text.equals("view cart", ignoreCase = true) && !text.contains("total", ignoreCase = true) && !text.contains("price details", ignoreCase = true)) {
                results.add(text)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) collectCartItemTitles(child, results)
        }
    }
}
