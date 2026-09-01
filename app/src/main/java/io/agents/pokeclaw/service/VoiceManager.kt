// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Base64
import io.agents.pokeclaw.utils.XLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Handles voice input via Gemini API multimodal endpoint and text-to-speech output.
 */
object VoiceManager : TextToSpeech.OnInitListener {

    private const val TAG = "VoiceManager"
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    var isVoiceOutputEnabled = true

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            tts?.setSpeechRate(1.05f)
            tts?.setPitch(1.0f)
            isTtsInitialized = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            XLog.i(TAG, "TTS initialized successfully, language result=$result")
        } else {
            XLog.e(TAG, "TTS initialization failed with status=$status")
        }
    }

    private val speechExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun speak(text: String, flush: Boolean = false) {
        if (!isVoiceOutputEnabled || !isTtsInitialized || text.isBlank()) return
        val cleanText = cleanTextForSpeech(text).take(400)
        if (cleanText.isBlank()) return
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(cleanText, mode, null, "saarthi_tts_" + System.currentTimeMillis())
        XLog.i(TAG, "Speaking response (flush=$flush): '$cleanText'")
    }

    private fun cleanTextForSpeech(raw: String): String {
        return raw.replace(Regex("""```[\s\S]*?```"""), "") // Remove code blocks
            .replace(Regex("""\{[\s\S]*?\}"""), "")       // Remove JSON
            .replace(Regex("""https?://\S+"""), "")        // Remove URLs
            .replace(Regex("""[*#_~`\[\]]"""), "")        // Remove markdown symbols
            .replace(Regex("""[\u2600-\u26FF\u2700-\u27BF]"""), "") // Remove emojis
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Speaks intermediate responses/tool execution progress asynchronously on a separate thread.
     * Queues speech items (QUEUE_ADD) so LLM reasoning and tool action names do not interrupt each other.
     */
    fun speakAsync(text: String, flush: Boolean = false) {
        if (!isVoiceOutputEnabled || text.isBlank()) return
        speechExecutor.submit {
            speak(text, flush)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun resolveGeminiApiKey(): String {
        return io.agents.pokeclaw.utils.KVUtils.getLlmApiKey()
    }

    fun processVoiceInputAutoKey(
        audioData: ByteArray,
        mimeType: String = "audio/mp3",
        onResult: (String?) -> Unit
    ) {
        val key = resolveGeminiApiKey()
        processGeminiVoiceInput(audioData, mimeType, key, onResult)
    }

    /**
     * Sends voice audio recording to Gemini API for speech transcription & intent parsing.
     */
    fun processGeminiVoiceInput(
        audioData: ByteArray,
        mimeType: String = "audio/mp3",
        apiKey: String,
        onResult: (String?) -> Unit
    ) {
        val resolvedKey = apiKey.ifBlank { resolveGeminiApiKey() }
        if (resolvedKey.isBlank()) {
            XLog.w(TAG, "Gemini API key is blank for voice processing")
            onResult(null)
            return
        }

        Thread {
            try {
                val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
                val jsonBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", "Transcribe the user's spoken voice command into clear English/Hindi text. Return only the transcribed command.")
                                })
                                put(JSONObject().apply {
                                    put("inlineData", JSONObject().apply {
                                        put("mimeType", mimeType)
                                        put("data", base64Audio)
                                    })
                                })
                            })
                        })
                    })
                }

                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-live-preview:generateContent?key=$resolvedKey"
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseStr = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val root = JSONObject(responseStr)
                    val candidates = root.optJSONArray("candidates")
                    val first = candidates?.optJSONObject(0)
                    val content = first?.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    val transcribed = parts?.optJSONObject(0)?.optString("text")?.trim()
                    XLog.i(TAG, "Gemini voice transcription: '$transcribed'")
                    onResult(transcribed)
                } else {
                    XLog.e(TAG, "Gemini voice API call failed: ${response.code} $responseStr")
                    onResult(null)
                }
            } catch (e: Exception) {
                XLog.e(TAG, "Error processing Gemini voice input", e)
                onResult(null)
            }
        }.start()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsInitialized = false
    }
}
