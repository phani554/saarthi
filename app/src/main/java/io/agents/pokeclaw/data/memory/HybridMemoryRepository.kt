// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.data.memory

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.agents.pokeclaw.agent.knowledge.MemoryManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Hybrid Memory Repository — Dual Strategy Orchestrator.
 *
 * Primary Strategy: Mem0 Cloud REST API (https://api.mem0.ai/)
 * Fallback Strategy: Local Markdown Vault (MemoryManager)
 *
 * Fallback triggers automatically on:
 * - Empty / unconfigured / invalid MEM0_API_KEY
 * - HTTP 401 (Unauthorized), 402 (Payment Required / Quota Exceeded), 429 (Rate Limited), 5xx Server Errors
 * - Network IOException (offline, timeout, unreachable host)
 * - Any JSON parsing or unexpected runtime exception
 */
object HybridMemoryRepository : MemoryRepository {

    private const val TAG = "HybridMemoryRepo"
    private const val MEM0_BASE_URL = "https://api.mem0.ai/"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private val _activeMemorySource = MutableStateFlow(
        if (isApiKeyValid(KVUtils.getMem0ApiKey())) MemorySource.MEM0_CLOUD else MemorySource.LOCAL_FALLBACK
    )
    override val activeMemorySource: StateFlow<MemorySource> = _activeMemorySource.asStateFlow()

    fun refreshActiveSource() {
        val isValid = isApiKeyValid(KVUtils.getMem0ApiKey())
        _activeMemorySource.value = if (isValid) MemorySource.MEM0_CLOUD else MemorySource.LOCAL_FALLBACK
        XLog.i(TAG, "Refreshed active memory source: ${_activeMemorySource.value.name}")
    }

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        XLog.d(TAG, "OkHttp: $message")
    }.apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    private val mem0Api: Mem0ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(MEM0_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(Mem0ApiService::class.java)
    }

    private fun isApiKeyValid(key: String): Boolean {
        if (key.isBlank()) return false
        val lower = key.trim().lowercase()
        return !lower.contains("placeholder") && !lower.contains("your_key") && key.length > 8
    }

    private fun getAuthHeader(apiKey: String): String {
        val trimmed = apiKey.trim()
        return if (trimmed.startsWith("Token ") || trimmed.startsWith("Bearer ")) {
            trimmed
        } else {
            "Token $trimmed"
        }
    }

    override fun getActiveSource(): MemorySource = _activeMemorySource.value

    override suspend fun searchMemories(
        query: String,
        userId: String
    ): Pair<String, MemorySource> = withContext(Dispatchers.IO) {
        val apiKey = KVUtils.getMem0ApiKey().trim()

        if (!isApiKeyValid(apiKey)) {
            XLog.d(TAG, "Mem0 API key missing or unconfigured; using LOCAL_FALLBACK")
            _activeMemorySource.value = MemorySource.LOCAL_FALLBACK
            return@withContext Pair(MemoryManager.getMemoryPromptSection(), MemorySource.LOCAL_FALLBACK)
        }

        try {
            XLog.d(TAG, "Searching Mem0 Cloud for query: '$query' (user=$userId)")
            val request = Mem0SearchRequest(query = query, userId = userId, limit = 5)
            val response = mem0Api.searchMemory(getAuthHeader(apiKey), request)

            if (response.isSuccessful) {
                val bodyStr = response.body()?.string().orEmpty()
                val memoryList = parseMem0SearchResults(bodyStr)
                if (memoryList.isNotEmpty()) {
                    _activeMemorySource.value = MemorySource.MEM0_CLOUD
                    val formatted = buildString {
                        append("\n\n## User Preferences & Past Context (Mem0 Cloud AI)\n")
                        append("These facts were retrieved from Mem0 AI memory. Use them to personalize actions and answers:\n")
                        memoryList.forEach { mem ->
                            append("- ").append(mem).append("\n")
                        }
                    }
                    XLog.i(TAG, "Mem0 Cloud search succeeded (${memoryList.size} memories retrieved)")
                    return@withContext Pair(formatted, MemorySource.MEM0_CLOUD)
                }
            } else {
                XLog.w(TAG, "Mem0 API search failed with HTTP ${response.code()}: ${response.errorBody()?.string()}")
                if (response.code() in listOf(401, 402, 429) || response.code() >= 500) {
                    _activeMemorySource.value = MemorySource.LOCAL_FALLBACK
                }
            }
        } catch (e: IOException) {
            XLog.w(TAG, "Mem0 Cloud network exception; falling back to local memory: ${e.message}")
            _activeMemorySource.value = MemorySource.LOCAL_FALLBACK
        } catch (e: Exception) {
            XLog.e(TAG, "Mem0 Cloud unexpected exception; falling back to local memory", e)
            _activeMemorySource.value = MemorySource.LOCAL_FALLBACK
        }

        // Fallback Strategy: Local Vault
        val localContext = MemoryManager.getMemoryPromptSection()
        Pair(localContext, MemorySource.LOCAL_FALLBACK)
    }

    override suspend fun recordTurn(
        userQuery: String,
        aiResponse: String,
        userId: String
    ): MemorySource = withContext(Dispatchers.IO) {
        val apiKey = KVUtils.getMem0ApiKey().trim()

        // Always learn locally first as safety net
        MemoryManager.learnFromMessage(userQuery)

        if (!isApiKeyValid(apiKey)) {
            _activeMemorySource.value = MemorySource.LOCAL_FALLBACK
            return@withContext MemorySource.LOCAL_FALLBACK
        }

        try {
            val messages = listOf(
                Mem0Message(role = "user", content = userQuery),
                Mem0Message(role = "assistant", content = aiResponse)
            )
            val request = Mem0AddRequest(messages = messages, userId = userId, infer = true)
            val response = mem0Api.addMemory(getAuthHeader(apiKey), request)

            if (response.isSuccessful) {
                _activeMemorySource.value = MemorySource.MEM0_CLOUD
                XLog.i(TAG, "Asynchronously added turn to Mem0 Cloud successfully")
                return@withContext MemorySource.MEM0_CLOUD
            } else {
                XLog.w(TAG, "Mem0 API add failed with HTTP ${response.code()}")
                _activeMemorySource.value = MemorySource.LOCAL_FALLBACK
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Mem0 add memory exception: ${e.message}; local vault used")
            _activeMemorySource.value = MemorySource.LOCAL_FALLBACK
        }

        MemorySource.LOCAL_FALLBACK
    }

    override suspend fun recordFact(
        fact: String,
        userId: String
    ): MemorySource = withContext(Dispatchers.IO) {
        MemoryManager.recordFact(fact)
        recordTurn(userQuery = fact, aiResponse = "Recorded personal fact: $fact", userId = userId)
    }

    /**
     * Asynchronously persist turn in background without blocking caller thread.
     */
    fun recordTurnAsync(userQuery: String, aiResponse: String, userId: String = "user_default") {
        scope.launch {
            recordTurn(userQuery, aiResponse, userId)
        }
    }

    private fun parseMem0SearchResults(jsonStr: String): List<String> {
        val results = mutableListOf<String>()
        if (jsonStr.isBlank()) return results

        try {
            val jsonElement = JsonParser.parseString(jsonStr)
            if (jsonElement.isJsonArray) {
                val array = jsonElement.asJsonArray
                array.forEach { item ->
                    if (item.isJsonObject) {
                        val obj = item.asJsonObject
                        val text = obj.get("memory")?.asString
                            ?: obj.get("text")?.asString
                            ?: obj.get("content")?.asString
                        if (!text.isNullOrBlank()) {
                            results.add(text.trim())
                        }
                    } else if (item.isJsonPrimitive) {
                        results.add(item.asString.trim())
                    }
                }
            } else if (jsonElement.isJsonObject) {
                val obj = jsonElement.asJsonObject
                val resultsArray = obj.getAsJsonArray("results")
                resultsArray?.forEach { item ->
                    if (item.isJsonObject) {
                        val memoryText = item.asJsonObject.get("memory")?.asString
                            ?: item.asJsonObject.get("text")?.asString
                        if (!memoryText.isNullOrBlank()) {
                            results.add(memoryText.trim())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Error parsing Mem0 search results JSON", e)
        }

        return results
    }
}
