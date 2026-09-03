// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.runBlocking

/**
 * Modular Chain of Thought Analysis & Recovery Learning Repository.
 * Maintains learnings from previous execution passes to help the agent auto-recover from
 * common UI problems (unresponsive buttons, open chatrooms, sub-screen overlays, out of stock).
 */
object ChainOfThoughtLearnings {

    private const val TAG = "CoTLearnings"

    enum class IssueType {
        UNRESPONSIVE_BUTTON,
        SUB_SCREEN_OVERLAY,
        OUT_OF_STOCK_ITEM,
        CONTACT_NOT_FOUND,
        WRONG_CHAT_OPENED,
        SEARCH_UI_MISSING
    }

    private val staticLearnings = mapOf(
        IssueType.UNRESPONSIVE_BUTTON to "CoT Learning: The target button was tapped but did not trigger screen change. Try using tap_node on the parent container, or use find_and_tap.",
        IssueType.SUB_SCREEN_OVERLAY to "CoT Learning: Screen is stuck inside a sub-screen or popup overlay. Press system_key(key=\"back\") or tap the top-left back arrow to return to App Home.",
        IssueType.OUT_OF_STOCK_ITEM to "CoT Learning: Item is OUT OF STOCK on screen. Do NOT keep searching or scrolling for this item. Skip it immediately or finish.",
        IssueType.CONTACT_NOT_FOUND to "CoT Learning: Full contact name returned 0 search results. Try searching key sub-terms (e.g., 'unofficial' or 'project' for 'major project group').",
        IssueType.WRONG_CHAT_OPENED to "CoT Learning: Screen opened inside an existing chatroom. Press Back to return to WhatsApp Home Chats screen before searching.",
        IssueType.SEARCH_UI_MISSING to "CoT Learning: Search bar is not visible on screen. Tap the top magnifying glass search icon or scroll up to restore the search bar."
    )

    /**
     * Get modular Chain of Thought reasoning prompt for a specific issue type.
     */
    fun getReasoningPrompt(issue: IssueType, contextDetail: String = ""): String {
        val baseLearning = staticLearnings[issue] ?: "CoT Learning: Screen is unresponsive. Try pressing Back or switching strategy."
        return buildString {
            append("\n\n## Modular Chain of Thought Analysis & Recovery\n")
            append("- Problem Identified: ").append(issue.name).append("\n")
            if (contextDetail.isNotBlank()) append("- Context: ").append(contextDetail).append("\n")
            append("- Actionable Learning: ").append(baseLearning).append("\n")
            append("- Recommended Next Step: Apply the learning above immediately on this step.\n")
        }
    }

    /**
     * Auto-update and persist a new successful CoT recovery learning to Mem0 for future tasks.
     */
    fun recordSuccessfulLearning(issue: IssueType, resolutionAction: String) {
        val learningText = "cot_learning_${issue.name}: $resolutionAction"
        XLog.i(TAG, "Auto-updating CoT Learning Repository: $learningText")
        runBlocking {
            HybridMemoryRepository.recordTurnAsync(
                userQuery = "cot_learning_${issue.name}",
                aiResponse = resolutionAction
            )
        }
    }
}
