// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * How an old tool result is shortened when history is compressed.
 *
 * Tools that READ data the task needs later (her contact's item list, the cart,
 * notifications) keep their result: cutting them to 80 chars made the model lose
 * the list and reopen WhatsApp again and again (run 2026-11-18 23:57, 25 calls,
 * open_app x5, max iterations).
 */
object ToolResultCompressor {

    /** Results the rest of the task depends on: never cut to a one-line summary. */
    val DATA_TOOLS = setOf(
        "read_message_items",
        "read_cart",
        "get_notifications",
        "get_group_task_summary",
        "inspect_memory",
    )

    /** Cap for a pinned data result so one huge read can't blow the prompt. */
    const val DATA_CAP = 1500

    private val GSON = Gson()

    fun compress(toolName: String, text: String, placeholder: String?): String {
        if (text.length <= 100) return text
        if (toolName in DATA_TOOLS) {
            return if (text.length <= DATA_CAP) text else text.take(DATA_CAP) + "...[cut]"
        }
        if (placeholder != null) return placeholder
        return summarize(text)
    }

    fun summarize(resultJson: String): String = try {
        val mapType = object : TypeToken<Map<String, Any?>>() {}.type
        val map: Map<String, Any?> = GSON.fromJson(resultJson, mapType)
        val isSuccess = map["isSuccess"] as? Boolean ?: false
        if (isSuccess) {
            val data = map["data"]?.toString() ?: "ok"
            "✓ " + if (data.length > 80) data.take(80) + "..." else data
        } else {
            val error = map["error"]?.toString() ?: "failed"
            "✗ " + if (error.length > 80) error.take(80) + "..." else error
        }
    } catch (_: Exception) {
        if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
    }
}
