// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.SearchBarService
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult

class FindSearchBarTool : BaseTool() {

    override fun getName(): String = "find_search_bar"

    override fun getDisplayName(): String = "Find Search Bar"

    override fun getDescriptionEN(): String =
        "Quickly navigates to the search bar in WhatsApp, Blinkit, Amazon, Flipkart, or any active app. " +
        "Pass 'query' to type directly into search bar."

    override fun getDescriptionCN(): String =
        "快速在 WhatsApp、Blinkit、Amazon、Flipkart 或任何应用中导航至搜索栏。传递 query 参数可直接输入。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("query", "string", "Optional search query to type directly into search bar", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService()
            ?: return ToolResult.error("Accessibility service is not running")

        val query = optionalString(params, "query", "").trim()

        if (query.isNotEmpty()) {
            val typed = SearchBarService.navigateAndType(service, query)
            return if (typed) {
                ToolResult.success("Navigated to search bar and typed '$query'")
            } else {
                ToolResult.error("Failed to type '$query' into search bar")
            }
        }

        val result = SearchBarService.findSearchBar(service)
            ?: return ToolResult.error("Search bar not found on active screen")

        val node = result.node ?: return ToolResult.error("Search bar node unavailable")
        val clicked = service.clickNode(node)

        return ToolResult.success("Found search bar in ${result.appPackage} (editable=${result.isEditable}), clicked=$clicked")
    }
}
