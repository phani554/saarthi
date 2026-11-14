// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves and loads chat conversations as markdown files.
 *
 * Storage: /storage/emulated/0/PokeClaw/chats/
 * Format: 2026-04-04-send-hi-to-mom.md
 *
 * Each file:
 * ---
 * title: Send hi to Mom
 * created: 2026-04-04T15:30:00
 * model: FunctionGemma-270M
 * ---
 *
 * ## User
 * Open WhatsApp and send hi to Mom
 *
 * ## 🦞 Assistant
 * On it. Checking your screen.
 *
 * ## System
 * Task completed.
 */
object ChatHistoryManager {

    private const val MESSAGE_TIMESTAMP_PREFIX = "<!-- pokeclaw:timestamp="
    private const val MESSAGE_TIMESTAMP_SUFFIX = " -->"
    private val frontmatterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    data class ConversationSummary(
        val id: String,
        val title: String,
        val created: Long,
        val file: File
    )

    // Callers run these on a background thread (ConversationStore's single IO thread). @Synchronized keeps
    // the caches below consistent if another thread ever calls in.
    @Volatile private var chatDirCache: File? = null
    /** Sidebar entries by absolute file path; filled by one directory scan, then kept current by save/rename/delete. */
    private var summaryCache: MutableMap<String, ConversationSummary>? = null
    /** Lock-free id -> file view of [summaryCache], republished after every change (read from the main thread). */
    @Volatile private var idToFile: Map<String, File>? = null
    /** One long-lived index helper instead of a new SQLiteOpenHelper per save. */
    private var chatDb: ChatDatabase? = null

    private fun getChatDir(context: Context): File {
        chatDirCache?.let { if (it.exists()) return it }
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "chats")
        if (!dir.exists()) dir.mkdirs()
        chatDirCache = dir
        return dir
    }

    private fun db(context: Context): ChatDatabase =
        chatDb ?: ChatDatabase(context.applicationContext).also { chatDb = it }

    private fun summaries(context: Context): MutableMap<String, ConversationSummary> {
        summaryCache?.let { return it }
        val dir = getChatDir(context)
        val map = LinkedHashMap<String, ConversationSummary>()
        dir.listFiles()
            ?.filter { it.extension == "md" && !it.name.endsWith(".memory.md") } // digests aren't chats
            ?.forEach { file -> readSummary(file)?.let { map[file.absolutePath] = it } }
        summaryCache = map
        publish()
        return map
    }

    private fun publish() {
        idToFile = summaryCache?.values?.associate { it.id to it.file }
    }

    private fun readSummary(file: File): ConversationSummary? {
        return try {
            var title = file.nameWithoutExtension
            var id = ""
            file.useLines { lines ->
                var inFm = false
                for (line in lines) {
                    if (line == "---") { if (!inFm) { inFm = true; continue } else break }
                    if (inFm && line.startsWith("title: ")) title = line.removePrefix("title: ")
                    if (inFm && line.startsWith("id: ")) id = line.removePrefix("id: ")
                }
            }
            ConversationSummary(
                id = id.ifEmpty { file.nameWithoutExtension },
                title = title,
                created = file.lastModified(),
                file = file
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Save a conversation to markdown file.
     */
    @Synchronized
    fun save(context: Context, conversationId: String, messages: List<ChatMessage>, model: String) {
        if (messages.isEmpty()) return

        // Generate title from first user message
        val firstUserMsg = messages.firstOrNull { it.role == ChatMessage.Role.USER }
        val title = firstUserMsg?.content?.take(50)?.replace(Regex("[^a-zA-Z0-9\\s]"), "")?.trim()?.replace("\\s+".toRegex(), "-")?.lowercase() ?: "untitled"
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(messages.first().timestamp))
        // The conversation id is in the name, so Hindi/Telugu first messages (which strip to "") and
        // two chats that start the same way no longer overwrite each other.
        val safeId = conversationId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val fileName = "$dateStr-${title.ifBlank { "chat" }}-$safeId.md"

        val chatDir = getChatDir(context)
        val cache = summaries(context)
        // Reuse this conversation's file even if its derived title changed (e.g. saved before the first user message).
        // Looked up in the in-memory index instead of re-listing the folder on every save.
        val file = cache.values.firstOrNull {
            it.file.name.endsWith("-$safeId.md") && !it.file.name.endsWith(".memory.md") && it.file.exists()
        }?.file ?: File(chatDir, fileName)
        // Older builds named the file without the id; take it over (title included) if it is this conversation.
        val legacyFile = File(chatDir, "$dateStr-$title.md")
        val legacyIsThis = !file.exists() && legacyFile.exists() && readFrontmatterValue(legacyFile, "id") == conversationId
        // Keep a title the user set via rename instead of re-deriving it from the first message.
        val keptTitle = when {
            file.exists() -> readFrontmatterValue(file, "title")
            legacyIsThis -> readFrontmatterValue(legacyFile, "title")
            else -> null
        }
        val indexTitle = keptTitle?.takeIf { it != "Untitled" } ?: firstUserMsg?.content?.take(80) ?: "Untitled"

        val sb = StringBuilder()
        // Frontmatter
        sb.appendLine("---")
        sb.appendLine("id: $conversationId")
        sb.appendLine("title: $indexTitle")
        sb.appendLine("created: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(messages.first().timestamp))}")
        sb.appendLine("model: $model")
        sb.appendLine("---")
        sb.appendLine()

        // Messages
        messages.forEach { msg ->
            when (msg.role) {
                ChatMessage.Role.USER -> {
                    sb.appendLine("## User")
                    sb.appendLine(serializeTimestamp(msg.timestamp))
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                ChatMessage.Role.ASSISTANT -> {
                    if (!msg.modelName.isNullOrEmpty()) {
                        sb.appendLine("## 🦞 Assistant [${msg.modelName}]")
                    } else {
                        sb.appendLine("## 🦞 Assistant")
                    }
                    sb.appendLine(serializeTimestamp(msg.timestamp))
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                ChatMessage.Role.SYSTEM -> {
                    sb.appendLine("## System")
                    sb.appendLine(serializeTimestamp(msg.timestamp))
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                ChatMessage.Role.TOOL_GROUP -> {
                    sb.appendLine("## Tools")
                    sb.appendLine(serializeTimestamp(msg.timestamp))
                    msg.toolSteps?.forEach { step ->
                        val icon = if (step.success) "✓" else "○"
                        if (step.summary.isBlank()) sb.appendLine("- $icon ${step.toolName}")
                        else sb.appendLine("- $icon ${step.toolName} → ${step.summary}")
                    }
                    sb.appendLine()
                }
            }
        }

        file.writeText(sb.toString())
        if (legacyIsThis && legacyFile.delete()) cache.remove(legacyFile.absolutePath)
        cache[file.absolutePath] = ConversationSummary(
            id = conversationId,
            title = indexTitle,
            created = file.lastModified(),
            file = file
        )
        publish()

        // Index in SQLite for fast search: one transaction for the conversation row and all its messages.
        try {
            val helper = db(context)
            val sql = helper.writableDatabase
            sql.beginTransaction()
            try {
                helper.indexConversation(
                    id = conversationId,
                    title = indexTitle,
                    created = messages.first().timestamp,
                    model = model,
                    filePath = file.absolutePath,
                    messages = messages
                )
                sql.setTransactionSuccessful()
            } finally {
                sql.endTransaction()
            }
        } catch (_: Exception) { /* index failure is non-fatal */ }
    }

    /**
     * The chat file of [conversationId] from the in-memory index (no folder re-scan), or null.
     * Lock-free once the index is built, so a main-thread caller never waits on a save in progress.
     */
    fun fileFor(context: Context, conversationId: String): File? {
        val map = idToFile ?: synchronized(this) { summaries(context); idToFile } ?: return null
        return map[conversationId]?.takeIf { it.exists() }
    }

    /** Reads `key: value` from a chat file's frontmatter, or null. */
    private fun readFrontmatterValue(file: File, key: String): String? {
        return try {
            var result: String? = null
            file.useLines { lines ->
                var inFm = false
                for (line in lines) {
                    if (line == "---") { if (!inFm) { inFm = true; continue } else break }
                    if (inFm && line.startsWith("$key: ")) { result = line.removePrefix("$key: "); break }
                }
            }
            result?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Load a conversation from markdown file.
     */
    @Synchronized
    fun load(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()

        val messages = mutableListOf<ChatMessage>()
        val lines = file.readLines()

        var inFrontmatter = false
        var frontmatterDone = false
        var fallbackConversationTimestamp = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        var currentRole: ChatMessage.Role? = null
        var currentModelName: String? = null
        var currentTimestamp: Long? = null
        val contentBuilder = StringBuilder()

        for (line in lines) {
            // Only the leading block is frontmatter; a "---" rule inside a message is content.
            if (line == "---" && !frontmatterDone && currentRole == null) {
                if (!inFrontmatter) { inFrontmatter = true; continue }
                else { inFrontmatter = false; frontmatterDone = true; continue }
            }
            if (inFrontmatter) {
                if (line.startsWith("created: ")) {
                    parseFrontmatterTimestamp(line.removePrefix("created: "))?.let {
                        fallbackConversationTimestamp = it
                    }
                }
                continue
            }

            when {
                line.startsWith("## User") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp)
                    currentRole = ChatMessage.Role.USER
                    currentModelName = null
                    currentTimestamp = null
                }
                line.startsWith("## 🦞 Assistant") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp)
                    currentRole = ChatMessage.Role.ASSISTANT
                    // Extract model name from "## 🦞 Assistant [ModelName]"
                    val bracketMatch = Regex("\\[(.+)]").find(line)
                    currentModelName = bracketMatch?.groupValues?.get(1)
                    currentTimestamp = null
                }
                line.startsWith("## System") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp)
                    currentRole = ChatMessage.Role.SYSTEM
                    currentModelName = null
                    currentTimestamp = null
                }
                line.startsWith("## Tools") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp)
                    currentRole = ChatMessage.Role.TOOL_GROUP
                    currentModelName = null
                    currentTimestamp = null
                }
                currentRole != null && line.startsWith(MESSAGE_TIMESTAMP_PREFIX) -> {
                    currentTimestamp = parseMessageTimestamp(line)
                }
                else -> {
                    if (currentRole != null && line.isNotBlank()) {
                        if (contentBuilder.isNotEmpty()) contentBuilder.appendLine()
                        contentBuilder.append(line)
                    }
                }
            }
        }
        flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp)

        return messages
    }

    private fun flushMessage(
        messages: MutableList<ChatMessage>,
        role: ChatMessage.Role?,
        content: StringBuilder,
        modelName: String? = null,
        timestamp: Long? = null,
        fallbackConversationTimestamp: Long
    ) {
        if (role != null && content.isNotEmpty()) {
            val resolvedTimestamp = timestamp ?: (fallbackConversationTimestamp + messages.size * 1000L)
            messages.add(
                ChatMessage(
                    role = role,
                    content = content.toString().trim(),
                    timestamp = resolvedTimestamp,
                    modelName = modelName
                )
            )
            content.clear()
        }
    }

    private fun serializeTimestamp(timestamp: Long): String {
        return "$MESSAGE_TIMESTAMP_PREFIX$timestamp$MESSAGE_TIMESTAMP_SUFFIX"
    }

    private fun parseMessageTimestamp(line: String): Long? {
        return line.removePrefix(MESSAGE_TIMESTAMP_PREFIX)
            .removeSuffix(MESSAGE_TIMESTAMP_SUFFIX)
            .toLongOrNull()
    }

    private fun parseFrontmatterTimestamp(raw: String): Long? {
        return try {
            frontmatterDateFormat.parse(raw.trim())?.time
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Delete all saved chat files and clear SQLite index.
     */
    @Synchronized
    fun clearAllHistory(context: Context) {
        try {
            val dir = getChatDir(context)
            dir.listFiles()?.forEach { it.delete() }
            summaryCache = null
            idToFile = null
            val sql = db(context).writableDatabase
            sql.beginTransaction()
            try {
                sql.execSQL("DELETE FROM messages")
                sql.execSQL("DELETE FROM conversations")
                sql.setTransactionSuccessful()
            } finally {
                sql.endTransaction()
            }
        } catch (_: Exception) {}
    }

    /**
     * List all saved conversations, newest first.
     * The folder is scanned once; later calls come from the in-memory index that save/rename/delete keep current.
     */
    @Synchronized
    fun listConversations(context: Context): List<ConversationSummary> {
        val cache = summaries(context)
        // Drop entries whose file vanished outside this class (e.g. cleared app storage).
        if (cache.entries.removeAll { !it.value.file.exists() }) publish()
        return cache.values.sortedByDescending { it.created }
    }

    /**
     * Rename a conversation by updating the title in the markdown frontmatter.
     */
    @Synchronized
    fun rename(file: File, newTitle: String): Boolean {
        if (!file.exists()) return false
        try {
            val content = file.readText()
            val updated = content.replaceFirst(
                Regex("(?m)^title: .+$"),
                "title: $newTitle"
            )
            file.writeText(updated)
            val summary = readSummary(file)
            if (summary != null) summaryCache?.put(file.absolutePath, summary)
            publish()
            return true
        } catch (e: Exception) {
            io.agents.pokeclaw.utils.XLog.e("ChatHistoryManager", "Failed to rename conversation", e)
            return false
        }
    }

    /**
     * Delete a conversation (its file, its compaction digest and its search-index rows).
     */
    @Synchronized
    fun delete(context: Context, conversation: ConversationSummary): Boolean {
        val file = conversation.file
        val deleted = file.delete() || !file.exists()
        if (deleted) {
            summaryCache?.remove(file.absolutePath)
            publish()
            try { File(file.parentFile, file.nameWithoutExtension + ".memory.md").delete() } catch (_: Exception) {}
            try { db(context).deleteConversation(conversation.id) } catch (_: Exception) {}
        }
        return deleted
    }
}
