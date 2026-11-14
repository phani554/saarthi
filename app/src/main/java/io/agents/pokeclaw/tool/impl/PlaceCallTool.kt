// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.AgentTaskState
import io.agents.pokeclaw.agent.MultiModelAgentOrchestrator
import io.agents.pokeclaw.agent.TaskExecutionState
import io.agents.pokeclaw.agent.knowledge.ContactAliasResolver
import io.agents.pokeclaw.care.CareProfileStore
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

    companion object {
        private val CONFIRM_IDS = arrayOf(
            "com.whatsapp:id/call_type_dialog_button",
            "com.whatsapp:id/button1",
            "android:id/button1"
        )
        private val HEADER_IDS = arrayOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_contact_name"
        )
    }

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
            val outcome = when (callType) {
                "phone" -> executePhoneCall(context, rawContact, resolvedContact)
                "whatsapp_voice", "whatsapp_video" -> executeWhatsAppCall(context, resolvedContact, callType == "whatsapp_video")
                else -> executePhoneCall(context, rawContact, resolvedContact)
            }
            // Lockdown (which disarms voice and kills tasks) only once a call is confirmed live.
            if (outcome.callStarted) {
                enforceCallLockdown()
            }
            outcome.result
        } catch (e: Exception) {
            XLog.e("PlaceCallTool", "Error executing place_call", e)
            ToolResult.error("Failed to place call: ${e.message}")
        }
    }

    private fun enforceCallLockdown() {
        XLog.w("PlaceCallTool", "Enforcing TOTAL LOCKDOWN for active call — keeping user on active call screen")
        TaskExecutionState.instance.setState(AgentTaskState.CallInProgress)
        VoiceManager.stop()
        VoiceManager.disarmVoiceLoop()
        MultiModelAgentOrchestrator.killAllTasks()
        watchForCallEnd()
    }

    private data class CallOutcome(val result: ToolResult, val callStarted: Boolean)

    /**
     * Nothing else re-arms the assistant when the call ends. Watch the audio mode (no permission
     * needed) and return to Idle + re-arm voice once the call is over.
     */
    private fun watchForCallEnd() {
        val am = ClawApplication.instance.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        Thread {
            try {
                val start = System.currentTimeMillis()
                val deadline = start + 3 * 60 * 60 * 1000L
                var sawCall = false
                while (System.currentTimeMillis() < deadline) {
                    val inCall = isInCallMode(am)
                    if (inCall) sawCall = true
                    else if (sawCall || System.currentTimeMillis() - start > 30_000L) break
                    Thread.sleep(2000L)
                }
                if (TaskExecutionState.instance.currentState.value is AgentTaskState.CallInProgress) {
                    XLog.i("PlaceCallTool", "Call ended: leaving call lockdown and re-arming voice")
                    TaskExecutionState.instance.setState(AgentTaskState.Idle)
                    VoiceManager.armVoiceLoop()
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                XLog.w("PlaceCallTool", "Call-end watcher failed: ${e.message}")
            }
        }.apply { isDaemon = true; name = "CallEndWatcher" }.start()
    }

    private fun isInCallMode(am: AudioManager?): Boolean {
        val mode = am?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /**
     * Polls until a call is really up: the audio mode switched to a call mode, or a WhatsApp
     * call screen (end-call button / "Calling" / "Ringing") is showing.
     */
    private fun waitForCallScreen(service: ClawAccessibilityService?, timeoutMs: Long): Boolean {
        val am = ClawApplication.instance.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isInCallMode(am)) return true
            val root = service?.rootInActiveWindow
            if (root != null && hasWhatsAppCallScreen(root, 0)) return true
            try { Thread.sleep(200L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun hasWhatsAppCallScreen(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 40) return false
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        if (id.startsWith("com.whatsapp:id/") && (id.contains("end_call") || id.contains("call_status"))) return true
        if (desc == "end call" || desc == "leave call") return true
        if (text == "calling" || text == "calling…" || text == "calling..." || text == "ringing" || text == "ringing…") return true
        for (i in 0 until node.childCount) {
            if (hasWhatsAppCallScreen(node.getChild(i), depth + 1)) return true
        }
        return false
    }

    /** Number for a name from the care profile (name or relation), then phone contacts (exact, then partial). */
    private fun lookupNumber(context: Context, name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        try {
            CareProfileStore.get().contacts.firstOrNull {
                it.hasPhone && (it.name.equals(n, true) || it.relation.equals(n, true))
            }?.let { return it.digits }
        } catch (_: Exception) { }
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val col = "display_name" // ContactsContract...Phone.DISPLAY_NAME (inherited constant)
            for ((sel, arg) in listOf("$col = ? COLLATE NOCASE" to n, "$col LIKE ?" to "$n%")) {
                context.contentResolver.query(uri, projection, sel, arrayOf(arg), null)?.use { c ->
                    if (c.count == 1 && c.moveToFirst()) return c.getString(0)
                    if (c.count > 1 && sel.contains("=") && c.moveToFirst()) return c.getString(0)
                }
            }
        } catch (e: SecurityException) {
            XLog.w("PlaceCallTool", "No contacts permission for '$n'")
        } catch (e: Exception) {
            XLog.w("PlaceCallTool", "Contact lookup failed for '$n': ${e.message}")
        }
        return null
    }

    /** True when the number is one the user saved (care profile or phone contacts), not free text. */
    private fun isSavedNumber(context: Context, number: String): Boolean {
        val d = number.filter { it.isDigit() }
        if (d.length < 7) return false
        val tail = d.takeLast(10)
        try {
            if (CareProfileStore.get().contacts.any { c -> c.hasPhone && c.digits.filter { it.isDigit() }.takeLast(10) == tail }) return true
        } catch (_: Exception) { }
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { c ->
                if (c.count > 0) return true
            }
        } catch (_: Exception) { }
        return false
    }

    private fun executePhoneCall(context: Context, rawContact: String, contact: String): CallOutcome {
        val numberMatch = Regex("""[\d\s\-+()]{7,}""").find(contact)
        var number = numberMatch?.value?.replace(Regex("""[\s\-()]"""), "") ?: ""
        if (number.count { it.isDigit() } < 7) number = lookupNumber(context, contact) ?: lookupNumber(context, rawContact) ?: ""

        if (number.isEmpty()) {
            // Was: opened an empty dialer and reported success, then locked the assistant down.
            return CallOutcome(ToolResult.error("No phone number found for '$rawContact' in the care profile or phone contacts. Ask the user for the number or the exact contact name."), false)
        }

        // A number only heard in speech / typed by the model is dialled, not called: the user taps Call.
        // Direct calling is kept for numbers the user saved (care profile, phone contacts).
        if (!isSavedNumber(context, number)) {
            return try {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                VoiceManager.speakNative("I have put $number on the dialer. Please check it and press call.", flush = true)
                CallOutcome(ToolResult.success("Opened the dialer with $number for $rawContact (not a saved number, so not called automatically). The user must tap Call."), false)
            } catch (e: Exception) {
                CallOutcome(ToolResult.error("Could not open the dialer: ${e.message}"), false)
            }
        }

        VoiceManager.speakNative("Calling $contact now...", flush = true)
        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(callIntent)
            if (waitForCallScreen(null, 4000L)) {
                CallOutcome(ToolResult.success("Calling $contact ($number) now. Active call screen active."), true)
            } else {
                CallOutcome(ToolResult.error("Asked the phone to call $contact ($number) but no call started. Check the screen before retrying."), false)
            }
        } catch (_: Exception) {
            return try {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                CallOutcome(ToolResult.success("Could not call directly; opened the dialer with $contact ($number). The user must tap Call."), false)
            } catch (e: Exception) {
                CallOutcome(ToolResult.error("Could not place or dial the call: ${e.message}"), false)
            }
        }
    }

    private fun executeWhatsAppCall(context: Context, contact: String, isVideo: Boolean): CallOutcome {
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

                                // Confirm dialog ("Start video call?"): dialog-button ids only. Bare text such as
                                // "call" used to hit the action-bar call buttons or a message bubble.
                                if (clicked) {
                                    clickCallConfirmDialog(service, 1200L)
                                    if (waitForCallScreen(service, 4000L)) {
                                        return CallOutcome(ToolResult.success("Started $modeText with $contact on WhatsApp. Active call screen active."), true)
                                    }
                                    XLog.w("PlaceCallTool", "Strategy 1: no call screen after tapping $modeText")
                                    return CallOutcome(ToolResult.error("Tapped the $modeText button for $contact but no call screen appeared. Check the screen before retrying; do not tap call again blindly."), false)
                                }
                            }

                            // Strategy 2: Contact Info Screen Fallback (Click top chat header title -> click Video button)
                            XLog.i("PlaceCallTool", "Strategy 1 unavailable/unconfirmed. Initiating Strategy 2: Contact Info Screen shortcut...")
                            val infoSuccess = triggerCallViaContactInfoScreen(service, root, isVideo)
                            if (infoSuccess) {
                                return CallOutcome(ToolResult.success("Started $modeText with $contact via WhatsApp Contact Info Screen."), true)
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

        // Was success() after only opening WhatsApp: the agent then told the user the call was placed.
        return CallOutcome(ToolResult.error("Could not start the $modeText with $contact. WhatsApp is open; the call button was not found."), false)
    }

    /** Clicks a WhatsApp/system call-confirm dialog button if one appears within the window. */
    private fun clickCallConfirmDialog(service: ClawAccessibilityService, windowMs: Long) {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            val confirmRoot = service.rootInActiveWindow
            if (confirmRoot != null) {
                val confirmBtn = CONFIRM_IDS.asSequence()
                    .flatMap { service.findNodesById(it).asSequence() }
                    .firstOrNull { it.isVisibleToUser }
                if (confirmBtn != null) {
                    val c = NodeFinder.findClickableAncestor(confirmBtn) ?: confirmBtn
                    val ok = service.clickNode(c)
                    XLog.i("PlaceCallTool", "Clicked call confirm dialog button: $ok")
                    return
                }
            }
            try { Thread.sleep(100L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
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
        // Ids for the requested call type only; bare "call" text also matched e.g. "Video call" or bubbles.
        val infoIds = if (isVideo) arrayOf("com.whatsapp:id/video_btn", "com.whatsapp:id/video_call")
            else arrayOf("com.whatsapp:id/call_btn", "com.whatsapp:id/voice_call", "com.whatsapp:id/audio_call_btn")
        val infoCallBtn = infoIds.asSequence()
            .flatMap { service.findNodesById(it).asSequence() }
            .firstOrNull { it.isVisibleToUser }

        if (infoCallBtn != null) {
            val clickable = NodeFinder.findClickableAncestor(infoCallBtn) ?: infoCallBtn
            var callClicked = service.clickNode(clickable)
            if (!callClicked) {
                val b = Rect()
                infoCallBtn.getBoundsInScreen(b)
                callClicked = service.performTap(b.centerX(), b.centerY())
            }

            XLog.i("PlaceCallTool", "Contact Info Fallback: Clicked $targetLabel button on Contact Info Screen, result=$callClicked")

            if (!callClicked) return false
            clickCallConfirmDialog(service, 1000L)
            return waitForCallScreen(service, 4000L)
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

    /**
     * The chat must really be open (not the search screen) and its title must name the contact.
     * Reads the conversation title id; only on an open chat falls back to the top text band.
     */
    private fun verifyChatHeader(root: AccessibilityNodeInfo?, contact: String): Boolean {
        if (root == null) return false
        if (!ContactListUiUtils.isOpenChatroom(root)) return false
        val normalizedAliases = ContactMatchUtils.buildNormalizedAliases(contact)
        val digitAliases = ContactMatchUtils.buildDigitAliases(contact)

        val titles = HEADER_IDS.flatMap { id -> collectById(root, id) }
        val topNodes = if (titles.isNotEmpty()) titles else mutableListOf<AccessibilityNodeInfo>().also { collectHeaderNodes(root, it) }
        for (node in topNodes) {
            if (node.isEditable) continue
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            if (ContactMatchUtils.matchesCandidate(text, normalizedAliases, digitAliases)) {
                return true
            }
        }
        return false
    }

    private fun collectById(root: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> =
        try { root.findAccessibilityNodeInfosByViewId(id) ?: emptyList() } catch (_: Exception) { emptyList() }

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
