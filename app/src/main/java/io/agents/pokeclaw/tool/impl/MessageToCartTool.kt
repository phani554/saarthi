// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import io.agents.pokeclaw.agent.MessageToCartPipeline
import io.agents.pokeclaw.agent.OutboundConfirmGate
import io.agents.pokeclaw.agent.SpokenStrings
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult

/**
 * End-To-End Direct Message-To-Cart Tool (`message_to_cart`).
 * Reads shopping list from a WhatsApp contact message and adds all items directly to cart on Flipkart Minutes/Blinkit/Zepto
 * in 1 fast pass without multi-app oscillation or LLM delays.
 */
class MessageToCartTool : BaseTool() {

    override fun getName(): String = "message_to_cart"

    override fun getDisplayName(): String = "Message To Cart"

    override fun getDescriptionEN(): String =
        "Reads a shopping list from a WhatsApp contact message and adds all items directly to cart on Flipkart Minutes, Blinkit, or Zepto."

    override fun getDescriptionCN(): String =
        "从 WhatsApp 联系人消息中读取购物清单，并直接在 Flipkart Minutes、Blinkit 或 Zepto 上将所有商品加入购物车。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("contact", "string", "Contact name to read shopping message from (e.g. 'Kamya')", true),
        ToolParameter("store_app", "string", "Target store app (e.g. 'Flipkart Minutes', 'Blinkit', 'Zepto')", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService()
            ?: return ToolResult.error("Accessibility service is not running")

        val contact = requireString(params, "contact").trim()
        val storeApp = optionalString(params, "store_app", "Flipkart Minutes").trim()
        val storePackage = MessageToCartPipeline.storePackageFor(storeApp)
            ?: return ToolResult.error("Unknown store '$storeApp'. Use Blinkit, Zepto, Flipkart Minutes or Amazon.")

        // Second pass: the user said yes to the list read back to her; add exactly those items.
        val approvedItems = optionalString(params, ITEMS_PARAM, "")
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (approvedItems.isNotEmpty() && OutboundConfirmGate.consumeApproval(params)) {
            return MessageToCartPipeline.addToCart(service, storePackage, approvedItems)
        }

        // First pass: read and parse the list, then ask her before anything goes into a cart.
        val read = MessageToCartPipeline.readItems(service, contact)
        read.error?.let { return it }
        val parked = mapOf<String, Any>(
            "contact" to contact,
            "store_app" to storeApp,
            ITEMS_PARAM to read.items.joinToString("\n"),
        )
        return OutboundConfirmGate.check(
            getName(), parked,
            SpokenStrings.confirmShoppingList(read.items, storeApp),
            "nothing was added to the cart yet. Saathi reads these items to the user and adds them only " +
                "after they say yes: ${read.items.joinToString("; ")}. Stop here: do not call this tool again " +
                "and do not add these items any other way."
        ) ?: MessageToCartPipeline.addToCart(service, storePackage, read.items)
    }

    companion object {
        /** Internal param holding the read-back list (newline-separated); not offered to the model. */
        private const val ITEMS_PARAM = "__confirmed_items"
    }
}
