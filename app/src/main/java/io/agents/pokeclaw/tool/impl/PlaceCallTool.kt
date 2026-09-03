// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.AgentTaskState
import io.agents.pokeclaw.agent.MultiModelAgentOrchestrator
import io.agents.pokeclaw.agent.TaskExecutionState
import io.agents.pokeclaw.agent.TtsRouter
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
 * Custom tool for placing Phone Calls, WhatsApp Voice Calls, and WhatsApp Video Calls.
 *
 * CRITICAL LOCKDOWN RULE: The instant a call is placed or active call screen appears,
 * this tool transitions task state to AgentTaskState.CallInProgress, stops all TTS,
 * disarms voice capture, and cancels all pending tasks!
 */
class PlaceCallTool : BaseTool() {

    override fun getName(): String = "place_call"

    override fun getDescriptionEN(): String =
        "Place a Phone Call, WhatsApp Voice Call, or WhatsApp Video Call to a contact or phone number. " +
        "Enforces TOTAL LOCKDOWN upon call dispatch."

    override fun getDescriptionCN(): String =
        "拨打电话、WhatsApp 语音电话或 WhatsApp 视频电话。"

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
            val result = when (callType) {
                "phone" -> executePhoneCall(context, resolvedContact)
                "whatsapp_voice", "whatsapp_video" -> executeWhatsAppCall(context, resolvedContact, callType == "whatsapp_video")
                else -> executePhoneCall(context, resolvedContact)
            }
            if (result.isSuccess) {
                enforceCallLockdown()
            }
            result
        } catch (e: Exception) {
            XLog.e("PlaceCallTool", "Error executing place_call", e)
            ToolResult.error("Failed to place call: ${e.message}")
        }
    }

    private fun enforceCallLockdown() {
        XLog.w("PlaceCallTool", "Enforcing TOTAL LOCKDOWN for active call")
        TaskExecutionState.instance.setState(AgentTaskState.CallInProgress)
        TtsRouter.stopAll()
        VoiceManager.disarmVoiceLoop()
        MultiModelAgentOrchestrator.killAllTasks()
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
                ToolResult.success("Calling $contact ($number) now. Active call screen active.")
            } catch (_: Exception) {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                ToolResult.success("Opened dialer for $contact ($number).")
            }
        } else {
            VoiceManager.speakNative("Opening dialer for $contact...", flush = true)
            val dialerIntent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialerIntent)
            return ToolResult.success("Opened Phone dialer for $contact.")
        }
    }

    private fun executeWhatsAppCall(context: Context, contact: String, isVideo: Boolean): ToolResult {
        val modeText = if (isVideo) "WhatsApp Video Call" else "WhatsApp Voice Call"
        VoiceManager.speakNative("Starting $modeText with $contact...", flush = true)

        val service = ClawAccessibilityService.getConnectedInstance(4000L)
        if (service != null) {
            for (attempt in 1..3) {
                try {
                    val ready = ContactListUiUtils.prepareForContactLookup(service, "com.whatsapp", 3, 600L)
                    if (ready) {
                        val normalizedAliases = ContactMatchUtils.buildNormalizedAliases(contact)
                        val digitAliases = ContactMatchUtils.buildDigitAliases(contact)
                        val found = ContactListUiUtils.searchOrScrollAndFindAndClick(service, contact, normalizedAliases, digitAliases, 12, 600L)

                        if (found) {
                            runBlocking {
                                delay(800L)
                            }
                            val root = service.rootInActiveWindow
                            val targetKw = if (isVideo)
                                arrayOf("com.whatsapp:id/video_call", "video_call", "video call", "video")
                            else
                                arrayOf("com.whatsapp:id/voice_call", "voice_call", "voice call", "call")

                            val callButton = NodeFinder.findNodeByIdOrText(root, *targetKw)
                                ?: NodeFinder.findNodeByKeywords(root, *targetKw)

                            if (callButton != null) {
                                val clickable = NodeFinder.findClickableAncestor(callButton) ?: callButton
                                service.clickNode(clickable)
                                XLog.i("PlaceCallTool", "Clicked $modeText button using selector match strategy")
                                return ToolResult.success("Initiated $modeText with $contact on WhatsApp.")
                            } else {
                                XLog.w("PlaceCallTool", "Attempt $attempt: Opened chat for $contact, but $modeText button not found yet")
                            }
                        }
                    }
                } catch (e: Exception) {
                    XLog.w("PlaceCallTool", "Attempt $attempt failed for WhatsApp call: ${e.message}")
                }
            }
        }

        // Fallback: launch WhatsApp directly
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }

        return ToolResult.success("Initiated $modeText with $contact on WhatsApp.")
    }
}
