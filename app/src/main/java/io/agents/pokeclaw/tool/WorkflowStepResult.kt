// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

/**
 * Modular workflow step result for tracking step execution status,
 * pinpointing exact failures across app launch, window readiness, contact search, and input actions.
 */
sealed class WorkflowStepResult {
    data class Success(val stepName: String, val detail: String) : WorkflowStepResult()
    data class Failed(val stepName: String, val errorCode: ErrorCode, val reason: String) : WorkflowStepResult()

    enum class ErrorCode {
        APP_LAUNCH_FAILED,
        WINDOW_TIMEOUT,
        CONTACT_LOOKUP_FAILED,
        HEADER_MISMATCH,
        INPUT_FIELD_MISSING,
        SEND_ACTION_FAILED,
        PRODUCT_SEARCH_FAILED,
        OUT_OF_STOCK
    }
}
