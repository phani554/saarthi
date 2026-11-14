// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import android.content.Context
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

data class LocalEngineLease(
    val engine: Engine,
    val backendLabel: String,
)

data class LocalConversationLease(
    val engine: Engine,
    val conversation: Conversation,
    val backendLabel: String,
)

/**
 * Who holds the one LiteRT-LM session the shared engine allows. On a "session already exists"
 * conflict a lower-priority caller gives up at once (it must not wait behind a running task), and an
 * equal or higher one waits for the holder to close its conversation (e.g. chat closing for a task).
 */
enum class LocalSessionOwner(val priority: Int) {
    SINGLE_SHOT(0),
    CHAT(1),
    TASK(2),
}

data class LocalSingleShotResult(
    val text: String?,
    val backendLabel: String,
)

object LocalModelRuntime {

    private const val TAG = "LocalModelRuntime"
    private const val DEFAULT_RETRY_COUNT = 5
    private const val DEFAULT_RESET_ATTEMPT = 3
    private const val DEFAULT_RETRY_SLEEP_MS = 1500L

    fun acquireSharedEngine(
        context: Context,
        modelPath: String,
        preferCpu: Boolean = false,
    ): LocalEngineLease {
        XLog.i(
            TAG,
            "acquireSharedEngine: initializing local backend for $modelPath (preferCpu=$preferCpu)",
        )

        try {
            val engine = EngineHolder.getOrCreate(modelPath, context.cacheDir.path, preferCpu)
            return LocalEngineLease(
                engine = engine,
                backendLabel = EngineHolder.getBackendLabel(modelPath) ?: "GPU",
            )
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            XLog.e(TAG, "acquireSharedEngine: backend initialization failed for $modelPath", e)
            throw IllegalStateException("Local model could not start on NPU, GPU or CPU", e)
        }
    }

    fun resetSharedEngine() {
        try {
            EngineHolder.close()
        } catch (e: Exception) {
            XLog.w(TAG, "resetSharedEngine: close failed", e)
        }
    }

    fun currentBackendLabel(modelPath: String?): String? {
        return EngineHolder.getBackendLabel(modelPath)
    }

    private class SessionHolder(val conversation: Conversation, val owner: LocalSessionOwner)

    /** The conversation last opened through [openConversation] (the engine allows one at a time). */
    @Volatile
    private var sessionHolder: SessionHolder? = null

    /** Owners call this when they close [conversation], so a stale holder never blocks others. */
    fun releaseSession(conversation: Conversation?) {
        if (conversation == null) return
        synchronized(this) {
            if (sessionHolder?.conversation === conversation) sessionHolder = null
        }
    }

    /**
     * Opens a conversation on the one shared engine (chat, tasks and single-shot calls all use the same
     * Engine from [EngineHolder]; no second engine is ever loaded). The engine allows one session: when
     * another conversation is still open, a caller of equal or higher [owner] priority retries up to
     * [maxRetries] times (the holder is usually closing, e.g. chat releasing for a task), and a lower
     * one fails at once.
     */
    fun openConversation(
        context: Context,
        modelPath: String,
        conversationConfig: ConversationConfig,
        preferCpu: Boolean = false,
        maxRetries: Int = DEFAULT_RETRY_COUNT,
        owner: LocalSessionOwner = LocalSessionOwner.CHAT,
    ): LocalConversationLease {
        val engineLease = acquireSharedEngine(context, modelPath, preferCpu = preferCpu)
        var attempt = 0
        while (true) {
            attempt++
            try {
                val conversation = engineLease.engine.createConversation(conversationConfig)
                synchronized(this) { sessionHolder = SessionHolder(conversation, owner) }
                return LocalConversationLease(
                    engine = engineLease.engine,
                    conversation = conversation,
                    backendLabel = engineLease.backendLabel,
                )
            } catch (e: Exception) {
                XLog.w(TAG, "openConversation failed for $modelPath (owner=$owner, attempt $attempt): ${e.message}")
                if (!isSessionConflict(e)) throw e
                val holder = sessionHolder
                val outranked = holder != null && holder.owner.priority > owner.priority
                if (outranked || attempt >= maxRetries.coerceAtLeast(1)) {
                    throw IllegalStateException(
                        "Local model session already in use" + (holder?.let { " by ${it.owner}" } ?: ""), e
                    )
                }
                try {
                    Thread.sleep(DEFAULT_RETRY_SLEEP_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Local model session already in use", e)
                }
            }
        }
    }

    fun runSingleShot(
        context: Context,
        modelPath: String,
        systemPrompt: String,
        prompt: String,
        temperature: Double = 0.3,
        preferCpu: Boolean = false,
    ): LocalSingleShotResult {
        // NPU bundles reject a custom sampler, so leave it default there (same rule as LocalLlmClient).
        val backendLabel = acquireSharedEngine(context, modelPath, preferCpu = preferCpu).backendLabel
        val conversationConfig = if (backendLabel == "NPU") {
            ConversationConfig(systemInstruction = Contents.of(systemPrompt))
        } else {
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                samplerConfig = SamplerConfig(
                    topK = 64,
                    topP = 0.95,
                    temperature = temperature,
                )
            )
        }
        val lease = openConversation(
            context = context,
            modelPath = modelPath,
            conversationConfig = conversationConfig,
            preferCpu = preferCpu,
            maxRetries = 1,
            owner = LocalSessionOwner.SINGLE_SHOT,
        )

        return try {
            val response = lease.conversation.sendMessage(prompt, emptyMap())
            LocalSingleShotResult(
                text = response.contents?.toString()?.trim(),
                backendLabel = lease.backendLabel,
            )
        } finally {
            try {
                lease.conversation.close()
            } catch (e: Exception) {
                XLog.w(TAG, "runSingleShot: conversation close failed", e)
            }
            releaseSession(lease.conversation)
        }
    }


    fun isSessionConflict(error: Throwable?): Boolean {
        val message = error?.message.orEmpty()
        if (message.isEmpty()) return false
        return message.contains("A session already exists", ignoreCase = true) ||
            message.contains("Only one session is supported at a time", ignoreCase = true) ||
            message.contains("session already in use", ignoreCase = true)
    }
}
