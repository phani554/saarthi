// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

/**
 * FIFO of parked outbound actions waiting for the user's yes (OutboundConfirmGate).
 * Not thread-safe on its own: the gate calls it under its lock. Pure Kotlin (JVM-testable).
 *
 * - [add] ignores an exact duplicate (same key) that is already waiting, so a model that retries
 *   the same send does not make her confirm it twice.
 * - Entries older than [ttlMs] are dropped whenever the queue is read.
 * - At most [capacity] entries; [add] returns false when full.
 */
class PendingQueue<T>(
    private val capacity: Int,
    private val ttlMs: Long,
    private val keyOf: (T) -> Any,
    private val createdAtOf: (T) -> Long,
) {
    private val items = ArrayDeque<T>()

    enum class AddResult { ADDED, DUPLICATE, FULL }

    fun add(item: T, now: Long): AddResult {
        purge(now)
        val key = keyOf(item)
        if (items.any { keyOf(it) == key }) return AddResult.DUPLICATE
        if (items.size >= capacity) return AddResult.FULL
        items.addLast(item)
        return AddResult.ADDED
    }

    fun head(now: Long): T? {
        purge(now)
        return items.firstOrNull()
    }

    fun pop(now: Long): T? {
        purge(now)
        return items.removeFirstOrNull()
    }

    fun size(now: Long): Int {
        purge(now)
        return items.size
    }

    fun snapshot(now: Long): List<T> {
        purge(now)
        return items.toList()
    }

    fun clear() = items.clear()

    private fun purge(now: Long) {
        items.removeAll { now - createdAtOf(it) > ttlMs }
    }
}
