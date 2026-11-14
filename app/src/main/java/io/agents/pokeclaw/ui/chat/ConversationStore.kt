// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns persisted conversation identity and markdown-backed history operations.
 *
 * All disk work (markdown files, SQLite index) runs on one shared background thread, so it never
 * blocks the UI and runs in the order it was asked for (a save always lands before a later load).
 * Results come back on the main thread through the callbacks.
 *
 * [currentConversationId] is only read and changed on the main thread.
 */
class ConversationStore(
    context: Context
) {

    companion object {
        private const val TAG = "ConversationStore"
        private const val CURRENT_CONVERSATION_ID_KEY = "CURRENT_CONVERSATION_ID"

        private fun newConversationId(): String = "chat_${System.currentTimeMillis()}"

        /**
         * One serial IO thread for the whole app (not per Activity), so a save queued by an
         * Activity that is being recreated still runs before the new Activity's restore.
         */
        private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "chat-history-io").apply { isDaemon = true }
        }
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    private val appContext: Context = context.applicationContext

    data class SessionSnapshot(
        val conversationId: String,
        val messages: List<ChatMessage>,
        val conversations: List<ChatHistoryManager.ConversationSummary>
    )

    var currentConversationId: String = KVUtils.getString(CURRENT_CONVERSATION_ID_KEY, "")
        .takeIf { it.isNotEmpty() }
        ?: newConversationId()
        private set

    /** Runs [work] on the IO thread and hands its result to [onResult] on the main thread. */
    private fun <T> runIo(work: () -> T, onResult: (T) -> Unit) {
        io.execute {
            val result = try {
                work()
            } catch (e: Exception) {
                XLog.e(TAG, "chat history IO failed", e)
                return@execute
            }
            mainHandler.post { onResult(result) }
        }
    }

    // Each step is guarded on its own: a failed save must not stop the load (or the callback) after it.
    private fun safeSave(id: String, messages: List<ChatMessage>, modelName: String) {
        try {
            ChatHistoryManager.save(appContext, id, messages, modelName)
        } catch (e: Exception) {
            XLog.e(TAG, "save failed for $id", e)
        }
    }

    private fun safeList(): List<ChatHistoryManager.ConversationSummary> = try {
        ChatHistoryManager.listConversations(appContext)
    } catch (e: Exception) {
        XLog.e(TAG, "list failed", e)
        emptyList()
    }

    private fun safeLoad(file: java.io.File): List<ChatMessage> = try {
        ChatHistoryManager.load(file)
    } catch (e: Exception) {
        XLog.e(TAG, "load failed for ${file.name}", e)
        emptyList()
    }

    fun refreshSidebar(onResult: (List<ChatHistoryManager.ConversationSummary>) -> Unit) {
        runIo({ safeList() }, onResult)
    }

    /**
     * Loads the conversation that was open last time; [onResult] gets null if it no longer exists.
     * [onResult] is always called (null on any error), because the caller waits for it.
     */
    fun restoreLastConversation(onResult: (SessionSnapshot?) -> Unit) {
        val id = currentConversationId
        runIo({
            val conversations = safeList()
            val match = conversations.firstOrNull { it.id == id }
            if (match == null) null
            else SessionSnapshot(
                conversationId = id,
                messages = safeLoad(match.file),
                conversations = conversations
            )
        }, onResult)
    }

    /**
     * Saves [messages] (a snapshot taken on the main thread) under the current conversation id.
     */
    fun saveCurrent(
        messages: List<ChatMessage>,
        modelName: String,
        onResult: (List<ChatHistoryManager.ConversationSummary>) -> Unit
    ) {
        val id = currentConversationId
        persistCurrentConversationId()
        runIo({
            safeSave(id, messages, modelName)
            safeList()
        }, onResult)
    }

    /**
     * Saves the current chat and switches to a new, empty one. The new id is set right away
     * (main thread); [onResult] gets the refreshed sidebar.
     */
    fun startNewConversation(
        currentMessages: List<ChatMessage>,
        modelName: String,
        onResult: (SessionSnapshot) -> Unit
    ) {
        val oldId = currentConversationId
        val newId = newConversationId()
        currentConversationId = newId
        persistCurrentConversationId()
        runIo({
            safeSave(oldId, currentMessages, modelName)
            SessionSnapshot(
                conversationId = newId,
                messages = emptyList(),
                conversations = safeList()
            )
        }, onResult)
    }

    /**
     * Saves the current chat and opens [target]. The id switches right away (main thread);
     * [onResult] gets the loaded messages.
     */
    fun openConversation(
        target: ChatHistoryManager.ConversationSummary,
        currentMessages: List<ChatMessage>,
        modelName: String,
        onResult: (SessionSnapshot) -> Unit
    ) {
        val oldId = currentConversationId
        currentConversationId = target.id
        persistCurrentConversationId()
        runIo({
            safeSave(oldId, currentMessages, modelName)
            SessionSnapshot(
                conversationId = target.id,
                messages = safeLoad(target.file),
                conversations = safeList()
            )
        }, onResult)
    }

    fun renameConversation(
        target: ChatHistoryManager.ConversationSummary,
        newTitle: String,
        onResult: (Boolean) -> Unit
    ) {
        runIo({ ChatHistoryManager.rename(target.file, newTitle) }, onResult)
    }

    fun deleteConversation(
        target: ChatHistoryManager.ConversationSummary,
        onResult: (Boolean) -> Unit
    ) {
        runIo({ ChatHistoryManager.delete(appContext, target) }, onResult)
    }

    private fun persistCurrentConversationId() {
        KVUtils.putString(CURRENT_CONVERSATION_ID_KEY, currentConversationId)
    }
}
