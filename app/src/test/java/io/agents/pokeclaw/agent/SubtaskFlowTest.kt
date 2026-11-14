package io.agents.pokeclaw.agent

import io.agents.pokeclaw.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtaskFlowTest {
    @Test fun cartThenMessageSplitsOnAndSend() {
        val parts = TaskQueueDecomposer.decompose("Add KitKat to my Flipkart minutes cart and send the total to Kamya")
        assertEquals(listOf("Add KitKat to my Flipkart minutes cart", "send the total to Kamya"), parts)
    }

    @Test fun itemListStaysOneTask() {
        assertTrue(TaskQueueDecomposer.decompose("order milk, bread and eggs on blinkit").isEmpty())
    }

    @Test fun laterStepGetsWholeRequestAndPreviousResult() {
        val step = QueuedTask(Channel.LOCAL, "send the total to Kamya", "m_sub_1", dependsOnId = "m_sub_0",
            parentGoal = "Add KitKat to Flipkart minutes and send the total to Kamya", stepIndex = 1, stepCount = 2)
        TaskDependencyGraph.recordOutcome("m_sub_0", true, "Added KitKat. Cart total ₹133.")
        val adapted = TaskDependencyGraph.adaptDependentTask(step)
        assertEquals("send the total to Kamya", adapted.taskText)
        val parsed = TaskPromptEnvelope.parse(adapted.agentPromptOverride!!)
        assertEquals("send the total to Kamya", parsed.currentRequest)
        assertTrue(parsed.backgroundState!!.contains("₹133"))
        assertTrue(parsed.backgroundState!!.contains("step 2 of 2"))
        TaskDependencyGraph.clear()
    }

    @Test fun storeModes() {
        assertEquals(ShoppingStoreRouter.Mode.MAIN, ShoppingStoreRouter.modeFor("order a red frock on flipkart"))
        assertEquals(ShoppingStoreRouter.Mode.MINUTES, ShoppingStoreRouter.modeFor("add kitkat to flipkart"))
        assertEquals(ShoppingStoreRouter.Mode.MINUTES, ShoppingStoreRouter.modeFor("flipkart minutes cart"))
        assertEquals(ShoppingStoreRouter.Mode.UNKNOWN, ShoppingStoreRouter.modeFor("check Kamya's message and find the item on flipkart"))
        assertTrue(ShoppingStoreRouter.promptSection("find the item on flipkart", ShoppingStoreRouter.Mode.UNKNOWN).contains("get_installed_apps"))
    }
}
