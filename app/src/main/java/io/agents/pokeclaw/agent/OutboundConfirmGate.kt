// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog
import java.util.UUID

/**
 * "Always ask before messaging or calling a person" (owner decision 2026-11-14).
 *
 * Outbound tools (send_message, place_call, message_care_contact) call [check] first. Without the
 * user's approval the tool sends nothing: the call is parked here and the tool returns an English
 * CONFIRMATION NEEDED error that ends the task with the spoken question (DefaultAgentService /
 * TaskOrchestrator Tier 1 turn it into the final answer). The next thing the user says or types
 * goes to TaskFlowController first: "haan" runs exactly the parked call with a one-time token,
 * "na" drops it, anything else drops it and is handled as a new request.
 *
 * The model cannot approve on its own: a `confirmed=true` from the model is ignored; only the
 * random token issued by [approve] (never shown to the model) lets the tool go ahead.
 * The SOS chain (DistressEscalator) does not use these tools and stays automatic.
 */
object OutboundConfirmGate {
    private const val TAG = "OutboundConfirmGate"

    /** Prefix of the tool error the agent reads (English). */
    const val MARKER = "CONFIRMATION NEEDED"
    /** Internal param carrying the one-time approval token. */
    const val TOKEN_PARAM = "__user_confirm_token"
    private const val TTL_MS = 5 * 60_000L

    data class Pending(
        val toolName: String,
        val params: Map<String, Any>,
        /** Question for the user, in her language. */
        val question: String,
        val createdAt: Long,
    )

    data class Approved(val toolName: String, val params: Map<String, Any>)

    enum class Reply { YES, NO, OTHER }

    private val lock = Any()
    private var pending: Pending? = null
    private var approvedToken: String? = null

    /**
     * Returns null when this call carries the user's approval (the tool may go ahead); otherwise
     * parks the call and returns the ToolResult the tool must return (nothing was sent).
     */
    @JvmStatic
    @JvmOverloads
    fun check(
        toolName: String,
        params: Map<String, Any>,
        question: String,
        agentNotice: String = DEFAULT_AGENT_NOTICE,
    ): ToolResult? {
        val token = params[TOKEN_PARAM]?.toString()
        synchronized(lock) {
            if (token != null && token == approvedToken) {
                approvedToken = null
                XLog.i(TAG, "$toolName approved by the user")
                return null
            }
            val clean = LinkedHashMap<String, Any>()
            for ((k, v) in params) if (k != TOKEN_PARAM && k != "confirmed") clean[k] = v
            pending = Pending(toolName, clean, question, System.currentTimeMillis())
        }
        XLog.i(TAG, "Parked $toolName until the user says yes")
        return ToolResult.error("$MARKER: $agentNotice")
    }

    private const val DEFAULT_AGENT_NOTICE =
        "nothing was sent or called yet. Saathi now asks the user and does it only after they say yes. " +
            "Stop here: do not call this tool again and do not send or call any other way."

    /**
     * True (and the token is used up) when [params] carry the user's approval. Unlike [check] it
     * parks nothing, for tools that do their own read-back first (message_to_cart).
     */
    @JvmStatic
    fun consumeApproval(params: Map<String, Any>): Boolean {
        val token = params[TOKEN_PARAM]?.toString() ?: return false
        synchronized(lock) {
            if (token != approvedToken) return false
            approvedToken = null
            return true
        }
    }

    @JvmStatic
    fun isAskResult(result: ToolResult?): Boolean =
        result != null && !result.isSuccess && result.error?.startsWith(MARKER) == true

    /** The parked call, or null (none, or older than 5 minutes). */
    @JvmStatic
    fun peek(): Pending? = synchronized(lock) {
        val p = pending ?: return@synchronized null
        if (System.currentTimeMillis() - p.createdAt > TTL_MS) {
            pending = null
            null
        } else p
    }

    @JvmStatic
    fun pendingQuestion(): String? = peek()?.question

    @JvmStatic
    fun clear() = synchronized(lock) {
        pending = null
        approvedToken = null
    }

    /** User said yes: the parked call with a fresh one-time token, or null when nothing is parked. */
    @JvmStatic
    fun approve(): Approved? = synchronized(lock) {
        val p = peek() ?: return@synchronized null
        pending = null
        val token = UUID.randomUUID().toString()
        approvedToken = token
        Approved(p.toolName, p.params + (TOKEN_PARAM to token))
    }

    // ── Agent taps on a Send / Call control ─────────────────────────────────────────────────
    // The tools above are the only way a message or call may go out. A model that types into a chat
    // and taps Send (or taps a call icon) would skip the question, so tap tools refuse these controls.

    private val OUTBOUND_PACKAGES = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
        "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.android.contacts",
        "com.samsung.android.contacts"
    )
    private val OUTBOUND_ID = Regex(
        """:id/(send|send_button|send_btn|send_message_button_icon|voice_call|video_call|audio_call|call_button|dialpad_floating_action_button|dialButton|call_btn)$""",
        RegexOption.IGNORE_CASE
    )
    private val OUTBOUND_LABEL = Regex(
        """^(send|send message|send sms|voice call|video call|audio call|call|dial|whatsapp call)$""",
        RegexOption.IGNORE_CASE
    )

    /** Message returned by a tap tool that refuses a Send / Call control (English, for the agent). */
    const val DIRECT_SEND_BLOCKED =
        "Blocked: that is a Send or Call control. Use send_message / place_call instead; they ask the user first."

    /** True when [node] (or its clickable ancestor) is a Send / Call button in a messaging or dialer app. */
    @JvmStatic
    fun isDirectOutboundControl(node: AccessibilityNodeInfo?): Boolean {
        var n = node
        var depth = 0
        while (n != null && depth < 4) {
            val pkg = n.packageName?.toString().orEmpty()
            if (pkg !in OUTBOUND_PACKAGES) return false
            val id = n.viewIdResourceName.orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val text = n.text?.toString()?.trim().orEmpty()
            if (OUTBOUND_ID.containsMatchIn(id) || OUTBOUND_LABEL.matches(desc) ||
                (!n.isEditable && OUTBOUND_LABEL.matches(text))) return true
            if (n.isClickable) return false
            n = n.parent
            depth++
        }
        return false
    }

    /** Coordinate form of [isDirectOutboundControl]: checks the deepest node under (x, y). */
    @JvmStatic
    fun isDirectOutboundAt(root: AccessibilityNodeInfo?, x: Int, y: Int): Boolean =
        isDirectOutboundControl(deepestAt(root, x, y, 0))

    private fun deepestAt(node: AccessibilityNodeInfo?, x: Int, y: Int, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 60) return null
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.contains(x, y)) return null
        for (i in node.childCount - 1 downTo 0) {
            val hit = deepestAt(node.getChild(i), x, y, depth + 1)
            if (hit != null) return hit
        }
        return node
    }

    private val NO_WORDS = setOf(
        "no", "nope", "nahi", "nahin", "nai", "na", "naa", "mat", "nako", "cancel", "stop", "ruko", "rehne",
        "vaddu", "vadhu", "kaadu", "ledu", "venda",
        "नहीं", "नही", "ना", "मत", "रुको", "రద్దు", "వద్దు", "కాదు", "లేదు"
    )
    private val YES_WORDS = setOf(
        "yes", "yeah", "yep", "ok", "okay", "okk", "sure", "haan", "han", "haa", "ha", "haanji", "hanji", "ji",
        "theek", "thik", "sahi", "bhejo", "bhej", "karo", "kar", "lagao", "milao", "avunu", "sare", "seri",
        "cheyyi", "pampu", "pampandi",
        "हाँ", "हां", "हा", "जी", "ठीक", "सही", "भेजो", "भेज", "करो", "लगाओ", "मिलाओ",
        "అవును", "సరే", "పంపు", "పంపండి", "చెయ్యి"
    )

    /** Short yes / no in Hindi, Telugu or English. Long or unclear replies are OTHER. */
    @JvmStatic
    fun classify(text: String): Reply {
        val tokens = text.lowercase()
            .replace(Regex("[\\p{P}\\p{S}]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 6) return Reply.OTHER
        if (tokens.any { it in NO_WORDS }) return Reply.NO
        if (tokens.first() in YES_WORDS) return Reply.YES
        return Reply.OTHER
    }
}
