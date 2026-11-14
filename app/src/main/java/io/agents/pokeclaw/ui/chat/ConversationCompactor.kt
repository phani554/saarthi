// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.content.Context
import io.agents.pokeclaw.utils.XLog
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages conversation memory compaction.
 * Compacts older conversation turns into structured, bulleted summaries (Key Facts, Decisions, Actions).
 */
object ConversationCompactor {

    private const val TAG = "Compactor"
    private const val COMPACT_CHAR_THRESHOLD = 8000 // ~2000 tokens, ~50% of 32K
    private const val KEEP_RECENT_MESSAGES = 5

    /**
     * Check if conversation needs compaction.
     */
    fun needsCompaction(messages: List<ChatMessage>): Boolean {
        val totalChars = messages.sumOf { it.content.length }
        return totalChars > COMPACT_CHAR_THRESHOLD && messages.size > KEEP_RECENT_MESSAGES + 3
    }

    /**
     * Compact a conversation: summarize older messages into structured digest, save digest.
     */
    fun compact(
        engine: Engine,
        messages: List<ChatMessage>,
        context: Context,
        conversationId: String
    ): String? {
        if (!needsCompaction(messages)) return null

        val olderMessages = messages.dropLast(KEEP_RECENT_MESSAGES)
        val olderText = buildString {
            olderMessages.forEach { msg ->
                when (msg.role) {
                    ChatMessage.Role.USER -> appendLine("User: ${msg.content}")
                    ChatMessage.Role.ASSISTANT -> appendLine("Assistant: ${msg.content}")
                    else -> {}
                }
            }
        }

        if (olderText.isBlank()) return null

        try {
            val tempConversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of("You are an ultra-fast summarization assistant. Produce structured bulleted digests of conversation history in English, without preamble."),
                    samplerConfig = SamplerConfig(topK = 32, topP = 0.9, temperature = 0.2)
                )
            )

            val prompt = """Compact this conversation history into structured key points.
Write the digest in English. Keep names, numbers and quoted messages exactly as said.
- Key Facts: [important names, details, preferences]
- Decisions: [agreed outcomes]
- Pending Actions: [open tasks]

History:
$olderText"""

            // Always close the temporary session: a leaked one shows up as "session already exists" /
            // "Model busy" for every later chat or local task until the app restarts.
            val digest = try {
                val response = tempConversation.sendMessage(prompt)
                response?.toString()?.trim()
            } finally {
                try {
                    tempConversation.close()
                } catch (e: Exception) {
                    XLog.w(TAG, "Compaction: temp conversation close failed", e)
                }
            } ?: return null

            // Save digest to .memory.md
            saveDigest(context, conversationId, digest, messages)

            XLog.i(TAG, "Compacted: ${olderMessages.size} messages → ${digest.length} chars digest")
            return digest
        } catch (e: Exception) {
            XLog.e(TAG, "Compaction failed", e)
            return null
        }
    }

    private fun chatDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "chats")

    /** Reads `key: value` from the leading frontmatter only (a few lines), or null. */
    private fun frontmatterValue(file: File, key: String): String? {
        return try {
            var result: String? = null
            file.useLines { lines ->
                var inFm = false
                for (line in lines) {
                    if (line == "---") { if (!inFm) { inFm = true; continue } else break }
                    if (inFm && line.startsWith("$key: ")) { result = line.removePrefix("$key: ").trim(); break }
                }
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Finds the digest file by name (next to the chat file, or `<id>.memory.md`) instead of reading
     * every chat file. Falls back to the frontmatter of the digest files only (older names).
     */
    private fun findDigestFile(context: Context, conversationId: String): File? {
        val dir = chatDir(context)
        if (!dir.exists()) return null
        ChatHistoryManager.fileFor(context, conversationId)?.let { md ->
            val byChat = File(dir, md.nameWithoutExtension + ".memory.md")
            if (byChat.exists()) return byChat
        }
        val byId = File(dir, "$conversationId.memory.md")
        if (byId.exists()) return byId
        return dir.listFiles()?.firstOrNull { file ->
            file.name.endsWith(".memory.md") && frontmatterValue(file, "conversation") == conversationId
        }
    }

    /**
     * Save digest to .memory.md file alongside the conversation .md file.
     */
    private fun saveDigest(context: Context, conversationId: String, digest: String, messages: List<ChatMessage>) {
        val dir = chatDir(context)
        if (!dir.exists()) dir.mkdirs()

        val memoryFile = findDigestFile(context, conversationId)
            ?: ChatHistoryManager.fileFor(context, conversationId)?.let { md ->
                File(dir, md.nameWithoutExtension + ".memory.md")
            }
            ?: File(dir, "$conversationId.memory.md")

        val content = buildString {
            appendLine("---")
            appendLine("conversation: $conversationId")
            appendLine("compacted_at: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())}")
            appendLine("original_messages: ${messages.size}")
            appendLine("---")
            appendLine()
            appendLine(digest)
        }

        memoryFile.writeText(content)
    }

    /**
     * Load digest for a conversation.
     * Returns null if no digest exists. Reads at most one digest file (plus digest headers for old names).
     */
    fun loadDigest(context: Context, conversationId: String): String? {
        val memoryFile = findDigestFile(context, conversationId) ?: return null

        val lines = try { memoryFile.readLines() } catch (_: Exception) { return null }
        var pastFrontmatter = false
        var frontmatterCount = 0
        val digest = StringBuilder()

        for (line in lines) {
            if (line == "---" && !pastFrontmatter) {
                frontmatterCount++
                if (frontmatterCount >= 2) { pastFrontmatter = true; continue }
                continue
            }
            if (pastFrontmatter && line.isNotBlank()) {
                if (digest.isNotEmpty()) digest.appendLine()
                digest.append(line)
            }
        }

        return digest.toString().trim().ifEmpty { null }
    }

    /**
     * Build system prompt with conversation context for chatroom switch.
     */
    fun buildRestoredSystemPrompt(
        context: Context,
        conversationId: String,
        recentMessages: List<ChatMessage>
    ): String {
        val digest = loadDigest(context, conversationId)
        val recentText = buildString {
            recentMessages.forEach { msg ->
                when (msg.role) {
                    ChatMessage.Role.USER -> appendLine("User: ${msg.content}")
                    ChatMessage.Role.ASSISTANT -> appendLine("Assistant: ${msg.content}")
                    else -> {}
                }
            }
        }

        return buildString {
            appendLine("You are a helpful AI assistant on an Android phone.")
            if (digest != null) {
                appendLine()
                appendLine("Summary of our earlier conversation:")
                appendLine(digest)
            }
            if (recentText.isNotBlank()) {
                appendLine()
                appendLine("Most recent messages:")
                appendLine(recentText)
            }
            appendLine()
            appendLine("Continue naturally from here.")
        }
    }
}
