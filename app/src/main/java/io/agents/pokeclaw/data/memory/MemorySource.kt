// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.data.memory

enum class MemorySource(val displayName: String, val tag: String) {
    MEM0_CLOUD("Mem0 Cloud AI", "MEM0_CLOUD"),
    LOCAL_FALLBACK("Local Markdown Vault", "LOCAL_FALLBACK")
}
