// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.runBlocking
import java.util.ArrayDeque

/**
 * Detects stuck agent loops using 5 signals and manages active recovery without abrupt task abandonment.
 * Remembers successful recovery sequences via Mem0.
 */
class StuckDetector(private val windowSize: Int = 8) {

    private val actions = ArrayDeque<String>(windowSize + 1)
    private val screenHashes = ArrayDeque<Int>(windowSize + 1)
    private val screenDiffCounts = ArrayDeque<Int>(windowSize + 1)
    private val errors = ArrayDeque<String>(windowSize + 1)
    private var consecutiveStuckSteps = 0

    private var pendingFingerprint: String? = null
    private var pendingRecoveryAction: String? = null

    sealed class Signal(val description: String) {
        class SameAction(val action: String, val count: Int) :
            Signal("Same action '$action' repeated $count times consecutively")

        class ScreenUnchanged(val steps: Int) :
            Signal("Screen unchanged for $steps consecutive steps")

        class ZeroDiff(val steps: Int) :
            Signal("Zero screen text diff for $steps consecutive steps")

        class HighRepetition(val action: String, val count: Int, val window: Int) :
            Signal("Action '$action' appeared $count times in last $window steps")

        class RepeatedError(val error: String, val count: Int) :
            Signal("Same error repeated $count times consecutively")
    }

    enum class RecoveryLevel {
        HINT,             // Level 1: inject recovery hint into prompt
        AUTO_DISMISS,     // Level 2: auto-dismiss blocking overlay / press back
        STRATEGY_SWITCH   // Level 3: re-open app / switch strategy
    }

    data class Detection(
        val signal: Signal,
        val level: RecoveryLevel,
        val recoveryHint: String,
        val fingerprint: String
    )

    fun record(packageName: String, action: String, screenHash: Int, screenDiffCount: Int, error: String?): Detection? {
        actions.addLast(action)
        if (actions.size > windowSize) actions.removeFirst()

        screenHashes.addLast(screenHash)
        if (screenHashes.size > windowSize) screenHashes.removeFirst()

        screenDiffCounts.addLast(screenDiffCount)
        if (screenDiffCounts.size > windowSize) screenDiffCounts.removeFirst()

        if (error != null) {
            errors.addLast(error)
            if (errors.size > windowSize) errors.removeFirst()
        } else {
            val fp = pendingFingerprint
            val act = pendingRecoveryAction
            if (fp != null && act != null) {
                confirmAndSaveRecovery(fp, act)
                pendingFingerprint = null
                pendingRecoveryAction = null
            }
            errors.clear()
        }

        val signal = checkSameAction()
            ?: checkScreenUnchanged()
            ?: checkZeroDiff()
            ?: checkHighRepetition()
            ?: checkRepeatedError()

        if (signal != null) {
            consecutiveStuckSteps++
            val level = when {
                consecutiveStuckSteps >= 5 -> RecoveryLevel.STRATEGY_SWITCH
                consecutiveStuckSteps >= 3 -> RecoveryLevel.AUTO_DISMISS
                else -> RecoveryLevel.HINT
            }

            val fingerprint = "pkg:${packageName}_act:${action.take(30)}_hash:${screenHash}"
            val rememberedRecovery = checkRememberedRecovery(fingerprint)

            val hint = if (!rememberedRecovery.isNullOrBlank()) {
                XLog.i(TAG, "Replaying remembered recovery sequence from Mem0 for $fingerprint: $rememberedRecovery")
                "[System Notice - Remembered Fix] $rememberedRecovery"
            } else {
                generateRecoveryHint(signal, level)
            }

            pendingFingerprint = fingerprint
            pendingRecoveryAction = hint

            val detection = Detection(signal, level, hint, fingerprint)
            XLog.w(TAG, "[StuckDetector] ${signal.description} → Level ${level.name} (fp=$fingerprint, count=$consecutiveStuckSteps)")
            return detection
        }

        consecutiveStuckSteps = 0
        return null
    }

    private fun checkRememberedRecovery(fingerprint: String): String? {
        return try {
            val (memories, _) = runBlocking { HybridMemoryRepository.searchMemories("stuck_recovery_$fingerprint") }
            if (memories.isNotBlank()) {
                val lines = memories.split("\n")
                val match = lines.find { it.contains("stuck_recovery_") }
                match?.substringAfter(":")?.trim()
            } else null
        } catch (e: Exception) {
            XLog.w(TAG, "Error checking remembered recovery in Mem0: ${e.message}")
            null
        }
    }

    private fun confirmAndSaveRecovery(fingerprint: String, recoveryAction: String) {
        try {
            XLog.i(TAG, "Confirming & persisting successful recovery memory to Mem0 for $fingerprint")
            runBlocking {
                HybridMemoryRepository.recordTurnAsync(
                    userQuery = "stuck_recovery_$fingerprint",
                    aiResponse = recoveryAction
                )
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to persist recovery memory: ${e.message}")
        }
    }

    private fun checkSameAction(): Signal? {
        if (actions.size < 3) return null
        val last3 = actions.toList().takeLast(3)
        return if (last3.all { it == last3[0] }) {
            Signal.SameAction(last3[0].take(50), 3)
        } else null
    }

    private fun checkScreenUnchanged(): Signal? {
        if (screenHashes.size < 3) return null
        val last3 = screenHashes.toList().takeLast(3)
        return if (last3.all { it == last3[0] }) {
            Signal.ScreenUnchanged(3)
        } else null
    }

    private fun checkZeroDiff(): Signal? {
        if (screenDiffCounts.size < 3) return null
        val last3 = screenDiffCounts.toList().takeLast(3)
        return if (last3.all { it == 0 }) {
            Signal.ZeroDiff(3)
        } else null
    }

    private fun checkHighRepetition(): Signal? {
        if (actions.size < windowSize) return null
        val counts = actions.groupingBy { it }.eachCount()
        val maxEntry = counts.maxByOrNull { it.value } ?: return null
        return if (maxEntry.value >= 3) {
            Signal.HighRepetition(maxEntry.key.take(50), maxEntry.value, windowSize)
        } else null
    }

    private fun checkRepeatedError(): Signal? {
        if (errors.size < 3) return null
        val last3 = errors.toList().takeLast(3)
        return if (last3.all { it == last3[0] }) {
            Signal.RepeatedError(last3[0].take(80), 3)
        } else null
    }

    private fun generateRecoveryHint(signal: Signal, level: RecoveryLevel): String {
        val base = when (signal) {
            is Signal.SameAction -> when {
                signal.action.contains("find_and_tap") ->
                    "Your find_and_tap action is not working. Try using tap_node with a specific node ID from get_screen_info, or use system_key(key=\"enter\") to submit."
                signal.action.contains("scroll") ->
                    "You may have reached the end of scrollable content. Try a different approach or press back."
                signal.action.contains("tap") ->
                    "Your tap action may not be hitting the right target. Call get_screen_info to refresh the screen state and try a different element."
                else ->
                    "Your last action '${signal.action}' is not producing results. Try a completely different approach."
            }
            is Signal.ScreenUnchanged ->
                "The screen has not changed for ${signal.steps} steps. Try pressing system_key(key=\"back\") or selecting an alternative button on screen."
            is Signal.ZeroDiff ->
                "No new content has appeared on screen. Try navigating away and back, or use a different tool."
            is Signal.HighRepetition ->
                "You are repeating '${signal.action}' too frequently. Try something fundamentally different."
            is Signal.RepeatedError ->
                "The same error keeps occurring: '${signal.error}'. Try a different tool or strategy."
        }

        return when (level) {
            RecoveryLevel.HINT ->
                "[System Notice] $base"
            RecoveryLevel.AUTO_DISMISS ->
                "[System Recovery] Screen unresponsiveness detected. Attempting active recovery (dismissing overlay or pressing back)."
            RecoveryLevel.STRATEGY_SWITCH ->
                "[System Recovery] Persistent screen state detected. Please switch strategy, re-open app, or navigate via search bar."
        }
    }

    fun reset() {
        actions.clear()
        screenHashes.clear()
        screenDiffCounts.clear()
        errors.clear()
        consecutiveStuckSteps = 0
        pendingFingerprint = null
        pendingRecoveryAction = null
    }

    companion object {
        private const val TAG = "StuckDetector"
    }
}
