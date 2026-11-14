// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import io.agents.pokeclaw.agent.knowledge.ContactAliasResolver
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog

/**
 * WhatsApp forward.
 *
 * The previous flow opened the TARGET chat, never selected a source message, clicked any node
 * containing "forward" (which also matches a "Forwarded" label on a bubble) and reported
 * "Forwarded" without checking anything. Until a real flow exists (find the bubble matching
 * message_text -> long-press -> forward icon by id -> pick target -> send -> verify), this tool
 * does nothing on screen and says so honestly, pointing the agent at send_message, which does
 * verify the recipient and the send.
 */
class WhatsAppForwardTool : BaseTool() {

    override fun getName(): String = "forward_whatsapp_message"

    override fun getDescriptionEN(): String =
        "Forwarding WhatsApp messages is not supported yet. To share a message's text with someone, " +
            "use send_message with that text instead."

    override fun getDescriptionCN(): String =
        "暂不支持转发 WhatsApp 消息。如需分享内容，请使用 send_message 发送该文本。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("target_contact", "string", "Target contact or group name to forward message to", true),
        ToolParameter("message_text", "string", "Text or content of message being forwarded", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val target = optionalString(params, "target_contact", "").trim()
        val text = optionalString(params, "message_text", "").trim()
        if (target.isBlank()) {
            return ToolResult.error("Missing required parameter: target_contact")
        }
        val resolvedTarget = ContactAliasResolver.resolve(target)
        XLog.w("WhatsAppForwardTool", "forward requested to '$resolvedTarget'; forwarding is not implemented, nothing done")
        val hint = if (text.isNotBlank()) {
            "Use send_message with contact='$target' and message set to the text instead."
        } else {
            "Ask the user what the message says, then use send_message to send that text to '$target'."
        }
        return ToolResult.error("Nothing was forwarded: WhatsApp forwarding is not supported yet. $hint")
    }
}
