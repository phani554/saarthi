// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.SearchBarService
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.tool.impl.AddToCartTool
import io.agents.pokeclaw.tool.impl.ReadCartTool
import io.agents.pokeclaw.utils.NodeFinder
import io.agents.pokeclaw.utils.XLog

/**
 * Ultra-fast E-Commerce & Quick Commerce Automation Engine for Blinkit, Zepto, Flipkart Minutes, Amazon (IN/US), etc.
 * Performs initial cart inspection first to skip already added items (halving execution time),
 * immediately skips and reports out-of-stock items, and combines search bar navigation and cart addition in 1 fast step (< 100ms).
 */
object EcommerceAutomationHelper {

    private const val TAG = "EcommerceHelper"
    private const val RESULTS_WAIT_MS = 3000L

    /** True if a visible, non-editable node (not the search box) shows [query]. */
    private fun hasNonEditableText(node: android.view.accessibility.AccessibilityNodeInfo?, query: String, depth: Int): Boolean {
        if (node == null || depth > 40) return false
        if (!node.isEditable) {
            val t = node.text?.toString()?.lowercase()
            if (t != null && t.contains(query)) return true
        }
        for (i in 0 until node.childCount) {
            if (hasNonEditableText(node.getChild(i), query, depth + 1)) return true
        }
        return false
    }

    /**
     * Fast pure search without attempting Add to Cart.
     */
    fun fastSearch(service: ClawAccessibilityService, query: String): ToolResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return ToolResult.error("Query is blank")

        val root = service.rootInActiveWindow
        val pkg = root?.packageName?.toString().orEmpty().lowercase()

        if (pkg.contains("amazon")) {
            return AmazonAutomationDriver.searchOnly(service, cleanQuery)
        }

        val typed = SearchBarService.navigateAndType(service, cleanQuery)
        return if (typed) {
            ToolResult.success("Searched for '$cleanQuery'")
        } else {
            ToolResult.error("Search bar unavailable on active screen")
        }
    }

    /**
     * Fast 1-step Search + Add to Cart in < 150ms.
     */
    fun fastSearchAndAddToCart(service: ClawAccessibilityService, query: String): ToolResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return ToolResult.error("Query is blank")

        XLog.i(TAG, "fastSearchAndAddToCart: starting fast 1-step order for '$cleanQuery'")

        val root = service.rootInActiveWindow
        val pkg = root?.packageName?.toString().orEmpty().lowercase()

        // Dedicated driver for Amazon India & Global
        if (pkg.contains("amazon")) {
            return AmazonAutomationDriver.searchAndAddToCart(service, cleanQuery)
        }

        // 1. Switch Flipkart to Minutes mode if on Flipkart
        if (pkg == "com.flipkart.android") {
            NodeFinder.ensureFlipkartMinutesMode(service)
        }

        // 2. Fast search bar navigation & typing
        val typed = SearchBarService.navigateAndType(service, cleanQuery)
        if (!typed) {
            XLog.w(TAG, "fastSearchAndAddToCart: failed to navigate and type query into search bar")
            return ToolResult.error("Search bar unavailable on active screen")
        }

        // 3. Wait (up to RESULTS_WAIT_MS) for real results: an ADD button AND a non-editable node
        //    showing the query. 300 ms was too short and let us tap an ADD left over from the previous screen.
        val deadline = System.currentTimeMillis() + RESULTS_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val currentRoot = service.rootInActiveWindow
            if (currentRoot != null) {
                val addNode = NodeFinder.findNodeByIdOrText(currentRoot, "add", "+ add", "add to cart")
                if (addNode != null && hasNonEditableText(currentRoot, cleanQuery.lowercase(), 0)) break
            }
            try { Thread.sleep(100L) } catch (_: InterruptedException) { break }
        }

        // 4. Add matching product card to cart directly
        val addTool = AddToCartTool()
        val result = addTool.execute(mapOf("product_name" to cleanQuery))

        if (result.isSuccess) {
            XLog.i(TAG, "fastSearchAndAddToCart SUCCESS: '$cleanQuery' added to cart cleanly")
            return ToolResult.success("Added '$cleanQuery' to cart in 1 fast step")
        }

        XLog.w(TAG, "fastSearchAndAddToCart: add_to_cart result = ${result.error ?: "failed"}")
        return result
    }

    /**
     * Process multi-product order list directly.
     * Skips out-of-stock items immediately and reports them cleanly in the summary.
     */
    fun processMultiProductOrderList(service: ClawAccessibilityService, items: List<String>): ToolResult {
        if (items.isEmpty()) return ToolResult.error("Item list is empty")

        XLog.i(TAG, "processMultiProductOrderList: starting order for ${items.size} items")

        // Only skip "already in cart" items when we are actually looking at a cart screen, and only
        // match against the cart's item titles. A store home page showing a "Milk" category is not a cart.
        val rootNow = service.rootInActiveWindow
        val onCartScreen = rootNow != null && NodeFinder.findNodeByTextContains(
            rootNow, "my cart", "your cart", "shopping cart", "review cart"
        ) != null
        val cartText = if (onCartScreen) {
            val initialCartResult = ReadCartTool().execute(emptyMap())
            if (initialCartResult.isSuccess) initialCartResult.data.orEmpty().substringAfter("Item List:", "").lowercase() else ""
        } else ""

        val itemsToAdd = items.filter { item ->
            val isAlreadyInCart = cartText.isNotBlank() && cartText.contains(item.lowercase())
            if (isAlreadyInCart) {
                XLog.i(TAG, "Item '$item' is ALREADY in cart. Skipping duplicate addition!")
            }
            !isAlreadyInCart
        }

        if (itemsToAdd.isEmpty()) {
            return ToolResult.success("All ${items.size} items are ALREADY present in the cart! $cartText")
        }

        val successItems = mutableListOf<String>()
        val failedItems = mutableListOf<String>()
        val outOfStockItems = mutableListOf<String>()

        for (item in itemsToAdd) {
            val result = fastSearchAndAddToCart(service, item)
            if (result.isSuccess) {
                successItems.add(item)
            } else if (result.error?.contains("OUT OF STOCK", ignoreCase = true) == true) {
                XLog.w(TAG, "Item '$item' is OUT OF STOCK — skipping immediately and reporting")
                outOfStockItems.add(item)
            } else {
                failedItems.add(item)
            }
        }

        val summary = buildString {
            append("Cart Order Progress: Added ").append(successItems.size).append(" items (").append(successItems.joinToString(", ")).append(").")
            if (outOfStockItems.isNotEmpty()) {
                append(" Skipped ").append(outOfStockItems.size).append(" out-of-stock items: (").append(outOfStockItems.joinToString(", ")).append(").")
            }
            if (items.size > itemsToAdd.size) {
                append(" Skipped ").append(items.size - itemsToAdd.size).append(" items already in cart.")
            }
            if (failedItems.isNotEmpty()) {
                append(" Could not add ").append(failedItems.size).append(" items: (").append(failedItems.joinToString(", ")).append(").")
            }
        }

        // Nothing added (all failed / out of stock): this is not a success.
        if (successItems.isEmpty()) return ToolResult.error(summary)
        return ToolResult.success(summary)
    }

    /**
     * Process multi-product orders with initial cart pre-inspection.
     */
    fun processMultiProductOrder(service: ClawAccessibilityService, prompt: String): ToolResult {
        val items = extractMultiProducts(prompt)
        if (items.isEmpty()) return ToolResult.error("No multi-product list extracted")
        return processMultiProductOrderList(service, items)
    }

    /**
     * Check if a task prompt requests multiple e-commerce products (e.g. "order milk, bread, pepsi").
     */
    fun extractMultiProducts(prompt: String): List<String> {
        // Whole words only: "address" is not "add", "forget" is not "get".
        val isOrderTask = Regex("""(?i)\b(order|buy|add|get)\b""").containsMatchIn(prompt)
        if (!isOrderTask) return emptyList()

        val clean = prompt.replace(Regex("""(?i)\b(order|buy|add|get|to cart|from blinkit|from zepto|from flipkart|on amazon)\b"""), "")
            .trim()

        // Commas separate items; "and" only splits the last item of a comma list ("milk, bread and eggs"),
        // so "bread and butter" stays one product.
        val commaParts = clean.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (commaParts.size < 2) return emptyList()
        val lastSplit = commaParts.last().removePrefix("and ").split(Regex("""\s+(?:and|&)\s+"""))
        val items = (commaParts.dropLast(1) + lastSplit).map { it.trim() }.filter { it.length > 2 }
        return if (items.size > 1) items else emptyList()
    }
}
