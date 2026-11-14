// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.agent.ConfirmReplyClassifier.Reply
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmReplyClassifierTest {
    private fun c(text: String, tool: String? = "send_message") = ConfirmReplyClassifier.classify(text, tool)

    @Test fun shortYes() {
        assertEquals(Reply.YES, c("haan"))
        assertEquals(Reply.YES, c("Haan ji"))
        assertEquals(Reply.YES, c("ok"))
        assertEquals(Reply.YES, c("हाँ भेजो"))
        assertEquals(Reply.YES, c("అవును"))
        assertEquals(Reply.YES, c("होय"))
    }

    @Test fun shortNo() {
        assertEquals(Reply.NO, c("na"))
        assertEquals(Reply.NO, c("nahi"))
        assertEquals(Reply.NO, c("mat bhejo"))
        assertEquals(Reply.NO, c("haan... nahi ruko"))
        assertEquals(Reply.NO, c("వద్దు"))
        assertEquals(Reply.NO, c("नको"))
    }

    @Test fun emphaticNaAfterYesIsYes() {
        assertEquals(Reply.YES, c("bhej do na"))
        assertEquals(Reply.YES, c("haan na"))
        assertEquals(Reply.NO, c("na na"))
    }

    @Test fun longYesCounts() {
        assertEquals(Reply.YES, c("haan bhej do Ravi ko abhi"))
        assertEquals(Reply.YES, c("haan haan bhej do Ravi ko abhi turant please"))
        assertEquals(Reply.YES, c("theek hai beta jaldi se bhej do Ravi ko abhi"))
        // strong yes later in a long reply
        assertEquals(Reply.YES, c("arre beta haan wahi wala bhej do jaldi se"))
    }

    @Test fun longWithoutYesIsOther() {
        assertEquals(Reply.NO, c("mujhe pata nahi tha ki aaj kya hai")) // any clear no word wins
        assertEquals(Reply.OTHER, c("kal subah das baje wala program kaisa raha"))
    }

    @Test fun newRequestIsOther() {
        assertEquals(Reply.OTHER, c("haan aur Sita ko bhi bhejo"))
        assertEquals(Reply.OTHER, c("haan and also call Sita"))
        assertEquals(Reply.OTHER, c("ok youtube kholo"))
        assertEquals(Reply.OTHER, c("haan phir doodh order karo"))
    }

    @Test fun callVerbsDependOnParkedTool() {
        assertEquals(Reply.YES, c("haan call karo", "place_call"))
        assertEquals(Reply.YES, c("haan phone lagao", "place_call"))
        assertEquals(Reply.OTHER, c("haan call karo", "send_message"))
        assertEquals(Reply.YES, c("haan call karo", null))
        assertEquals(Reply.OTHER, c("haan message karo", "place_call"))
    }

    @Test fun emptyAndTooLong() {
        assertEquals(Reply.OTHER, c(""))
        assertEquals(Reply.OTHER, c("haan " + "bahut ".repeat(25)))
    }
}
