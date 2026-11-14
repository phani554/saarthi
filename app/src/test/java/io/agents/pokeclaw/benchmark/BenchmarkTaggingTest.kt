package io.agents.pokeclaw.benchmark

import io.agents.pokeclaw.benchmark.BenchmarkTagging.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkTaggingTest {
    @Test fun tagsTheGrandmaTasks() {
        assertEquals("call", BenchmarkTagging.taskId("Video call Ravi on WhatsApp"))
        assertEquals("call", BenchmarkTagging.taskId("रवि को फोन लगाओ"))
        assertEquals("cart", BenchmarkTagging.taskId("Order cucumber and tomato from Flipkart Minutes"))
        assertEquals("cart", BenchmarkTagging.taskId("send the cart total to Kamya on WhatsApp."))
        assertEquals("read_message", BenchmarkTagging.taskId("tell me the last five messages of final year project in WhatsApp"))
        assertEquals("send_message", BenchmarkTagging.taskId("send hi to kamya"))
        assertEquals("other", BenchmarkTagging.taskId("what is the weather"))
        assertEquals("other", BenchmarkTagging.taskId(null))
    }

    @Test fun failureReasons() {
        assertEquals("user_stopped", BenchmarkTagging.failureReason("cancelled", true, "Agent task forcefully terminated by user.", 0, 0, emptyList()))
        assertEquals("interrupted", BenchmarkTagging.failureReason("cancelled", true, "Interrupted during action execution", 0, 0, emptyList()))
        assertNull(BenchmarkTagging.failureReason("success", false, "done", 0, 0, emptyList()))
        assertNull(BenchmarkTagging.failureReason("awaiting_yes", false, "shall I send?", 0, 0, emptyList()))
        assertEquals("system_dialog", BenchmarkTagging.failureReason("fail", false, "system dialog blocked", 0, 0, emptyList()))
        assertEquals("stuck", BenchmarkTagging.failureReason("fail", false, "gave up", 0, 2, listOf(ToolOutcome("swipe", true, "ok"))))
        assertEquals("tool_error:find_search_bar", BenchmarkTagging.failureReason("fail", false, "x", 0, 0,
            listOf(ToolOutcome("open_app", true, "ok"), ToolOutcome("find_search_bar", false, "fail"))))
        assertEquals("model_error", BenchmarkTagging.failureReason("fail", false, "timeout", 1, 0, emptyList()))
        assertEquals("other", BenchmarkTagging.failureReason("fail", false, "x", 0, 0, listOf(ToolOutcome("tap", true, "ok"))))
    }

    @Test fun parkedSendIsNotAToolError() {
        assertEquals("other", BenchmarkTagging.failureReason("fail", false, "x", 0, 0,
            listOf(ToolOutcome("send_message", true, BenchmarkTagging.STATUS_ASKED))))
    }

    @Test fun onScreenCheck() {
        assertTrue(BenchmarkTagging.sendVerifiedOnScreen("Sent 'hi' to Kamya via WhatsApp (verified on screen)"))
        assertFalse(BenchmarkTagging.sendVerifiedOnScreen("Sent 'hi' to Kamya via WhatsApp (input cleared after Send; message bubble not confirmed)"))
        assertFalse(BenchmarkTagging.sendVerifiedOnScreen(null))
    }
}
