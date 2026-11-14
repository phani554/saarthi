// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import io.agents.pokeclaw.agent.AgentTaskState
import io.agents.pokeclaw.agent.TaskExecutionState
import io.agents.pokeclaw.lang.ReplyLanguage
import io.agents.pokeclaw.lang.ReplyLanguageState
import io.agents.pokeclaw.lang.SpeechNumbers
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
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
 * Thread-safe Voice Audio Engine: Handles voice input and output.
 * Synchronized to guarantee strict single-audio-playback mutual exclusion
 * and zero conflicting concurrent Cloud TTS calls during task execution.
 */
object VoiceManager : TextToSpeech.OnInitListener {

    private const val TAG = "VoiceManager"
    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    @Volatile
    private var isTtsInitialized = false
    var isVoiceOutputEnabled = true
    var onPlaybackFinished: (() -> Unit)? = null

    /**
     * The current reply language code (en-IN / hi-IN / mr-IN / te-IN). Kept for older callers; the
     * source of truth is [ReplyLanguageState]. Setting it only accepts those four codes.
     */
    var lastDetectedLanguageCode: String
        get() = ReplyLanguageState.replyLanguage().code
        set(value) { ReplyLanguage.fromCode(value)?.let { ReplyLanguageState.current = it } }

    private var mediaPlayer: MediaPlayer? = null
    /** Temp WAV backing the current Sarvam clip; deleted on completion, error, failure or stop. */
    private var currentTempFile: File? = null
    private val speechExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Exception) {
            Handler(Looper.myLooper() ?: Looper.getMainLooper())
        }
    }

    private val _engineState = MutableStateFlow<VoiceEngineState>(VoiceEngineState.Idle)
    val engineState: StateFlow<VoiceEngineState> = _engineState.asStateFlow()

    private val speakLock = Any()

    // ---- Audio focus (playback only) ----
    /** Spoken replies are an assistant talking: other audio ducks under it. Used by TTS and MediaPlayer. */
    private val speechAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
    private val focusLock = Any()
    private var focusRequest: AudioFocusRequest? = null
    @Volatile
    private var hasAudioFocus = false
    /** Own handler: stopAudioPlayback() clears mainHandler's queue, which must not drop focus callbacks. */
    private val focusHandler by lazy { Handler(Looper.getMainLooper()) }
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // A call or another app took the audio for good / for a while: stop talking.
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                XLog.i(TAG, "Audio focus lost ($change): stopping speech")
                hasAudioFocus = false
                stopAudioPlayback()
            }
            // LOSS_TRANSIENT_CAN_DUCK: the system lowers our volume itself (API 26+). Nothing to do.
            else -> {}
        }
    }

    private fun audioManager(): AudioManager? =
        appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** Asks for transient, duck-others focus before a reply plays. Speech still plays if refused. */
    private fun requestSpeechFocus() {
        synchronized(focusLock) {
            if (hasAudioFocus) return
            val am = audioManager() ?: return
            try {
                val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(speechAudioAttributes)
                    .setOnAudioFocusChangeListener(focusChangeListener, focusHandler)
                    .build()
                    .also { focusRequest = it }
                hasAudioFocus = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } catch (e: Exception) {
                XLog.w(TAG, "Audio focus request failed", e)
            }
        }
    }

    /** Gives focus back (music returns to full volume) when a reply ends, is stopped or fails. */
    private fun abandonSpeechFocus() {
        synchronized(focusLock) {
            if (!hasAudioFocus) return
            hasAudioFocus = false
            val req = focusRequest ?: return
            try {
                audioManager()?.abandonAudioFocusRequest(req)
            } catch (e: Exception) {
                XLog.w(TAG, "Audio focus abandon failed", e)
            }
        }
    }

    @Volatile
    private var isPlayingAudio = false

    /**
     * Bumped on every new utterance and every stop. A Sarvam clip or a posted native
     * utterance only starts if its generation is still current, so a late Sarvam
     * response can never play on top of newer native speech (and vice versa).
     */
    @Volatile
    private var speechGeneration = 0L

    @Volatile
    private var isSarvamClipPlaying = false

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        XLog.d(TAG, "OkHttp: $message")
    }.apply {
        // BASIC only: BODY logged the Sarvam key header, base64 audio and transcripts to logcat.
        level = HttpLoggingInterceptor.Level.BASIC
        redactHeader("api-subscription-key")
        redactHeader("x-goog-api-key")
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (tts == null) {
            tts = TextToSpeech(appContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            tts?.language = Locale("hi", "IN")
            try { tts?.setAudioAttributes(speechAudioAttributes) } catch (_: Exception) {}
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (!isSarvamClipPlaying) {
                        isPlayingAudio = false
                        _engineState.value = VoiceEngineState.Idle
                        // Re-arm the voice loop after native speech too (it was Sarvam-only).
                        val doneGeneration = speechGeneration
                        mainHandler.postDelayed({
                            if (doneGeneration == speechGeneration && !isSarvamClipPlaying &&
                                tts?.isSpeaking != true
                            ) {
                                abandonSpeechFocus()
                                onPlaybackFinished?.invoke()
                            }
                        }, 300L)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { onDone(utteranceId) }
            })
            XLog.i(TAG, "Native TTS initialized successfully")
        } else {
            XLog.e(TAG, "Native TTS initialization failed (status=$status)")
        }
    }

    fun isPlayingAudio(): Boolean = isPlayingAudio

    fun isRecordingInApp(): Boolean = VoiceRecorder.isRecording()

    fun armVoiceLoop() {
        XLog.i(TAG, "Voice auto-loop ARMED behind WaitingForUser state")
    }

    fun disarmVoiceLoop() {
        XLog.i(TAG, "Voice auto-loop DISARMED")
        stopInAppVoiceCapture()
    }

    fun startInAppVoiceCapture(context: Context, onResult: (String?) -> Unit) {
        synchronized(speakLock) {
            if (isPlayingAudio) {
                XLog.i(TAG, "Stopping audio playback for user voice capture")
                stopAudioPlayback()
            }
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

    fun stopInAppVoiceCapture() {
        VoiceRecorder.stopRecording()
        _engineState.value = VoiceEngineState.Idle
    }

    /**
     * Synchronized speech dispatcher.
     * STRICT MANDATE: Never invoke Sarvam Cloud TTS during active task execution!
     */
    fun speak(text: String, flush: Boolean = false) {
        if (!isVoiceOutputEnabled || text.isBlank()) return
        val (cleanText, lang) = prepareForSpeech(text) ?: return

        var myGeneration = 0L
        var sarvamKey = ""
        synchronized(speakLock) {
            stopAudioPlayback()
            myGeneration = speechGeneration

            val currentState = TaskExecutionState.instance.currentState.value
            val isExecutingState = currentState is AgentTaskState.Executing ||
                    currentState is AgentTaskState.Recovering

            if (isExecutingState) {
                XLog.i(TAG, "Task is EXECUTING -> FORCE Native Local TTS (0ms cloud latency)")
                if (isTtsInitialized) {
                    speakNativeInternal(cleanText, flush, lang)
                }
                return
            }

            sarvamKey = KVUtils.getSarvamApiKey().trim()
            if (sarvamKey.isEmpty()) {
                if (isTtsInitialized) {
                    speakNativeInternal(cleanText, flush, lang)
                }
                return
            }
            isPlayingAudio = true
            _engineState.value = VoiceEngineState.Speaking
        }

        // The Sarvam HTTP call (up to ~25 s) runs OUTSIDE speakLock, so stop(), voice capture
        // and SOS speakNative(flush=true) never wait on the network. The generation check below
        // drops the clip if anything newer started or a stop happened meanwhile.
        val base64Audio = fetchSarvamTtsAudio(cleanText, sarvamKey, lang)

        synchronized(speakLock) {
            if (myGeneration != speechGeneration) {
                XLog.i(TAG, "Sarvam audio arrived after a newer utterance or a stop; discarding")
                return
            }
            if (base64Audio != null) {
                playBase64Audio(base64Audio, myGeneration)
                return
            }
            isPlayingAudio = false
            if (isTtsInitialized) {
                speakNativeInternal(cleanText, flush, lang)
            } else {
                _engineState.value = VoiceEngineState.Error("Sarvam TTS failed")
            }
        }
    }

    fun speakNative(text: String, flush: Boolean = false) {
        if (!isVoiceOutputEnabled || text.isBlank()) return
        val (cleanText, lang) = prepareForSpeech(text) ?: return
        synchronized(speakLock) {
            if (isSarvamClipPlaying && !flush) {
                // Low-priority progress narration: never talk over (or cut) a Sarvam reply.
                XLog.d(TAG, "Skipping native narration while Sarvam clip plays (${cleanText.length} chars)")
                return
            }
            stopAudioPlayback()
            if (isTtsInitialized) {
                speakNativeInternal(cleanText, flush, lang)
            }
        }
    }

    /**
     * Cleans [raw] for speech, picks the voice language from the text itself (Telugu script -> te,
     * Devanagari -> current hi/mr, Latin -> en; LanguageDetector.ttsLanguageFor) and rewrites numbers
     * for that language (phone digits, rupees, commas; SpeechNumbers). Null when nothing is left.
     */
    private fun prepareForSpeech(raw: String): Pair<String, ReplyLanguage>? {
        val clean = cleanTextForSpeech(raw)
        if (clean.isBlank()) return null
        val lang = try { ReplyLanguageState.ttsLanguageFor(clean) } catch (_: Throwable) { ReplyLanguage.HI }
        val spoken = try { SpeechNumbers.normalize(clean, lang) } catch (_: Throwable) { clean }
        return Pair(spoken.take(400), lang)
    }

    private fun localeFor(lang: ReplyLanguage): Locale = when (lang) {
        ReplyLanguage.EN -> Locale("en", "IN")
        ReplyLanguage.HI -> Locale("hi", "IN")
        ReplyLanguage.MR -> Locale("mr", "IN")
        ReplyLanguage.TE -> Locale("te", "IN")
    }

    private fun speakNativeInternal(cleanText: String, flush: Boolean, lang: ReplyLanguage) {
        isPlayingAudio = true
        _engineState.value = VoiceEngineState.Speaking
        val locale = localeFor(lang)

        val myGeneration = speechGeneration
        mainHandler.post {
            if (myGeneration != speechGeneration || isSarvamClipPlaying) {
                XLog.d(TAG, "Dropping stale native utterance (${cleanText.length} chars)")
                return@post
            }
            try {
                val result = tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (result < TextToSpeech.LANG_AVAILABLE) {
                    // Phone has no voice for this language: Marathi falls back to Hindi (same script),
                    // English to the generic English voice.
                    val fallback = when (lang) {
                        ReplyLanguage.MR -> Locale("hi", "IN")
                        ReplyLanguage.EN -> Locale.ENGLISH
                        else -> null
                    }
                    if (fallback != null) tts?.setLanguage(fallback)
                }
            } catch (_: Exception) {}
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            requestSpeechFocus()
            tts?.speak(cleanText, mode, null, "saarthi_native_" + System.currentTimeMillis())
            XLog.i(TAG, "Speaking via Native TTS (${cleanText.length} chars)")
        }
    }

    fun speakAsync(text: String, flush: Boolean = false) {
        if (!isVoiceOutputEnabled || text.isBlank()) return
        speechExecutor.submit {
            speak(text, flush)
        }
    }

    /**
     * Blocking Sarvam TTS request. Returns the base64 audio, or null on any failure.
     * Must be called without holding speakLock.
     */
    private fun fetchSarvamTtsAudio(text: String, apiKey: String, lang: ReplyLanguage): String? {
        return try {
            // Current Sarvam REST contract (docs.sarvam.ai, checked 2026-11-14): "text" + "language_code".
            // bulbul:v3 preprocesses numbers itself and does not take enable_preprocessing (v2 only).
            // The language comes from the reply text, so an English reply never gets the hi-IN voice.
            val jsonBody = JSONObject().apply {
                put("text", text)
                put("language_code", lang.code)
                put("speaker", KVUtils.getSarvamSpeaker().ifBlank { "shubh" })
                put("model", "bulbul:v3")
                put("pace", 1.0)
                put("speech_sample_rate", 22050)
            }

            val request = Request.Builder()
                .url("https://api.sarvam.ai/text-to-speech")
                .addHeader("api-subscription-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val base64Audio = JSONObject(responseStr).optJSONArray("audios")?.optString(0).orEmpty()
                    if (base64Audio.isNotEmpty()) {
                        XLog.i(TAG, "Sarvam TTS audio received (${text.length} chars of text)")
                        return base64Audio
                    }
                }
                XLog.w(TAG, "Sarvam TTS request failed (${response.code}, ${responseStr.length} bytes, lang=${lang.code}), falling back to native TTS")
                null
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Error calling Sarvam TTS, falling back to native TTS", e)
            null
        }
    }

    /** Called with speakLock held. */
    private fun playBase64Audio(base64Audio: String, generation: Long) {
        val ctx = appContext ?: run {
            isPlayingAudio = false
            _engineState.value = VoiceEngineState.Idle
            return
        }
        var tempFile: File? = null
        try {
            if (VoiceRecorder.isRecording()) {
                VoiceRecorder.stopRecording()
            }
            isPlayingAudio = true
            _engineState.value = VoiceEngineState.Speaking

            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val file = File.createTempFile("sarvam_tts_", ".wav", ctx.cacheDir)
            tempFile = file
            FileOutputStream(file).use { it.write(audioBytes) }

            if (generation != speechGeneration) {
                file.delete()
                isPlayingAudio = false
                return
            }
            // Make sure the phone's own TTS is silent before the Sarvam clip starts.
            try { tts?.stop() } catch (_: Exception) {}
            isSarvamClipPlaying = true
            currentTempFile = file
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(speechAudioAttributes)
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    file.delete()
                    isSarvamClipPlaying = false
                    isPlayingAudio = false
                    _engineState.value = VoiceEngineState.Idle
                    abandonSpeechFocus()
                    mainHandler.postDelayed({
                        onPlaybackFinished?.invoke()
                    }, 300L)
                }
                setOnErrorListener { _, _, _ ->
                    file.delete()
                    isSarvamClipPlaying = false
                    isPlayingAudio = false
                    _engineState.value = VoiceEngineState.Idle
                    abandonSpeechFocus()
                    false
                }
                requestSpeechFocus()
                start()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Error playing Sarvam TTS base64 audio", e)
            try { tempFile?.delete() } catch (_: Exception) {}
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
            currentTempFile = null
            isSarvamClipPlaying = false
            isPlayingAudio = false
            _engineState.value = VoiceEngineState.Idle
            abandonSpeechFocus()
        }
    }

    fun stopAudioPlayback() {
      synchronized(speakLock) {
        speechGeneration++
        isSarvamClipPlaying = false
        try {
            mainHandler.removeCallbacksAndMessages(null)
        } catch (_: Exception) {}
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        try { currentTempFile?.delete() } catch (_: Exception) {}
        currentTempFile = null
        try {
            tts?.stop()
        } catch (_: Exception) {}
        isPlayingAudio = false
        _engineState.value = VoiceEngineState.Idle
        abandonSpeechFocus()
      }
    }

    fun cleanupAllResidualState() {
        XLog.i(TAG, "Purging all pending speech tasks, handler callbacks, and stopping TTS")
        try {
            mainHandler.removeCallbacksAndMessages(null)
        } catch (_: Exception) {}
        try {
            (speechExecutor as? ThreadPoolExecutor)?.queue?.clear()
        } catch (_: Exception) {}

        stopAudioPlayback()
        isPlayingAudio = false
        _engineState.value = VoiceEngineState.Idle
    }

    fun stop() {
        cleanupAllResidualState()
    }

    private fun cleanTextForSpeech(raw: String): String {
        return raw.replace(Regex("""```[\s\S]*?```"""), "")
            .replace(Regex("""\{[\s\S]*?\}"""), "")
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""[*#_~`\[\]()<=>|/\\+@$%^&-]"""), " ")
            .replace(Regex("""[\u2600-\u26FF\u2700-\u27BF\uD83C-\uDBFF\uDC00-\uDFFF]"""), "")
            .replace(Regex("""[✓✗🚨]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun processVoiceInputAutoKey(audioBytes: ByteArray, mimeType: String, onTranscript: (String) -> Unit) {
        val sarvamKey = KVUtils.getSarvamApiKey().trim()
        val geminiKey = KVUtils.getVoiceGeminiKey().trim()

        if (sarvamKey.isNotEmpty()) {
            processSarvamVoiceInput(audioBytes, mimeType, sarvamKey, onTranscript)
        } else if (geminiKey.isNotEmpty()) {
            processGeminiVoiceInput(audioBytes, mimeType, geminiKey, onTranscript)
        } else {
            XLog.e(TAG, "No API key configured for STT!")
            _engineState.value = VoiceEngineState.Error("No API key set for voice transcription")
            onTranscript("")
        }
    }

    private fun processSarvamVoiceInput(audioBytes: ByteArray, mimeType: String, apiKey: String, onTranscript: (String) -> Unit) {
        try {
            val audioRequestBody = audioBytes.toRequestBody("audio/wav".toMediaType())
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "recording.wav", audioRequestBody)
                .addFormDataPart("model", "saaras:v3")
                .addFormDataPart("mode", "codemix")
                // "unknown" = Sarvam auto-detects (and returns language_probability); a fixed
                // Settings › Reply language sends that language instead.
                .addFormDataPart("language_code", ReplyLanguageState.sttLanguageCode())
                .build()

            val request = Request.Builder()
                .url("https://api.sarvam.ai/speech-to-text")
                .addHeader("api-subscription-key", apiKey)
                .post(multipartBody)
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    XLog.e(TAG, "Sarvam STT failed: ${e.message}")
                    onTranscript("")
                }

                override fun onResponse(call: Call, response: Response) {
                    // A throw here (e.g. HTML from a captive portal) would kill the process on
                    // OkHttp's dispatcher thread and leave the state stuck at Transcribing.
                    var transcript = ""
                    try {
                        response.use { resp ->
                            val respStr = resp.body?.string().orEmpty()
                            if (resp.isSuccessful && respStr.isNotEmpty()) {
                                val jsonObj = JSONObject(respStr)
                                transcript = jsonObj.optString("transcript", "").trim()
                                val detectedLang = if (jsonObj.isNull("language_code")) "" else jsonObj.optString("language_code", "").trim()
                                val probability = if (jsonObj.has("language_probability") && !jsonObj.isNull("language_probability"))
                                    jsonObj.optDouble("language_probability").takeIf { !it.isNaN() } else null
                                // Only en/hi/mr/te switch the reply language; short or unsure turns keep it.
                                ReplyLanguageState.onSpeech(detectedLang, probability, transcript)
                                XLog.i(TAG, "Sarvam STT transcription successful (${transcript.length} chars)")
                            } else {
                                XLog.e(TAG, "Sarvam STT API error HTTP ${resp.code} (${respStr.length} bytes)")
                            }
                        }
                    } catch (e: Exception) {
                        XLog.e(TAG, "Sarvam STT response parse failed: ${e.message}")
                        transcript = ""
                    }
                    onTranscript(transcript)
                }
            })
        } catch (e: Exception) {
            XLog.e(TAG, "Sarvam STT exception: ${e.message}")
            onTranscript("")
        }
    }

    private fun processGeminiVoiceInput(audioBytes: ByteArray, mimeType: String, apiKey: String, onTranscript: (String) -> Unit) {
        val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", mimeType)
                                put("data", base64Audio)
                            })
                        })
                        put(JSONObject().apply {
                            put("text", "Transcribe this audio accurately. Output ONLY the raw transcript.")
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
            .addHeader("x-goog-api-key", apiKey) // header, not URL query (query got logged)
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                XLog.e(TAG, "Gemini STT failed: ${e.message}")
                onTranscript("")
            }

            override fun onResponse(call: Call, response: Response) {
                var transcript = ""
                try {
                    response.use { resp ->
                        val respStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful && respStr.isNotEmpty()) {
                            val jsonObj = JSONObject(respStr)
                            val candidates = jsonObj.optJSONArray("candidates")
                            val firstCandidate = candidates?.optJSONObject(0)
                            val content = firstCandidate?.optJSONObject("content")
                            val parts = content?.optJSONArray("parts")
                            transcript = parts?.optJSONObject(0)?.optString("text", "")?.trim().orEmpty()
                            if (transcript.isNotBlank()) ReplyLanguageState.onText(transcript)
                            XLog.i(TAG, "Gemini STT transcription successful (${transcript.length} chars)")
                        } else {
                            XLog.e(TAG, "Gemini STT API error HTTP ${resp.code} (${respStr.length} bytes)")
                        }
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Gemini STT response parse failed: ${e.message}")
                    transcript = ""
                }
                onTranscript(transcript)
            }
        })
    }
}
