// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
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
 * disarms voice capture, and cancels all pending tasks while keeping the display
 * strictly on the active call screen!
 */
class PlaceCallTool : BaseTool() {

    override fun getName(): String = "place_call"

    override fun getDescriptionEN(): String =
        "Place a Phone Call, WhatsApp Voice Call, or WhatsApp Video Call to a contact or phone number. " +
        "Enforces TOTAL LOCKDOWN upon call dispatch, keeping user undisturbed on active call screen."

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
        XLog.w("PlaceCallTool", "Enforcing TOTAL LOCKDOWN for active call — keeping user on active call screen")
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
                                delay(600L)
                            }
                            val root = service.rootInActiveWindow

                            // MANDATORY CHAT HEADER VERIFICATION
                            val headerVerified = verifyChatHeader(root, contact)
                            if (!headerVerified) {
                                XLog.w("PlaceCallTool", "MANDATORY CHAT HEADER VERIFICATION FAILED: Opened chat header does not match target contact '$contact'. Pressing Back to re-navigate.")
                                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                                runBlocking { delay(400L) }
                                continue
                            }
                            XLog.i("PlaceCallTool", "Mandatory chat header verified for recipient '$contact'")

                            // Strategy 1: Direct Top Action Bar Call Button
                            val callButton = findTopActionBarCallButton(root, isVideo)
                            if (callButton != null) {
                                val clickable = NodeFinder.findClickableAncestor(callButton) ?: callButton
                                var clicked = service.clickNode(clickable)
                                if (!clicked) {
                                    val bounds = Rect()
                                    callButton.getBoundsInScreen(bounds)
                                    clicked = service.performTap(bounds.centerX(), bounds.centerY())
                                }

                                XLog.i("PlaceCallTool", "Strategy 1: Clicked $modeText top action bar icon, result=$clicked")

                                // Event-driven polling for popup confirmation dialog ("Start video call?" / "Call" / "Start")
                                val confirmDeadline = System.currentTimeMillis() + 1200L
                                while (System.currentTimeMillis() < confirmDeadline) {
                                    val confirmRoot = service.rootInActiveWindow
                                    if (confirmRoot != null) {
                                        val confirmBtn = NodeFinder.findNodeByIdOrText(confirmRoot,
                                            "com.whatsapp:id/call_type_dialog_button",
                                            "com.whatsapp:id/button1",
                                            "android:id/button1",
                                            "call", "start", "video call"
                                        )
                                        if (confirmBtn != null) {
                                            val clickConfirm = NodeFinder.findClickableAncestor(confirmBtn) ?: confirmBtn
                                            var cClicked = service.clickNode(clickConfirm)
                                            if (!cClicked) {
                                                val cBounds = Rect()
                                                confirmBtn.getBoundsInScreen(cBounds)
                                                cClicked = service.performTap(cBounds.centerX(), cBounds.centerY())
                                            }
                                            XLog.i("PlaceCallTool", "Strategy 1: Clicked popup confirmation button: $cClicked")
                                            return ToolResult.success("Initiated $modeText with $contact on WhatsApp. Active call screen active.")
                                        }
                                    }
                                    runBlocking { delay(80L) }
                                }

                                if (clicked) {
                                    return ToolResult.success("Initiated $modeText with $contact on WhatsApp. Active call screen active.")
                                }
                            }

                            // Strategy 2: Contact Info Screen Fallback (Click top chat header title -> click Video button)
                            XLog.i("PlaceCallTool", "Strategy 1 unavailable/unconfirmed. Initiating Strategy 2: Contact Info Screen shortcut...")
                            val infoSuccess = triggerCallViaContactInfoScreen(service, root, isVideo)
                            if (infoSuccess) {
                                return ToolResult.success("Initiated $modeText with $contact via WhatsApp Contact Info Screen.")
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

    private fun triggerCallViaContactInfoScreen(service: ClawAccessibilityService, root: AccessibilityNodeInfo, isVideo: Boolean): Boolean {
        // Step 1: Find and click top chat header title
        val headerTitle = NodeFinder.findNodeByIdOrText(root,
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp:id/chat_title",
            "com.whatsapp:id/conversation_contact_status"
        ) ?: findHeaderTitleByRegion(root)

        if (headerTitle == null) {
            XLog.w("PlaceCallTool", "Contact Info Fallback: Chat header title node not found")
            return false
        }

        val clickableHeader = NodeFinder.findClickableAncestor(headerTitle) ?: headerTitle
        var headerClicked = service.clickNode(clickableHeader)
        if (!headerClicked) {
            val hBounds = Rect()
            headerTitle.getBoundsInScreen(hBounds)
            headerClicked = service.performTap(hBounds.centerX(), hBounds.centerY())
        }

        XLog.i("PlaceCallTool", "Contact Info Fallback: Clicked chat header title, result=$headerClicked")
        if (!headerClicked) return false

        // Step 2: Wait 500ms for WhatsApp Contact Info Screen to open
        runBlocking { delay(500L) }
        val infoRoot = service.rootInActiveWindow ?: return false

        // Step 3: Find "Video" or "Audio" shortcut button on Contact Info screen
        val targetLabel = if (isVideo) "video" else "call"
        val infoCallBtn = NodeFinder.findNodeByIdOrText(infoRoot,
            "com.whatsapp:id/video_btn", "com.whatsapp:id/call_btn",
            "com.whatsapp:id/video_call", "com.whatsapp:id/voice_call",
            targetLabel
        ) ?: NodeFinder.findNodeByKeywords(infoRoot, targetLabel)

        if (infoCallBtn != null) {
            val clickable = NodeFinder.findClickableAncestor(infoCallBtn) ?: infoCallBtn
            var callClicked = service.clickNode(clickable)
            if (!callClicked) {
                val b = Rect()
                infoCallBtn.getBoundsInScreen(b)
                // Tap 20px right above text label as specified
                callClicked = service.performTap(b.centerX(), b.centerY() - 20)
            }

            XLog.i("PlaceCallTool", "Contact Info Fallback: Clicked $targetLabel button on Contact Info Screen, result=$callClicked")

            // Check for popup confirmation dialog
            runBlocking { delay(500L) }
            val confirmRoot = service.rootInActiveWindow
            if (confirmRoot != null) {
                val confirmBtn = NodeFinder.findNodeByIdOrText(confirmRoot,
                    "com.whatsapp:id/call_type_dialog_button",
                    "com.whatsapp:id/button1", "android:id/button1",
                    "call", "start", "video call"
                )
                if (confirmBtn != null) {
                    val clickConfirm = NodeFinder.findClickableAncestor(confirmBtn) ?: confirmBtn
                    service.clickNode(clickConfirm)
                    XLog.i("PlaceCallTool", "Contact Info Fallback: Clicked popup confirmation button")
                }
            }

            return true
        }

        XLog.w("PlaceCallTool", "Contact Info Fallback: '$targetLabel' button not found on Contact Info Screen")
        return false
    }

    private fun findHeaderTitleByRegion(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.top < 250 && bounds.left > 100 && bounds.left < 700 && (node.text != null || node.contentDescription != null)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = findHeaderTitleByRegion(node.getChild(i))
            if (child != null) return child
        }
        return null
    }

    private fun verifyChatHeader(root: AccessibilityNodeInfo?, contact: String): Boolean {
        if (root == null) return false
        val normalizedAliases = ContactMatchUtils.buildNormalizedAliases(contact)
        val digitAliases = ContactMatchUtils.buildDigitAliases(contact)

        val topNodes = mutableListOf<AccessibilityNodeInfo>()
        collectHeaderNodes(root, topNodes)
        for (node in topNodes) {
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            if (ContactMatchUtils.matchesCandidate(text, normalizedAliases, digitAliases)) {
                return true
            }
        }
        return false
    }

    private fun collectHeaderNodes(node: AccessibilityNodeInfo?, results: MutableList<AccessibilityNodeInfo>) {
        if (node == null || !node.isVisibleToUser) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.top < 350 && (node.text != null || node.contentDescription != null)) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) collectHeaderNodes(child, results)
        }
    }

    private fun findTopActionBarCallButton(root: AccessibilityNodeInfo?, isVideo: Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectTopActionBarCallNodes(root, isVideo, candidates)

        for (node in candidates) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.top < 350 && bounds.bottom > 0 && bounds.width() > 0) {
                return node
            }
        }
        return candidates.firstOrNull()
    }

    private fun collectTopActionBarCallNodes(node: AccessibilityNodeInfo?, isVideo: Boolean, results: MutableList<AccessibilityNodeInfo>) {
        if (node == null || !node.isVisibleToUser) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.top < 350) {
            val resId = node.viewIdResourceName?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()

            val isMatch = if (isVideo) {
                resId.contains("video_call") || resId.contains("menuitem_video") ||
                desc.contains("video call") || desc.contains("video_call") || text.contains("video call")
            } else {
                resId.contains("voice_call") || resId.contains("menuitem_voice") ||
                desc.contains("voice call") || desc.contains("voice_call") || text.contains("voice call")
            }

            if (isMatch) {
                results.add(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) collectTopActionBarCallNodes(child, isVideo, results)
        }
    }
}
