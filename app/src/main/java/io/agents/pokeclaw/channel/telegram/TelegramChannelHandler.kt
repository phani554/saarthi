// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.telegram

import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.channel.ChannelHandler
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TelegramChannelHandler(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private var botToken: String,
) : ChannelHandler {

    override val channel = Channel.TELEGRAM

    @Volatile
    private var lastChatId: Long? = null
    @Volatile
    private var pollingActive = false
    // Each init() gets a new generation; an old poller exits as soon as it sees a newer one.
    @Volatile
    private var pollGeneration = 0
    private var pollingThread: Thread? = null

    private val pollingHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun apiUrl(method: String): String =
        "https://api.telegram.org/bot$botToken/$method"

    override fun isConnected(): Boolean = pollingActive

    override fun init() {
        if (botToken.isEmpty()) {
            XLog.w(TAG, "Telegram Bot Token not configured, Telegram channel will be unavailable")
            return
        }

        pollingActive = true
        val gen = ++pollGeneration
        pollingThread = Thread({
            // Persisted so updates already handled are not replayed after a restart.
            var offset = KVUtils.getTelegramUpdateOffset()
            XLog.i(TAG, "Telegram polling thread started")
            while (pollingActive && gen == pollGeneration) {
                try {
                    val reqBody = JSONObject().apply {
                        put("offset", offset)
                        put("timeout", 30)
                        put("allowed_updates", org.json.JSONArray().put("message"))
                    }
                    val request = okhttp3.Request.Builder()
                        .url(apiUrl("getUpdates"))
                        .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), reqBody.toString()))
                        .build()
                    val response = pollingHttpClient.newCall(request).execute()
                    val body = response.body?.string()
                    val code = response.code
                    response.close()

                    if (body == null) {
                        XLog.w(TAG, "Telegram getUpdates response body is empty, code=$code")
                        continue
                    }

                    val json = JSONObject(body)
                    if (!json.optBoolean("ok", false)) {
                        XLog.e(TAG, "Telegram getUpdates failed: code=$code")
                        if (code == 401 || code == 404) {
                            XLog.e(TAG, "Telegram Bot Token invalid, stopping polling")
                            break
                        }
                        Thread.sleep(5000)
                        continue
                    }

                    val result = json.optJSONArray("result") ?: continue
                    if (gen != pollGeneration) break
                    for (i in 0 until result.length()) {
                        val update = result.getJSONObject(i)
                        val updateId = update.getLong("update_id")
                        if (updateId >= offset) {
                            offset = updateId + 1
                            KVUtils.setTelegramUpdateOffset(offset)
                        }

                        val message = update.optJSONObject("message") ?: continue
                        val text = message.optString("text", "")
                        if (text.isEmpty()) continue

                        val chatId = message.getJSONObject("chat").getLong("id")
                        val messageId = message.getInt("message_id")
                        val fromId = message.optJSONObject("from")?.optLong("id", 0L) ?: 0L
                        if (!isAllowedSender(fromId, chatId)) continue

                        // Skip stale commands queued while the phone was off/offline.
                        val sentAtSec = message.optLong("date", 0L)
                        if (sentAtSec > 0L && System.currentTimeMillis() / 1000L - sentAtSec > MAX_MESSAGE_AGE_SEC) {
                            XLog.w(TAG, "Telegram skipping stale message (age > ${MAX_MESSAGE_AGE_SEC}s)")
                            continue
                        }
                        lastChatId = chatId

                        XLog.i(TAG, "[${channel.displayName}] Message received, chatId=$chatId")
                        ChannelManager.dispatchMessage(channel, text, messageId.toString())
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    XLog.d(TAG, "Telegram polling timeout, continuing to poll")
                } catch (e: Exception) {
                    if (pollingActive) {
                        XLog.w(TAG, "Telegram polling exception, retrying in 5 seconds", e)
                        try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                    }
                }
            }
            XLog.i(TAG, "Telegram polling thread exited")
        }, "telegram-polling").apply { isDaemon = true; start() }

        XLog.i(TAG, "Telegram Long Polling started")
    }

    override fun disconnect() {
        pollingActive = false
        pollGeneration++
        pollingThread?.interrupt()
        pollingThread = null
        lastChatId = null
        XLog.i(TAG, "Telegram Long Polling stopped")
    }

    override fun reinitFromStorage() {
        disconnect()
        botToken = KVUtils.getTelegramBotToken()
        init()
    }

    override fun sendMessage(content: String, messageID: String) {
        val chatId = lastChatId
        if (chatId == null) {
            XLog.w(TAG, "Telegram reply failed: no available chatId")
            return
        }
        scope.launch {
            try {
                sendChunked(chatId, content)
            } catch (e: Exception) {
                XLog.e(TAG, "Telegram reply failed", e)
            }
        }
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        val chatId = lastChatId ?: return
        // Never send a screenshot anywhere but the paired owner's chat.
        if (!isOwnerChat(chatId)) {
            XLog.w(TAG, "Telegram image not sent: chat is not the paired owner's")
            return
        }
        scope.launch {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart(
                        "photo", "screenshot.png",
                        imageBytes.toRequestBody("image/png".toMediaTypeOrNull())
                    )
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(apiUrl("sendPhoto"))
                    .post(body)
                    .build()
                val response = httpClient.newCall(request).execute()
                XLog.i(TAG, "Telegram image send response: ${response.code}")
                response.close()
            } catch (e: Exception) {
                XLog.e(TAG, "Telegram image send failed", e)
            }
        }
    }

    override fun sendFile(file: java.io.File, messageID: String) {
        val chatId = lastChatId ?: return
        if (!isOwnerChat(chatId)) {
            XLog.w(TAG, "Telegram file not sent: chat is not the paired owner's")
            return
        }
        scope.launch {
            try {
                val mimeType = when (file.extension.lowercase()) {
                    "png", "jpg", "jpeg", "gif", "bmp", "webp" -> "image/${file.extension.lowercase()}"
                    else -> "application/octet-stream"
                }
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart(
                        "document", file.name,
                        file.readBytes().toRequestBody(mimeType.toMediaTypeOrNull())
                    )
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(apiUrl("sendDocument"))
                    .post(body)
                    .build()
                val response = httpClient.newCall(request).execute()
                XLog.i(TAG, "Telegram file send response: ${response.code}")
                response.close()
            } catch (e: Exception) {
                XLog.e(TAG, "Telegram file send failed", e)
            }
        }
    }

    override fun getLastSenderId(): String? = lastChatId?.toString()

    override fun restoreRoutingContext(targetUserId: String) {
        targetUserId.toLongOrNull()?.let { lastChatId = it }
    }

    override fun sendMessageToUser(userId: String, content: String) {
        val chatId = userId.toLongOrNull()
        if (chatId == null) {
            XLog.w(TAG, "Telegram sendMessageToUser failed: invalid chatId: $userId")
            return
        }
        scope.launch {
            try {
                sendChunked(chatId, content)
            } catch (e: Exception) {
                XLog.e(TAG, "Telegram sendMessageToUser failed", e)
            }
        }
    }

    // ---------- Internal helper methods ----------

    /**
     * Sender allow-list. Only the paired owner (Telegram user id) may send commands.
     * If nobody is paired yet, the first sender becomes the owner and is stored.
     */
    @Synchronized
    private fun isAllowedSender(fromId: Long, chatId: Long): Boolean {
        if (fromId == 0L) {
            XLog.w(TAG, "Telegram message without sender id ignored")
            return false
        }
        val owner = KVUtils.getTelegramOwnerUserId()
        if (owner == 0L) {
            KVUtils.setTelegramOwnerUserId(fromId)
            KVUtils.setTelegramOwnerChatId(chatId)
            XLog.i(TAG, "Telegram owner paired: userId=$fromId")
            return true
        }
        if (owner != fromId) {
            XLog.w(TAG, "Telegram message from unpaired sender ignored: userId=$fromId")
            return false
        }
        if (KVUtils.getTelegramOwnerChatId() != chatId) KVUtils.setTelegramOwnerChatId(chatId)
        return true
    }

    private fun isOwnerChat(chatId: Long): Boolean {
        val ownerChat = KVUtils.getTelegramOwnerChatId()
        return ownerChat != 0L && ownerChat == chatId
    }

    /** Splits long text (Telegram limit 4096) and falls back to plain text if MarkdownV2 fails. */
    private fun sendChunked(chatId: Long, content: String) {
        val chunks = if (content.length <= MAX_CHUNK) listOf(content) else content.chunked(MAX_CHUNK)
        for (chunk in chunks) {
            val ok = if (TelegramMarkdownUtils.containsMarkdown(chunk)) {
                sendText(chatId, TelegramMarkdownUtils.markdownToTelegramV2(chunk), "MarkdownV2")
            } else {
                sendText(chatId, TelegramMarkdownUtils.escapePlain(chunk), "MarkdownV2")
            }
            if (!ok) sendText(chatId, chunk, null)
        }
    }

    private fun sendText(chatId: Long, text: String, parseMode: String?): Boolean {
        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            if (parseMode != null) put("parse_mode", parseMode)
        }
        val body = okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), json.toString())
        val request = okhttp3.Request.Builder()
            .url(apiUrl("sendMessage"))
            .post(body)
            .build()
        val response = httpClient.newCall(request).execute()
        val code = response.code
        if (code !in 200..299) {
            val respBody = response.body?.string()
            XLog.w(TAG, "Telegram sendMessage failed: code=$code, parseMode=$parseMode, resp=${respBody?.take(200)}")
        } else {
            XLog.i(TAG, "Telegram reply succeeded: code=$code, parseMode=$parseMode")
        }
        response.close()
        return code in 200..299
    }

    companion object {
        private const val TAG = "TelegramHandler"
        private const val MAX_CHUNK = 3500
        private const val MAX_MESSAGE_AGE_SEC = 120L
    }
}
