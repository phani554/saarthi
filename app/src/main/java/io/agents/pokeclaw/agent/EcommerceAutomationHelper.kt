// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.SearchBarService
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.tool.impl.AddToCartTool
import io.agents.pokeclaw.utils.NodeFinder
import io.agents.pokeclaw.utils.XLog

/**
 * Ultra-fast E-Commerce & Quick Commerce Automation Engine for Blinkit, Zepto, Flipkart Minutes, Amazon, etc.
 * Combines search bar navigation, query typing, product search, and cart addition into
 * 1 fast, smooth step (< 800ms) without multi-turn LLM inference latency.
 */
object EcommerceAutomationHelper {

    private const val TAG = "EcommerceHelper"

    /**
     * Fast 1-step Search + Add to Cart.
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

        // 3. Brief event-driven pause for search result cards
        try {
            Thread.sleep(500L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
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
     * Check if a task prompt requests multiple e-commerce products (e.g. "order milk, bread, pepsi").
     * Extracts items and processes them sequentially in 1 fast pass.
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
