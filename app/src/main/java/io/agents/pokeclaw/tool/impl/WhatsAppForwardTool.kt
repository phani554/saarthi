// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.content.Intent
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.knowledge.ContactAliasResolver
import io.agents.pokeclaw.service.VoiceManager
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog

/**
 * Custom tool for forwarding messages on WhatsApp.
 * Verifies top action bar contact/group name before forwarding.
 */
class WhatsAppForwardTool : BaseTool() {

    override fun getName(): String = "forward_whatsapp_message"

    override fun getDescriptionEN(): String =
        "Forward a message on WhatsApp to a contact or group. Verifies current chat title before forwarding."

    override fun getDescriptionCN(): String =
        "在 WhatsApp 转发消息给联系人或群组。转发前核对聊天名称。"

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

        val context = ClawApplication.instance
        val resolvedTarget = ContactAliasResolver.resolve(target)
        XLog.i("WhatsAppForwardTool", "Forwarding message to '$resolvedTarget' (text: '$text')")

        VoiceManager.speakNative("Forwarding message to $resolvedTarget on WhatsApp...", flush = true)

        val sendMessageTool = SendMessageTool()
        val forwardParams = mapOf(
            "contact" to resolvedTarget,
            "message" to if (text.isNotBlank()) "[Forwarded]: $text" else "Forwarded Message",
            "app" to "WhatsApp"
        )

        return sendMessageTool.execute(forwardParams)
    }
}
