// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.data.memory

import com.google.gson.annotations.SerializedName

data class Mem0Message(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class Mem0AddRequest(
    @SerializedName("messages") val messages: List<Mem0Message>,
    @SerializedName("user_id") val userId: String,
    @SerializedName("infer") val infer: Boolean = true
)

data class Mem0SearchRequest(
    @SerializedName("query") val query: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("limit") val limit: Int = 10
)

data class Mem0SearchResultItem(
    @SerializedName("id") val id: String? = null,
    @SerializedName("memory") val memory: String? = null,
    @SerializedName("score") val score: Double? = null,
    @SerializedName("hash") val hash: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
)
