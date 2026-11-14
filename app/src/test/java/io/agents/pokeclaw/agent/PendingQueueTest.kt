// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingQueueTest {
    private data class Item(val key: String, val at: Long)

    private fun q(cap: Int = 3, ttl: Long = 1000L) =
        PendingQueue<Item>(cap, ttl, keyOf = { it.key }, createdAtOf = { it.at })

    @Test fun fifoOrder() {
        val q = q()
        q.add(Item("a", 0), 0)
        q.add(Item("b", 1), 1)
        assertEquals("a", q.head(2)?.key)
        assertEquals("a", q.pop(2)?.key)
        assertEquals("b", q.pop(2)?.key)
        assertNull(q.pop(2))
    }

    @Test fun duplicateIsAskedOnce() {
        val q = q()
        assertEquals(PendingQueue.AddResult.ADDED, q.add(Item("a", 0), 0))
        assertEquals(PendingQueue.AddResult.DUPLICATE, q.add(Item("a", 5), 5))
        assertEquals(1, q.size(5))
    }

    @Test fun capacity() {
        val q = q(cap = 2)
        q.add(Item("a", 0), 0)
        q.add(Item("b", 0), 0)
        assertEquals(PendingQueue.AddResult.FULL, q.add(Item("c", 0), 0))
    }

    @Test fun expiry() {
        val q = q(ttl = 100)
        q.add(Item("a", 0), 0)
        q.add(Item("b", 90), 90)
        assertEquals("b", q.head(150)?.key) // a expired
        assertNull(q.head(500))
    }
}
