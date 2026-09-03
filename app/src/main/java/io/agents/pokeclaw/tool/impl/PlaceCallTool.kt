// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.MultiModelAgentOrchestrator
import io.agents.pokeclaw.agent.knowledge.ContactAliasResolver
import io.agents.pokeclaw.service.VoiceManager
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.ContactListUiUtils
import io.agents.pokeclaw.utils.ContactMatchUtils
import io.agents.pokeclaw.utils.XLog

/**
 * Custom tool for placing Phone Calls, WhatsApp Voice Calls, and WhatsApp Video Calls.
 *
 * CRITICAL RULE: Once the call is placed or active call screen appears,
 * this tool immediately halts all background agent threads and speech via MultiModelAgentOrchestrator.killAllTasks(),
 * leaving the user undisturbed on the active call screen!
 */
class PlaceCallTool : BaseTool() {

    override fun getName(): String = "place_call"

    override fun getDescriptionEN(): String =
        "Place a Phone Call, WhatsApp Voice Call, or WhatsApp Video Call to a contact or phone number. " +
        "Automatically halts all background agent tasks so the user stays undisturbed on the active call screen."

    override fun getDescriptionCN(): String =
        "拨打电话、WhatsApp 语音电话或 WhatsApp 视频电话。自动停止所有后台任务，让用户保持在通话界面。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("contact", "string", "Target contact name, nickname, or phone number", true),
        ToolParameter("call_type", "string", "Type of call: 'phone', 'whatsapp_voice', or 'whatsapp_video'", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val rawContact = optionalString(params, "contact", "").trim()
        val callType = optionalString(params, "call_type", "phone").trim().lowercase().ifBlank { "phone" }

        if (rawContact.isBlank()) {
            return ToolResult.error("Missing required parameter: contact")
        }

        val context = ClawApplication.instance
        val resolvedContact = ContactAliasResolver.resolve(rawContact)
        XLog.i("PlaceCallTool", "Placing $callType call to '$resolvedContact' (raw: '$rawContact')")

        return try {
            when (callType) {
                "phone" -> executePhoneCall(context, resolvedContact)
                "whatsapp_voice", "whatsapp_video" -> executeWhatsAppCall(context, resolvedContact, callType == "whatsapp_video")
                else -> executePhoneCall(context, resolvedContact)
            }
        } catch (e: Exception) {
            XLog.e("PlaceCallTool", "Error executing place_call", e)
            ToolResult.error("Failed to place call: ${e.message}")
        }
    }

    private fun executePhoneCall(context: Context, contact: String): ToolResult {
        val numberMatch = Regex("""[\d\s\-+()]{7,}""").find(contact)
        val number = numberMatch?.value?.replace(Regex("""[\s\-()]"""), "") ?: ""

        if (number.isNotEmpty()) {
            VoiceManager.speakNative("Calling $contact now...", flush = true)
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(callIntent)
                MultiModelAgentOrchestrator.killAllTasks()
                ToolResult.success("Calling $contact ($number) now. Active call screen active.")
            } catch (_: Exception) {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                MultiModelAgentOrchestrator.killAllTasks()
                ToolResult.success("Opened dialer for $contact ($number).")
            }
        } else {
            VoiceManager.speakNative("Opening dialer for $contact...", flush = true)
            val dialerIntent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialerIntent)
            MultiModelAgentOrchestrator.killAllTasks()
            return ToolResult.success("Opened Phone dialer for $contact.")
        }
    }

    private fun executeWhatsAppCall(context: Context, contact: String, isVideo: Boolean): ToolResult {
        val modeText = if (isVideo) "WhatsApp Video Call" else "WhatsApp Voice Call"
        VoiceManager.speakNative("Starting $modeText with $contact...", flush = true)

        val service = requireAccessibilityService()
        if (service == null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
            MultiModelAgentOrchestrator.killAllTasks()
            return ToolResult.success("Opened WhatsApp for $modeText with $contact.")
        }

        // 1. Open WhatsApp
        val opened = OpenAppTool.openAppWithInterceptHandling(service, "com.whatsapp")
        if (!opened) {
            return ToolResult.error("Failed to open WhatsApp")
        }
        try { Thread.sleep(1200) } catch (_: InterruptedException) {}

        // 2. Open chatroom for target contact
        if (!ContactListUiUtils.prepareForContactLookup(service, "com.whatsapp", 3, 800)) {
            return ToolResult.error("Could not reach searchable WhatsApp chat list")
        }

        val normalized = ContactMatchUtils.buildNormalizedAliases(contact)
        val digit = ContactMatchUtils.buildDigitAliases(contact)
        val chatOpened = ContactListUiUtils.searchOrScrollAndFindAndClick(service, contact, normalized, digit, 8, 800)
        if (!chatOpened) {
            return ToolResult.error("Could not find contact '$contact' in WhatsApp")
        }

        try { Thread.sleep(1000) } catch (_: InterruptedException) {}

        // 3. Locate and tap call icon in top action bar
        val root = service.rootInActiveWindow
        if (root != null) {
            val callBtn = findCallButtonNode(root, isVideo)
            if (callBtn != null) {
                service.clickNode(callBtn)
                XLog.i("PlaceCallTool", "Tapped $modeText icon for $contact")
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}

                // Handle potential confirmation dialog ("Start video call?")
                val dialogRoot = service.rootInActiveWindow
                if (dialogRoot != null) {
                    val confirmBtn = findCallConfirmNode(dialogRoot)
                    if (confirmBtn != null) {
                        service.clickNode(confirmBtn)
                        XLog.i("PlaceCallTool", "Tapped call confirmation button")
                    }
                }
            } else {
                XLog.w("PlaceCallTool", "Call icon not found in action bar, opened chatroom")
            }
        }

        VoiceManager.stop()
        MultiModelAgentOrchestrator.killAllTasks()
        return ToolResult.success("Initiated $modeText with $contact on WhatsApp.")
    }

    private fun findCallButtonNode(root: AccessibilityNodeInfo, isVideo: Boolean): AccessibilityNodeInfo? {
        val targetId = if (isVideo) "com.whatsapp:id/video_call_btn" else "com.whatsapp:id/voice_call_btn"
        val altId = if (isVideo) "com.whatsapp:id/action_video_call" else "com.whatsapp:id/action_voice_call"
        val keyword = if (isVideo) "video" else "voice"

        val nodes = root.findAccessibilityNodeInfosByViewId(targetId)
        if (!nodes.isNullOrEmpty()) return nodes[0]

        val altNodes = root.findAccessibilityNodeInfosByViewId(altId)
        if (!altNodes.isNullOrEmpty()) return altNodes[0]

        return searchNodeByDesc(root, keyword)
    }

    private fun findCallConfirmNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return searchNodeByTextOrDesc(root, "Call") ?: searchNodeByTextOrDesc(root, "Start")
    }

    private fun searchNodeByDesc(node: AccessibilityNodeInfo?, keyword: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains(keyword) && (desc.contains("call") || desc.contains("button"))) return node
        for (i in 0 until node.childCount) {
            val res = searchNodeByDesc(node.getChild(i), keyword)
            if (res != null) return res
        }
        return null
    }

    private fun searchNodeByTextOrDesc(node: AccessibilityNodeInfo?, textStr: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val txt = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if ((txt == textStr.lowercase() || desc == textStr.lowercase()) && node.isClickable) return node
        for (i in 0 until node.childCount) {
            val res = searchNodeByTextOrDesc(node.getChild(i), textStr)
            if (res != null) return res
        }
        return null
    }
}
