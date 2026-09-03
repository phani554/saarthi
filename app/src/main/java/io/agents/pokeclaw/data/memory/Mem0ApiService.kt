// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.data.memory

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface Mem0ApiService {

    @POST("v1/memories/")
    suspend fun addMemory(
        @Header("Authorization") authHeader: String,
        @Body body: Mem0AddRequest
    ): Response<ResponseBody>

    @POST("v1/memories/search/")
    suspend fun searchMemory(
        @Header("Authorization") authHeader: String,
        @Body body: Mem0SearchRequest
    ): Response<ResponseBody>
}
