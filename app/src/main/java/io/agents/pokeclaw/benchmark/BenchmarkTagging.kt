// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.benchmark

/**
 * Pure helpers for the benchmark (unit-tested): which grandma task a run was, why a run failed,
 * and the privacy redaction applied to exported / uploaded runs.
 */
object BenchmarkTagging {

    /** The fixed task ids the notebook scores out of 10. */
    const val CALL = "call"
    const val READ_MESSAGE = "read_message"
    const val CART = "cart"
    const val SEND_MESSAGE = "send_message"
    const val OTHER = "other"

    private val CALL_WORDS = listOf("call", "video call", "phone ", "ring ", "कॉल", "फोन", "फ़ोन", "कॉल कर", "కాల్", "फोन लाव")
    private val READ_WORDS = listOf("read", "latest message", "last message", "messages of", "what did", "summar", "पढ़", "पढ", "मैसेज क्या", "चదువు", "చదువు", "वाच")
    private val CART_WORDS = listOf("cart", "order", "add ", "buy", "flipkart", "minutes", "blinkit", "zepto", "amazon", "price", "कार्ट", "मंगा", "खरीद", "ऑर्डर", "ఆర్డర్")
    private val SEND_WORDS = listOf("send", "tell ", "message to", "whatsapp", "भेज", "बोलो", "बता", "పంపు", "पाठव")

    /** Auto task id from the request text. Cart wins over send ("send the cart total" is a cart task). */
    fun taskId(text: String?): String {
        val t = " " + (text ?: "").lowercase() + " "
        fun has(ws: List<String>) = ws.any { t.contains(it) }
        return when {
            has(CALL_WORDS) && !has(listOf("calculat")) -> CALL
            has(CART_WORDS) -> CART
            has(READ_WORDS) -> READ_MESSAGE
            has(SEND_WORDS) -> SEND_MESSAGE
            else -> OTHER
        }
    }

    data class ToolOutcome(val name: String, val success: Boolean, val status: String?)

    /**
     * Why the run did not succeed, or null when it succeeded / parked for her yes.
     * Order matters: the user stopping it beats everything else.
     */
    fun failureReason(
        outcome: String?,
        cancelled: Boolean,
        message: String?,
        failedLlmCalls: Int,
        stuckSignals: Int,
        tools: List<ToolOutcome>,
    ): String? {
        val m = (message ?: "").lowercase()
        if (cancelled) return if (m.contains("interrupted")) "interrupted" else "user_stopped"
        if (outcome == "success" || outcome == "awaiting_yes") return null
        return when {
            m.contains("forcefully terminated") || m.contains("cancel") -> "user_stopped"
            m.contains("system dialog") -> "system_dialog"
            m.contains("max") && (m.contains("step") || m.contains("iteration") || m.contains("round")) -> "too_many_steps"
            failedLlmCalls > 0 && tools.isEmpty() -> "model_error"
            stuckSignals > 0 -> "stuck"
            tools.lastOrNull()?.let { !it.success && it.status != STATUS_ASKED } == true -> "tool_error:" + tools.last().name
            failedLlmCalls > 0 -> "model_error"
            else -> "other"
        }
    }

    const val STATUS_OK = "ok"
    const val STATUS_FAIL = "fail"
    const val STATUS_ASKED = "asked_user"

    /** True when a send tool's own on-screen check passed. */
    fun sendVerifiedOnScreen(data: String?): Boolean = data?.contains("(verified on screen)") == true

    const val HIDDEN = "[hidden]"
}
