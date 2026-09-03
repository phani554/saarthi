// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

/**
 * Single-source-of-truth state machine for the Saarthi Agent.
 * Every subsystem (VoiceManager, TtsRouter, AccessibilityExecutor, TaskOrchestrator)
 * reacts to state transitions on this Flow.
 */
sealed interface AgentTaskState {
    object Idle : AgentTaskState
    data class Planning(val task: String, val isFastPath: Boolean = false) : AgentTaskState
    data class Executing(val task: String, val stepIndex: Int = 1, val plan: List<String> = emptyList()) : AgentTaskState
    data class WaitingForUser(val prompt: String) : AgentTaskState
    data class Recovering(val stepIndex: Int, val reason: String) : AgentTaskState
    object CallInProgress : AgentTaskState
    data class Completed(val result: String) : AgentTaskState
    data class Failed(val error: String) : AgentTaskState
    data class Aborted(val reason: String) : AgentTaskState
}
