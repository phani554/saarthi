package io.agents.pokeclaw.data.memory

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridMemoryRepositoryTest {

    private val gson = Gson()

    @Test
    fun `Mem0SearchRequest serializes correctly`() {
        val request = Mem0SearchRequest(query = "major project group", userId = "user_123", limit = 5)
        val json = gson.toJson(request)
        assertTrue(json.contains("\"query\":\"major project group\""))
        assertTrue(json.contains("\"user_id\":\"user_123\""))
        assertTrue(json.contains("\"limit\":5"))
    }

    @Test
    fun `Mem0AddRequest serializes correctly`() {
        val request = Mem0AddRequest(
            messages = listOf(
                Mem0Message(role = "user", content = "my major project group is Final Year Project"),
                Mem0Message(role = "assistant", content = "Noted that your major project group is Final Year Project")
            ),
            userId = "user_123",
            infer = true
        )
        val json = gson.toJson(request)
        assertTrue(json.contains("\"role\":\"user\""))
        assertTrue(json.contains("\"user_id\":\"user_123\""))
        assertTrue(json.contains("\"infer\":true"))
    }

    @Test
    fun `MemorySource enum tags match expected strings`() {
        assertEquals("MEM0_CLOUD", MemorySource.MEM0_CLOUD.tag)
        assertEquals("LOCAL_FALLBACK", MemorySource.LOCAL_FALLBACK.tag)
    }
}
