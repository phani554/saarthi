// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.content.Intent
import android.net.Uri
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.care.CareProfileStore
import io.agents.pokeclaw.care.CareRoles
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog

/**
 * WhatsApp the saved chemist / family doctor / watchman / loved one by NUMBER (wa.me deep link),
 * so it never depends on how the contact is named in the phone book.
 * For the chemist with no message, it sends the current medicine list from the care profile.
 */
class MessageCareContactTool : BaseTool() {
    override fun getName() = "message_care_contact"
    override fun getDisplayName() = "Message care contact"
    override fun getDescriptionEN() =
        "WhatsApp a saved care contact by role: chemist, family_doctor, doctor, watchman, loved_one. " +
            "If message is empty for the chemist, sends the current medicine order list."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = listOf(
        ToolParameter("role", "string", "chemist | family_doctor | doctor | watchman | loved_one", true),
        ToolParameter("message", "string", "Text to send (optional for chemist)", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val rawRole = optionalString(params, "role", "").trim().lowercase()
        val role = if (rawRole in CareRoles.ALL) rawRole else CareRoles.fromWords(rawRole)
            ?: return ToolResult.error("Unknown role '$rawRole'. Use chemist, family_doctor, doctor, watchman or loved_one.")
        val profile = CareProfileStore.get()
        val contact = profile.first(role)
        if (contact == null || !contact.hasPhone) {
            return ToolResult.error("No ${CareRoles.displayName(role)} number saved. Ask the user for it, or add it in Settings → Care profile.")
        }
        var message = optionalString(params, "message", "").trim()
        if (message.isBlank() && role == CareRoles.CHEMIST) {
            if (profile.medicines.isEmpty()) return ToolResult.error("No medicines saved yet. Upload a prescription first.")
            message = "Namaste, please send these medicines:\n" + profile.medicines.joinToString("\n") {
                "- " + listOf(it.name, it.dose).filter { s -> s.isNotBlank() }.joinToString(" ")
            } + "\nThank you."
        }
        if (message.isBlank()) return ToolResult.error("Message is empty.")

        var digits = contact.digits.removePrefix("+")
        if (digits.length == 10) digits = "91$digits" // Indian mobile without country code
        val ctx = ClawApplication.instance
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}"))
            .setPackage("com.whatsapp")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent) } catch (e: Exception) {
            return ToolResult.error("WhatsApp is not installed or could not open: ${e.message}")
        }

        val service = requireAccessibilityService(6_000L)
            ?: return ToolResult.error("Opened WhatsApp with the message typed, but accessibility is off, so it was not sent.")
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            val send = service.findNodesById("com.whatsapp:id/send").firstOrNull { it.isVisibleToUser }
            if (send != null && service.clickNode(send)) {
                Thread.sleep(700)
                // Verified when the input box is empty again.
                val entry = service.findNodesById("com.whatsapp:id/entry").firstOrNull()
                val left = entry?.text?.toString().orEmpty()
                return if (left.isBlank() || !message.startsWith(left.take(10))) {
                    XLog.i("MessageCareContact", "Sent WhatsApp to $role")
                    ToolResult.success("Message sent to ${contact.label()} (${CareRoles.displayName(role)}).")
                } else ToolResult.error("Tapped send but the message is still in the box.")
            }
            Thread.sleep(400)
        }
        return ToolResult.error("WhatsApp opened with the message typed, but the send button was not found.")
    }
}
