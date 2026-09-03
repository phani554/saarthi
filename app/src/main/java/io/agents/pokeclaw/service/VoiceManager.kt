// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.widget.Toast
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

sealed class VoiceEngineState {
    object Idle : VoiceEngineState()
    object Recording : VoiceEngineState()
    object Transcribing : VoiceEngineState()
    object GeneratingResponse : VoiceEngineState()
    object Speaking : VoiceEngineState()
    data class Error(val message: String) : VoiceEngineState()
}

/**
 * Voice Audio Engine: Handles voice input (Sarvam STT / Gemini STT / Native)
 * and voice output (Sarvam TTS / Native TTS).
 */
object VoiceManager : TextToSpeech.OnInitListener {

    private const val TAG = "VoiceManager"
    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    var isVoiceOutputEnabled = true
    var onPlaybackFinished: (() -> Unit)? = null

    @Volatile
    var lastDetectedLanguageCode: String = "hi-IN"

    private var mediaPlayer: MediaPlayer? = null
    private val speechExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _engineState = MutableStateFlow<VoiceEngineState>(VoiceEngineState.Idle)
    val engineState: StateFlow<VoiceEngineState> = _engineState.asStateFlow()

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        XLog.d(TAG, "OkHttp: $message")
    }.apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    fun setEngineState(state: VoiceEngineState) {
        _engineState.value = state
    }

    fun init(context: Context) {
        appContext = context.applicationContext
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
            XLog.i(TAG, "Native TTS initialized successfully, result=$result")
        } else {
            XLog.e(TAG, "Native TTS initialization failed with status=$status")
        }
    }

    @Volatile
    private var isPlayingAudio = false

    fun isPlayingAudio(): Boolean = isPlayingAudio

    /**
     * Starts direct in-app voice capture via microphone.
     * Audio is captured directly into memory without launching system speech dialog popups.
     */
    fun startInAppVoiceCapture(context: Context, onResult: (String?) -> Unit) {
        if (isPlayingAudio) {
            XLog.w(TAG, "Voice capture blocked: audio playback (TTS) is currently active")
            return
        }
        if (VoiceRecorder.isRecording()) {
            VoiceRecorder.stopRecording()
            _engineState.value = VoiceEngineState.Idle
        } else {
            _engineState.value = VoiceEngineState.Recording
            VoiceRecorder.startRecording(context, maxDurationMs = 7000L) { bytes ->
                if (bytes.isNotEmpty()) {
                    _engineState.value = VoiceEngineState.Transcribing
                    processVoiceInputAutoKey(bytes, "audio/wav") { transcript ->
                        _engineState.value = VoiceEngineState.Idle
                        onResult(transcript)
                    }
                } else {
                    _engineState.value = VoiceEngineState.Idle
                    onResult(null)
                }
            }
        }
    }

    enum class TaskTtsEngineLock { UNLOCKED, SARVAM_CLOUD, NATIVE_LOCAL }

    @Volatile
    private var currentTaskEngineLock: TaskTtsEngineLock = TaskTtsEngineLock.UNLOCKED

    fun lockTtsEngineForTask(): TaskTtsEngineLock {
        val sarvamKey = KVUtils.getSarvamApiKey().trim()
        val latency = pingTtsLatencyMs()
        currentTaskEngineLock = if (sarvamKey.isNotEmpty() && latency < 600L) {
            XLog.i(TAG, "Locked SARVAM_CLOUD TTS engine for task lifetime (ping=$latency ms)")
            TaskTtsEngineLock.SARVAM_CLOUD
        } else {
            XLog.i(TAG, "Locked NATIVE_LOCAL TTS engine for task lifetime (ping=$latency ms)")
            TaskTtsEngineLock.NATIVE_LOCAL
        }
        return currentTaskEngineLock
    }

    fun unlockTtsEngineTask() {
        XLog.i(TAG, "Unlocked task TTS engine lock")
        currentTaskEngineLock = TaskTtsEngineLock.UNLOCKED
    }

    fun isRecordingInApp(): Boolean = VoiceRecorder.isRecording()

    fun stopInAppVoiceCapture() {
        VoiceRecorder.stopRecording()
        _engineState.value = VoiceEngineState.Idle
    }

    /**
     * Speaks text using the locked TTS engine or latency-evaluated engine.
     */
    fun speak(text: String, flush: Boolean = false) {
        if (!isVoiceOutputEnabled || text.isBlank()) return
        val cleanText = cleanTextForSpeech(text).take(400)
        if (cleanText.isBlank()) return

        stopAudioPlayback()

        val lock = if (currentTaskEngineLock != TaskTtsEngineLock.UNLOCKED) {
            currentTaskEngineLock
        } else {
            val sarvamKey = KVUtils.getSarvamApiKey().trim()
            if (sarvamKey.isNotEmpty() && pingTtsLatencyMs() < 600L) TaskTtsEngineLock.SARVAM_CLOUD else TaskTtsEngineLock.NATIVE_LOCAL
        }

        if (lock == TaskTtsEngineLock.SARVAM_CLOUD) {
            val sarvamKey = KVUtils.getSarvamApiKey().trim()
            val spokenSarvam = speakSarvamTts(cleanText, sarvamKey)
            if (spokenSarvam) return
        }

        if (isTtsInitialized) {
            speakNativeInternal(cleanText, flush)
        }
    }

    private fun speakNativeInternal(cleanText: String, flush: Boolean) {
        isPlayingAudio = true
        _engineState.value = VoiceEngineState.Speaking
        val locale = when (lastDetectedLanguageCode) {
            "en-IN" -> Locale.ENGLISH
            "hi-IN" -> Locale("hi", "IN")
            "te-IN" -> Locale("te", "IN")
            "ta-IN" -> Locale("ta", "IN")
            "bn-IN" -> Locale("bn", "IN")
            else -> Locale.getDefault()
        }
        tts?.setLanguage(locale)
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(cleanText, mode, null, "saarthi_tts_" + System.currentTimeMillis())
        XLog.i(TAG, "Speaking via Native TTS (lang=$lastDetectedLanguageCode, flush=$flush): '$cleanText'")
        mainHandler.postDelayed({
            isPlayingAudio = false
            if (_engineState.value == VoiceEngineState.Speaking) {
                _engineState.value = VoiceEngineState.Idle
            }
            onPlaybackFinished?.invoke()
        }, 2500L)
    }

    /**
     * Speaks intermediate execution calls and tool progress immediately via Native Android TTS.
     * Zero network latency, fast, and saves cloud audio synthesis time!
     */
    fun speakNative(text: String, flush: Boolean = false) {
        if (!isVoiceOutputEnabled || text.isBlank()) return
        val cleanText = cleanTextForSpeech(text).take(250)
        if (cleanText.isBlank()) return

        speechExecutor.submit {
            if (VoiceRecorder.isRecording()) {
                VoiceRecorder.stopRecording()
            }
            if (isTtsInitialized) {
                isPlayingAudio = true
                _engineState.value = VoiceEngineState.Speaking
                val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                tts?.speak(cleanText, mode, null, "saarthi_native_" + System.currentTimeMillis())
                XLog.i(TAG, "Speaking via Native TTS: '$cleanText'")
                mainHandler.postDelayed({
                    isPlayingAudio = false
                    if (_engineState.value == VoiceEngineState.Speaking) {
                        _engineState.value = VoiceEngineState.Idle
                    }
                }, 1800L)
            }
        }
    }

    /**
     * Speaks intermediate responses/tool execution progress asynchronously on a separate thread.
     */
    fun speakAsync(text: String, flush: Boolean = false) {
        if (!isVoiceOutputEnabled || text.isBlank()) return
        speechExecutor.submit {
            speak(text, flush)
        }
    }

    private fun speakSarvamTts(text: String, apiKey: String): Boolean {
        return try {
            isPlayingAudio = true
            _engineState.value = VoiceEngineState.Speaking
            val jsonBody = JSONObject().apply {
                put("inputs", JSONArray().apply { put(text) })
                val targetLang = if (lastDetectedLanguageCode.isNotBlank()) lastDetectedLanguageCode else KVUtils.getSarvamLanguageCode().ifBlank { "hi-IN" }
                put("target_language_code", targetLang)
                put("speaker", KVUtils.getSarvamSpeaker().ifBlank { "shubh" })
                put("model", "bulbul:v3")
                put("pace", 1.0)
                put("speech_sample_rate", 22050)
                put("enable_preprocessing", true)
            }

            val request = Request.Builder()
                .url("https://api.sarvam.ai/text-to-speech")
                .addHeader("api-subscription-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseStr = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val root = JSONObject(responseStr)
                val audios = root.optJSONArray("audios")
                val base64Audio = audios?.optString(0).orEmpty()
                if (base64Audio.isNotEmpty()) {
                    playBase64Audio(base64Audio)
                    XLog.i(TAG, "Spoke via Sarvam TTS successfully: '$text'")
                    return true
                }
            }
            isPlayingAudio = false
            XLog.w(TAG, "Sarvam TTS request failed (${response.code}), falling back to native TTS: $responseStr")
            _engineState.value = VoiceEngineState.Error("Sarvam TTS HTTP ${response.code}")
            false
        } catch (e: Exception) {
            isPlayingAudio = false
            XLog.e(TAG, "Error calling Sarvam TTS, falling back to native TTS", e)
            _engineState.value = VoiceEngineState.Error("Sarvam TTS exception: ${e.message}")
            false
        }
    }

    private fun playBase64Audio(base64Audio: String) {
        val ctx = appContext ?: return
        try {
            if (VoiceRecorder.isRecording()) {
                VoiceRecorder.stopRecording()
            }
            stopAudioPlayback()
            isPlayingAudio = true
            _engineState.value = VoiceEngineState.Speaking

            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val tempFile = File.createTempFile("sarvam_tts_", ".wav", ctx.cacheDir)
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    tempFile.delete()
                    isPlayingAudio = false
                    _engineState.value = VoiceEngineState.Idle
                    mainHandler.postDelayed({
                        onPlaybackFinished?.invoke()
                    }, 400L)
                }
                setOnErrorListener { _, _, _ ->
                    tempFile.delete()
                    isPlayingAudio = false
                    _engineState.value = VoiceEngineState.Idle
                    false
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Error playing Sarvam TTS base64 audio", e)
            isPlayingAudio = false
            _engineState.value = VoiceEngineState.Idle
        }
    }

    private fun stopAudioPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
        isPlayingAudio = false
        _engineState.value = VoiceEngineState.Idle
    }

    fun stop() {
        stopAudioPlayback()
        tts?.stop()
        _engineState.value = VoiceEngineState.Idle
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

    fun pingTtsLatencyMs(): Long {
        val start = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url("https://api.sarvam.ai/text-to-speech")
                .head()
                .build()
            val response = httpClient.newCall(request).execute()
            val elapsed = System.currentTimeMillis() - start
            response.close()
            elapsed
        } catch (_: Exception) {
            9999L
        }
    }

    fun resolveGeminiApiKey(): String {
        return KVUtils.getLlmApiKey()
    }

    /**
     * Process voice input: tries Sarvam STT first if API key is configured,
     * otherwise falls back to Gemini STT (`gemini-3.8-flash`).
     */
    fun processVoiceInputAutoKey(
        audioData: ByteArray,
        mimeType: String = "audio/wav",
        onResult: (String?) -> Unit
    ) {
        val sarvamKey = KVUtils.getSarvamApiKey().trim()
        if (sarvamKey.isNotEmpty()) {
            Thread {
                val sarvamTranscript = processSarvamVoiceInput(audioData, mimeType, sarvamKey)
                if (!sarvamTranscript.isNullOrBlank()) {
                    onResult(sarvamTranscript)
                    return@Thread
                }
                XLog.w(TAG, "Sarvam STT returned empty result, falling back to Gemini STT")
                val geminiKey = resolveGeminiApiKey()
                processGeminiVoiceInput(audioData, mimeType, geminiKey, onResult)
            }.start()
        } else {
            val geminiKey = resolveGeminiApiKey()
            processGeminiVoiceInput(audioData, mimeType, geminiKey, onResult)
        }
    }

    /**
     * Calls Sarvam AI Speech-to-Text API endpoint.
     */
    fun processSarvamVoiceInput(
        audioData: ByteArray,
        mimeType: String = "audio/wav",
        apiKey: String
    ): String? {
        return try {
            val langCode = KVUtils.getSarvamLanguageCode().ifBlank { "unknown" }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio_input.wav", audioData.toRequestBody(mimeType.toMediaType()))
                .addFormDataPart("model", "saaras:v3")
                .addFormDataPart("mode", "codemix")
                .addFormDataPart("language_code", langCode)
                .build()

            val request = Request.Builder()
                .url("https://api.sarvam.ai/speech-to-text")
                .addHeader("api-subscription-key", apiKey)
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseStr = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val root = JSONObject(responseStr)
                val transcript = root.optString("transcript").trim()
                val detectedLang = root.optString("language_code").trim()
                if (detectedLang.isNotEmpty()) {
                    lastDetectedLanguageCode = detectedLang
                    XLog.i(TAG, "Sarvam STT auto-detected language: '$detectedLang'")
                }
                XLog.i(TAG, "Sarvam STT transcription successful: '$transcript'")
                transcript.ifBlank { null }
            } else {
                XLog.e(TAG, "Sarvam STT API call failed (${response.code}): $responseStr")
                null
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Error in Sarvam STT API processing", e)
            null
        }
    }

    /**
     * Sends voice audio recording to Gemini API for speech transcription (`gemini-3.8-flash`).
     */
    fun processGeminiVoiceInput(
        audioData: ByteArray,
        mimeType: String = "audio/wav",
        apiKey: String,
        onResult: (String?) -> Unit
    ) {
        val resolvedKey = apiKey.ifBlank { resolveGeminiApiKey() }
        if (resolvedKey.isBlank()) {
            XLog.w(TAG, "Gemini API key is blank for voice processing")
            appContext?.let { ctx ->
                mainHandler.post {
                    Toast.makeText(ctx, "Gemini API Key is missing. Please set it in Settings.", Toast.LENGTH_LONG).show()
                }
            }
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
                                    put("text", "Transcribe the user's spoken voice recording accurately into clear text (preserving Hinglish/Hindi/English as spoken). Return ONLY the transcribed text.")
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

                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=$resolvedKey"
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
        stopAudioPlayback()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsInitialized = false
        _engineState.value = VoiceEngineState.Idle
    }
}
