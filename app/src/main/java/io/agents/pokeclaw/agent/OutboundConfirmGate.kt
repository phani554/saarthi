// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.a11y.NodeRecycler
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
    /** Most actions that may wait for her yes at once (e.g. "Ravi ko bolo ... aur Sita ko bolo ..."). */
    const val MAX_PENDING = 5

    data class Pending(
        val toolName: String,
        val params: Map<String, Any>,
        /** Question for the user, in her language. */
        val question: String,
        val createdAt: Long,
    ) {
        /**
         * True once its question was handed out by [pendingQuestion] (i.e. shown/spoken to her).
         * A yes only ever approves an action she was actually asked about.
         */
        var asked: Boolean = false
            internal set
    }

    data class Approved(val toolName: String, val params: Map<String, Any>)

    enum class Reply { YES, NO, OTHER }

    private val lock = Any()
    /** Parked actions, asked and confirmed one by one, oldest first. */
    private val queue = PendingQueue<Pending>(
        capacity = MAX_PENDING,
        ttlMs = TTL_MS,
        keyOf = { it.toolName to it.params.toString() },
        createdAtOf = { it.createdAt },
    )
    private var approvedToken: String? = null

    /**
     * Returns null when this call carries the user's approval (the tool may go ahead); otherwise
     * parks the call at the end of the queue and returns the ToolResult the tool must return
     * (nothing was sent). The same call parked twice is asked once.
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
        val added: PendingQueue.AddResult = synchronized(lock) {
            if (token != null && token == approvedToken) {
                approvedToken = null
                XLog.i(TAG, "$toolName approved by the user")
                return null
            }
            val clean = LinkedHashMap<String, Any>()
            for ((k, v) in params) if (k != TOKEN_PARAM && k != "confirmed") clean[k] = v
            val now = System.currentTimeMillis()
            queue.add(Pending(toolName, clean, question, now), now)
        }
        if (added == PendingQueue.AddResult.FULL) {
            XLog.w(TAG, "Confirmation queue full; $toolName not parked")
            return ToolResult.error(
                "$MARKER: too many messages/calls are already waiting for the user's yes. Nothing was sent. " +
                    "Stop here and do not send or call any other way."
            )
        }
        XLog.i(TAG, "Parked $toolName until the user says yes ($added)")
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

    /**
     * The parked call she has been asked about and has not answered yet, or null (none, not asked yet,
     * or older than 5 minutes). Queued actions behind it stay invisible here until they are asked.
     */
    @JvmStatic
    fun peek(): Pending? = synchronized(lock) { queue.head(System.currentTimeMillis())?.takeIf { it.asked } }

    /**
     * Question for the oldest waiting action, and marks it as asked: call this exactly when the
     * question is shown / spoken to her (DefaultAgentService, TaskOrchestrator, and after a queued
     * action ran or was declined, to ask the next one). Null when nothing waits.
     */
    @JvmStatic
    fun pendingQuestion(): String? = synchronized(lock) {
        val head = queue.head(System.currentTimeMillis()) ?: return@synchronized null
        head.asked = true
        head.question
    }

    /** How many actions wait for her yes (asked or not). */
    @JvmStatic
    fun pendingCount(): Int = synchronized(lock) { queue.size(System.currentTimeMillis()) }

    /** All waiting actions, oldest first (for display). */
    @JvmStatic
    fun pendingAll(): List<Pending> = synchronized(lock) { queue.snapshot(System.currentTimeMillis()) }

    /** Drops every waiting action and any unused approval. */
    @JvmStatic
    fun clear() = synchronized(lock) {
        queue.clear()
        approvedToken = null
    }

    /**
     * She said no to the action asked now: drop only that one. Returns true when another action
     * still waits; ask it with [pendingQuestion].
     */
    @JvmStatic
    fun decline(): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (queue.head(now)?.asked == true) queue.pop(now)
        queue.head(now) != null
    }

    /**
     * User said yes to the action asked now: removes it from the queue and returns it with a fresh
     * one-time token, or null when nothing is parked. Any later actions stay queued; ask
     * [pendingQuestion] after this one has run.
     */
    @JvmStatic
    fun approve(): Approved? = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (queue.head(now)?.asked != true) return@synchronized null
        val p = queue.pop(now) ?: return@synchronized null
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
        // [node] is the caller's; ancestors fetched here are recycled (API < 33).
        var n = node
        var depth = 0
        var result = false
        while (n != null && depth < 4) {
            val pkg = n.packageName?.toString().orEmpty()
            if (pkg !in OUTBOUND_PACKAGES) break
            val id = n.viewIdResourceName.orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val text = n.text?.toString()?.trim().orEmpty()
            if (OUTBOUND_ID.containsMatchIn(id) || OUTBOUND_LABEL.matches(desc) ||
                (!n.isEditable && OUTBOUND_LABEL.matches(text))) { result = true; break }
            if (n.isClickable) break
            val next = n.parent
            NodeRecycler.recycleUnless(n, node)
            n = next
            depth++
        }
        NodeRecycler.recycleUnless(n, node)
        return result
    }

    /** Coordinate form of [isDirectOutboundControl]: checks the deepest node under (x, y). */
    @JvmStatic
    fun isDirectOutboundAt(root: AccessibilityNodeInfo?, x: Int, y: Int): Boolean {
        val leaf = deepestAt(root, x, y, 0)
        return try { isDirectOutboundControl(leaf) } finally { NodeRecycler.recycleUnless(leaf, root) }
    }

    /** Deepest node under (x, y); children off the returned path are recycled. */
    private fun deepestAt(node: AccessibilityNodeInfo?, x: Int, y: Int, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 60) return null
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.contains(x, y)) return null
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            val hit = deepestAt(child, x, y, depth + 1)
            if (hit !== child) NodeRecycler.recycle(child)
            if (hit != null) return hit
        }
        return node
    }

    /**
     * Yes / no in Hindi, Marathi, Telugu or English, judged against the action asked now. A longer
     * yes ("haan bhej do Ravi ko abhi") still counts; a reply that adds a new request is OTHER.
     * See [ConfirmReplyClassifier].
     */
    @JvmStatic
    fun classify(text: String): Reply = when (ConfirmReplyClassifier.classify(text, peek()?.toolName)) {
        ConfirmReplyClassifier.Reply.YES -> Reply.YES
        ConfirmReplyClassifier.Reply.NO -> Reply.NO
        ConfirmReplyClassifier.Reply.OTHER -> Reply.OTHER
    }
}
