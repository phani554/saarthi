// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.data.memory

import kotlinx.coroutines.flow.StateFlow

interface MemoryRepository {

    val activeMemorySource: StateFlow<MemorySource>

    /**
     * Search memory for user query.
     * Attempts Mem0 Cloud REST API first; falls back to local vault if Mem0 is unavailable/rate-limited/unconfigured.
     * @return Pair of (formattedMemoryContext, activeSourceUsed)
     */
    suspend fun searchMemories(query: String, userId: String = "user_default"): Pair<String, MemorySource>

    /**
     * Record a chat turn (user prompt + AI response) into memory.
     * Attempts Mem0 Cloud REST API first; falls back to local memory vault.
     */
    suspend fun recordTurn(userQuery: String, aiResponse: String, userId: String = "user_default"): MemorySource

    /**
     * Record an explicit personal fact into memory.
     */
    suspend fun recordFact(fact: String, userId: String = "user_default"): MemorySource

    /**
     * Returns the current active memory source.
     */
    fun getActiveSource(): MemorySource
}
