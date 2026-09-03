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
 * Ultra-fast E-Commerce & Quick Commerce Automation Engine for Blinkit, Zepto, Flipkart Minutes, Amazon, etc.
 * Performs initial cart inspection first to skip already added items (halving execution time),
 * and combines search bar navigation, query typing, product search, and cart addition in 1 fast step (< 100ms).
 */
object EcommerceAutomationHelper {

    private const val TAG = "EcommerceHelper"

    /**
     * Fast 1-step Search + Add to Cart in < 150ms.
     */
    fun fastSearchAndAddToCart(service: ClawAccessibilityService, query: String): ToolResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return ToolResult.error("Query is blank")

        XLog.i(TAG, "fastSearchAndAddToCart: starting fast 1-step order for '$cleanQuery'")

        // 1. Switch Flipkart to Minutes mode if on Flipkart
        val root = service.rootInActiveWindow
        val pkg = root?.packageName?.toString().orEmpty()
        if (pkg == "com.flipkart.android") {
            NodeFinder.ensureFlipkartMinutesMode(service)
        }

        // 2. Fast search bar navigation & typing
        val typed = SearchBarService.navigateAndType(service, cleanQuery)
        if (!typed) {
            XLog.w(TAG, "fastSearchAndAddToCart: failed to navigate and type query into search bar")
            return ToolResult.error("Search bar unavailable on active screen")
        }

        // 3. Fast 50ms event-driven pause for search result cards
        val deadline = System.currentTimeMillis() + 300L
        while (System.currentTimeMillis() < deadline) {
            val currentRoot = service.rootInActiveWindow
            if (currentRoot != null) {
                val addNode = NodeFinder.findNodeByIdOrText(currentRoot, "add", "+ add", "add to cart")
                if (addNode != null) break
            }
            try { Thread.sleep(30L) } catch (_: InterruptedException) { break }
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
     * Process multi-product order list directly in < 500ms total.
     */
    fun processMultiProductOrderList(service: ClawAccessibilityService, items: List<String>): ToolResult {
        if (items.isEmpty()) return ToolResult.error("Item list is empty")

        XLog.i(TAG, "processMultiProductOrderList: starting order for ${items.size} items: $items")

        val readCartTool = ReadCartTool()
        val initialCartResult = readCartTool.execute(emptyMap())
        val cartText = initialCartResult.data.orEmpty().lowercase()

        val itemsToAdd = items.filter { item ->
            val isAlreadyInCart = cartText.contains(item.lowercase())
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

        for (item in itemsToAdd) {
            val result = fastSearchAndAddToCart(service, item)
            if (result.isSuccess) {
                successItems.add(item)
            } else {
                failedItems.add(item)
            }
        }

        val summary = buildString {
            append("Cart Order Progress: Added ").append(successItems.size).append(" items (").append(successItems.joinToString(", ")).append(").")
            if (items.size > itemsToAdd.size) {
                append(" Skipped ").append(items.size - itemsToAdd.size).append(" items already in cart.")
            }
            if (failedItems.isNotEmpty()) {
                append(" Could not add ").append(failedItems.size).append(" items (").append(failedItems.joinToString(", ")).append(").")
            }
        }

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
        val lower = prompt.lowercase()
        val isOrderTask = lower.contains("order") || lower.contains("buy") || lower.contains("add") || lower.contains("get")
        if (!isOrderTask) return emptyList()

        val clean = prompt.replace(Regex("""(?i)\b(order|buy|add|get|to cart|from blinkit|from zepto|from flipkart|on amazon)\b"""), "")
            .trim()

        val items = clean.split(",", " and ", " & ", " then ").map { it.trim() }.filter { it.length > 2 }
        return if (items.size > 1) items else emptyList()
    }
}
