// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

/**
 * Emergency Distress Signal Tool — sends an urgent SOS distress message to the user's caretaker
 * via WhatsApp or SMS with active device status and situational context.
 */
class SendDistressSignalTool : BaseTool() {

    override fun getName(): String = "send_distress_signal"

    override fun getDescriptionEN(): String =
        "SOS: text and phone the user's emergency contacts (loved one, building watchman, family doctor) in their saved priority order."

    override fun getDescriptionCN(): String =
        "通过 WhatsApp 或短信向照顾者发送紧急求救信号/SOS警报。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("message", "string", "Optional custom emergency message", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        // Rewritten: the old version only opened WhatsApp and then reported "alert sent".
        // Now it texts and phones the care contacts in the user's priority order.
        val customMsg = optionalString(params, "message", "").trim()
        val order = io.agents.pokeclaw.care.CareProfileStore.get().distressOrder()
        KVUtils.setEmergencyContactMissing(order.isEmpty())
        val summary = io.agents.pokeclaw.care.DistressEscalator.trigger(ClawApplication.instance, customMsg.ifBlank { "SOS" })
        XLog.w("SendDistressSignalTool", summary)
        return if (order.isEmpty() || !io.agents.pokeclaw.care.DistressEscalator.lastTriggerPlacedCall) ToolResult.error(summary) else ToolResult.success(summary)
    }
}
