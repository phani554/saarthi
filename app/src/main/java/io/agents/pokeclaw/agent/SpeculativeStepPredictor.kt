// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.utils.NodeFinder
import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Predictive & Speculative Step Execution Engine.
 * Predicts upcoming UI target selectors based on task intent and pre-resolves
 * target nodes in memory so upcoming steps execute instantly in 0ms.
 */
object SpeculativeStepPredictor {

    private const val TAG = "StepPredictor"

    data class PredictedStep(
        val stepName: String,
        val targetKeywords: Array<String>,
        val targetIds: Array<String>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as PredictedStep
            if (stepName != other.stepName) return false
            if (!targetKeywords.contentEquals(other.targetKeywords)) return false
            if (!targetIds.contentEquals(other.targetIds)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = stepName.hashCode()
            result = 31 * result + targetKeywords.contentHashCode()
            result = 31 * result + targetIds.contentHashCode()
            return result
        }
    }

    private val prewarmedNodes = ConcurrentHashMap<String, AccessibilityNodeInfo>()

    /**
     * Pre-resolve upcoming UI step targets for a given task goal.
     */
    fun predictUpcomingSteps(taskGoal: String): List<PredictedStep> {
        val lower = taskGoal.lowercase()
        val predictions = mutableListOf<PredictedStep>()

        when {
            lower.contains("message") || lower.contains("send") || lower.contains("whatsapp") -> {
                predictions.add(PredictedStep("search_bar", arrayOf("search"), arrayOf("com.whatsapp:id/search_src_text", "com.whatsapp:id/menuitem_search")))
                predictions.add(PredictedStep("chat_input", arrayOf("message", "type a message"), arrayOf("com.whatsapp:id/entry")))
                predictions.add(PredictedStep("send_button", arrayOf("send", "發送", "发送"), arrayOf("com.whatsapp:id/send_button")))
            }
            lower.contains("call") -> {
                predictions.add(PredictedStep("search_bar", arrayOf("search"), arrayOf("com.whatsapp:id/search_src_text")))
                predictions.add(PredictedStep("video_call", arrayOf("video call", "video"), arrayOf("com.whatsapp:id/video_call")))
                predictions.add(PredictedStep("voice_call", arrayOf("voice call", "call"), arrayOf("com.whatsapp:id/voice_call")))
            }
            lower.contains("order") || lower.contains("buy") || lower.contains("add") -> {
                predictions.add(PredictedStep("search_bar", arrayOf("search", "search in minutes"), arrayOf("com.grofers.customerapp:id/search_bar", "com.flipkart.android:id/search_auto_complete")))
                predictions.add(PredictedStep("add_cta", arrayOf("add", "+ add", "add to cart"), arrayOf("com.grofers.customerapp:id/add_button", "com.zeptoconsumerapp:id/button_add")))
            }
        }
        return predictions
    }

    /**
     * Pre-warm and resolve target node for a predicted step.
     */
    fun prewarmNodeForStep(service: ClawAccessibilityService, step: PredictedStep): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val cached = NodeFinder.findNodeByIdOrText(root, *step.targetIds)
            ?: NodeFinder.findNodeByKeywords(root, *step.targetKeywords)

        if (cached != null) {
            prewarmedNodes[step.stepName] = cached
            XLog.i(TAG, "Speculative pre-warm match for step '${step.stepName}': ${cached.viewIdResourceName ?: cached.text}")
        }
        return cached
    }

    fun getPrewarmedNode(stepName: String): AccessibilityNodeInfo? = prewarmedNodes[stepName]
}
