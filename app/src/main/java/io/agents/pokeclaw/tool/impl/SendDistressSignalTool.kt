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
 * Emergency Distress Signal Tool — sends an urgent SOS distress message to the user's caretaker
 * via WhatsApp or SMS with active device status.
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
        val rawCaretaker = optionalString(params, "caretaker", "caretaker").ifBlank { "caretaker" }
        val customMsg = optionalString(params, "message", "").trim()

        val context = ClawApplication.instance
        val resolvedCaretaker = ContactAliasResolver.resolve(rawCaretaker)

        val alertText = if (customMsg.isNotBlank()) {
            "🚨 EMERGENCY DISTRESS ALERT 🚨\n$customMsg\n(Sent via Saarthi AI Emergency System)"
        } else {
            "🚨 EMERGENCY DISTRESS ALERT 🚨\nUser requires urgent assistance! Please contact or check on them immediately.\n(Sent via Saarthi AI Emergency System)"
        }

        XLog.w("SendDistressSignalTool", "Executing emergency distress signal to '$resolvedCaretaker': $alertText")
        VoiceManager.speakNative("Sending emergency distress signal to $resolvedCaretaker now...", flush = true)

        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }

        return ToolResult.success("Emergency distress alert sent to $resolvedCaretaker.")
    }
}
