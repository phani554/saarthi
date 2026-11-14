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
            "If message is empty for the chemist, sends the current medicine order list. Saathi asks the user before sending."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = listOf(
        ToolParameter("role", "string", "chemist | family_doctor | doctor | watchman | loved_one", true),
        ToolParameter("message", "string", "Text to send (optional for chemist)", false),
        ToolParameter("confirmed", "boolean", "Ignored: Saathi itself asks the user before anything is sent", false),
    )

    private fun norm(s: String?): String = s.orEmpty().replace(Regex("\\s+"), " ").trim()

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
        // Every message to a care contact is read back first (the medicine order too); the model's
        // own confirmed=true no longer counts. The SOS chain does not use this tool.
        io.agents.pokeclaw.agent.OutboundConfirmGate.check(
            getName(), params, io.agents.pokeclaw.agent.SpokenStrings.confirmSend(contact.label(), message)
        )?.let { return it }

        var digits = contact.digits.removePrefix("+").filter { it.isDigit() }
        if (digits.length == 11 && digits.startsWith("0")) digits = digits.substring(1) // trunk prefix 0XXXXXXXXXX
        if (digits.length == 10) digits = "91$digits" // Indian mobile without country code
        val ctx = ClawApplication.instance
        val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}")
        // WhatsApp, else WhatsApp Business.
        var pkg: String? = null
        for (candidate in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                pkg = candidate
                break
            } catch (_: Exception) { }
        }
        if (pkg == null) return ToolResult.error("WhatsApp is not installed or could not open.")

        val service = requireAccessibilityService(6_000L)
            ?: return ToolResult.error("Opened WhatsApp with the message typed, but accessibility is off, so it was not sent.")
        val want = norm(message)
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            // Only send once the wa.me chat is open with exactly our text in its box. Polling right
            // after startActivity could otherwise find an older chat's send button and its draft.
            val entry = service.findNodesById("$pkg:id/entry").firstOrNull { it.isVisibleToUser }
            if (entry != null && norm(entry.text?.toString()) == want) {
                val send = service.findNodesById("$pkg:id/send").firstOrNull { it.isVisibleToUser }
                if (send != null && service.clickNode(send)) {
                    // Verified when the input box no longer holds the message.
                    val verifyUntil = System.currentTimeMillis() + 2_000L
                    while (System.currentTimeMillis() < verifyUntil) {
                        Thread.sleep(250)
                        val e = service.findNodesById("$pkg:id/entry").firstOrNull()
                        if (e != null && norm(e.text?.toString()) != want) {
                            XLog.i("MessageCareContact", "Sent WhatsApp to $role")
                            return ToolResult.success("Message sent to ${contact.label()} (${CareRoles.displayName(role)}).")
                        }
                    }
                    return ToolResult.error("Tapped send but the message is still in the box; it was probably NOT sent. Check the screen before retrying.")
                }
            }
            Thread.sleep(300)
        }
        return ToolResult.error("WhatsApp did not open the ${CareRoles.displayName(role)} chat with the message ready, so nothing was sent.")
    }
}
