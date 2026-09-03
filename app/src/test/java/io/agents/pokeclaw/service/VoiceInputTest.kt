// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import io.agents.pokeclaw.tool.WorkflowStepResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VoiceInputTest {

    @Test
    fun testWorkflowStepResultStructure() {
        val success = WorkflowStepResult.Success("APP_LAUNCH", "Opened WhatsApp")
        assertEquals("APP_LAUNCH", success.stepName)
        assertEquals("Opened WhatsApp", success.detail)

        val failed = WorkflowStepResult.Failed("CONTACT_SEARCH", WorkflowStepResult.ErrorCode.CONTACT_LOOKUP_FAILED, "Contact not found")
        assertEquals(WorkflowStepResult.ErrorCode.CONTACT_LOOKUP_FAILED, failed.errorCode)
        assertNotNull(failed.reason)
    }
}
