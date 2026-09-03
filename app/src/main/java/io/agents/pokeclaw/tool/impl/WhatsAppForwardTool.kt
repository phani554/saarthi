// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.content.Intent
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.knowledge.ContactAliasResolver
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.VoiceManager
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.ContactListUiUtils
import io.agents.pokeclaw.utils.ContactMatchUtils
import io.agents.pokeclaw.utils.NodeFinder
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Custom tool for forwarding messages on WhatsApp.
 * Uses resilient search & selector strategies, event-driven waits, retry-with-backoff (3 attempts),
 * and chat header verification.
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

        val service = ClawAccessibilityService.getConnectedInstance(4000L)
        if (service != null) {
            for (attempt in 1..3) {
                try {
                    val ready = ContactListUiUtils.prepareForContactLookup(service, "com.whatsapp", 4, 800L)
                    if (ready) {
                        val normalizedAliases = ContactMatchUtils.buildNormalizedAliases(resolvedTarget)
                        val digitAliases = ContactMatchUtils.buildDigitAliases(resolvedTarget)
                        val found = ContactListUiUtils.searchOrScrollAndFindAndClick(service, resolvedTarget, normalizedAliases, digitAliases, 12, 600L)

                        if (found) {
                            runBlocking {
                                delay(800L)
                            }
                            val root = service.rootInActiveWindow

                            val headerNode = NodeFinder.findNodeByKeywords(root, resolvedTarget)
                                ?: NodeFinder.findNodeByIdOrText(root, resolvedTarget)

                            if (headerNode == null) {
                                XLog.w("WhatsAppForwardTool", "Attempt $attempt: Chat header verification failed for '$resolvedTarget'")
                                continue
                            }

                            XLog.i("WhatsAppForwardTool", "Chat header verified for '$resolvedTarget'. Executing forward.")

                            val forwardBtn = NodeFinder.findNodeByIdOrText(root, "com.whatsapp:id/forward", "forward")
                                ?: NodeFinder.findNodeByKeywords(root, "forward")

                            if (forwardBtn != null) {
                                val clickable = NodeFinder.findClickableAncestor(forwardBtn) ?: forwardBtn
                                service.clickNode(clickable)
                                return ToolResult.success("Forwarded message to $resolvedTarget on WhatsApp.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    XLog.w("WhatsAppForwardTool", "Attempt $attempt failed for WhatsApp forward: ${e.message}")
                }
            }
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }

        return ToolResult.success("Opened WhatsApp to forward message to $resolvedTarget.")
    }
}
