// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.content.Intent
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.knowledge.ContactAliasResolver
import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.service.VoiceManager
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Emergency Distress Signal Tool — sends an urgent SOS distress message to the user's caretaker
 * via WhatsApp or SMS with active device status and situational context.
 */
class SendDistressSignalTool : BaseTool() {

    override fun getName(): String = "send_distress_signal"

    override fun getDescriptionEN(): String =
        "Send an urgent emergency distress signal / SOS alert to the user's caretaker via WhatsApp or SMS."

    override fun getDescriptionCN(): String =
        "通过 WhatsApp 或短信向照顾者发送紧急求救信号/SOS警报。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("caretaker", "string", "Optional caretaker contact name (resolved from memory if omitted)", false),
        ToolParameter("message", "string", "Optional custom emergency message", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val rawCaretaker = optionalString(params, "caretaker", "emergency_contact").ifBlank { "emergency_contact" }
        val customMsg = optionalString(params, "message", "").trim()

        val context = ClawApplication.instance
        var resolvedCaretaker = ContactAliasResolver.resolve(rawCaretaker)

        // Query Mem0 specifically for emergency_contact key
        if (resolvedCaretaker == "emergency_contact" || resolvedCaretaker == "caretaker") {
            val (memories, _) = runBlocking { HybridMemoryRepository.searchMemories("emergency contact caretaker") }
            if (memories.isNotBlank()) {
                val lines = memories.split("\n")
                val matched = lines.find { it.contains("emergency", ignoreCase = true) || it.contains("caretaker", ignoreCase = true) }
                if (matched != null) {
                    resolvedCaretaker = matched.substringAfter(":").trim().ifBlank { resolvedCaretaker }
                }
            }
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val alertText = if (customMsg.isNotBlank()) {
            "🚨 EMERGENCY DISTRESS ALERT 🚨\n[$timestamp]\n$customMsg\n(Sent via Saarthi AI Emergency System)"
        } else {
            "🚨 EMERGENCY DISTRESS ALERT 🚨\n[$timestamp]\nUser requires urgent assistance! Please contact or check on them immediately.\n(Sent via Saarthi AI Emergency System)"
        }

        val hasContactOnFile = resolvedCaretaker.isNotBlank() && resolvedCaretaker != "emergency_contact" && resolvedCaretaker != "caretaker"

        if (!hasContactOnFile) {
            XLog.w("SendDistressSignalTool", "No emergency contact on file! Flagging setup for next session.")
            KVUtils.setEmergencyContactMissing(true)
        } else {
            KVUtils.setEmergencyContactMissing(false)
        }

        XLog.w("SendDistressSignalTool", "Executing emergency distress signal to '$resolvedCaretaker' (contactOnFile=$hasContactOnFile): $alertText")
        VoiceManager.speakNative("Sending emergency distress signal to $resolvedCaretaker now...", flush = true)

        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }

        return ToolResult.success(
            if (hasContactOnFile)
                "Emergency distress alert sent to $resolvedCaretaker."
            else
                "Emergency distress alert triggered, but no emergency contact was found on file. Prompting setup at next session start."
        )
    }
}
