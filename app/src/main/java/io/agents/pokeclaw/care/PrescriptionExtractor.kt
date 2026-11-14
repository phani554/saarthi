// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.care

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Reads a prescription photo with a vision model on OpenRouter (Gemini only as a fallback when no
 * OpenRouter key is saved) and returns medicines, doctor and conditions.
 * Nothing is saved here; the UI shows the result and the user confirms before it is stored.
 */
object PrescriptionExtractor {
    private const val TAG = "PrescriptionExtractor"
    /** Vision model on OpenRouter, picked for low hallucination when reading medicines. Easy to change. */
    const val OPENROUTER_VISION_MODEL = "anthropic/claude-haiku-5.5"
    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val GEMINI_MODEL = "gemini-2.5-flash"

    data class Extraction(
        val medicines: List<Medicine>,
        val doctor: CareContact?,
        val conditions: List<String>,
        val date: String,
        val warnings: List<String>,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Where the photo goes for reading: "OpenRouter", "Google Gemini", or null when no key is set. */
    fun uploadTarget(): String? = when {
        openRouterKey().isNotBlank() -> "OpenRouter"
        geminiKey().isNotBlank() -> "Google Gemini"
        else -> null
    }

    private fun openRouterKey(): String = KVUtils.getApiKeyForProvider("OPENROUTER").trim()

    /** Gemini fallback key: only a key saved for Google (no generic or built-in key). */
    private fun geminiKey(): String = KVUtils.getApiKeyForProvider("GOOGLE").ifBlank {
        if (KVUtils.getLlmProvider().equals("GOOGLE", ignoreCase = true)) KVUtils.getSavedLlmApiKey() else ""
    }.trim()

    /** Blocking; call off the main thread. Nothing is saved here (the user taps Save on the result). */
    fun extract(context: Context, imageUri: Uri): kotlin.Result<Extraction> = runCatching {
        val orKey = openRouterKey()
        val gKey = if (orKey.isBlank()) geminiKey() else ""
        require(orKey.isNotBlank() || gKey.isNotBlank()) { "Set your OpenRouter key in Settings." }
        val jpeg = loadScaledJpeg(context, imageUri)
        val prompt = """
            This is a photo of a medical prescription from India. Read it carefully, including handwriting.
            Return ONLY JSON with this shape:
            {"doctor":{"name":"","qualification":"","clinic":"","phone":""},
             "date":"",
             "conditions":[""],
             "medicines":[{"name":"","dose":"","frequency":"","duration":"","notes":""}],
             "warnings":[""]}
            Rules: frequency like "1-0-1" or "twice daily after food". Leave a field "" if you cannot read it;
            never guess a medicine name. Put anything unclear in warnings.
        """.trimIndent()
        val text = if (orKey.isNotBlank()) readWithOpenRouter(orKey, jpeg, prompt) else readWithGemini(gKey, jpeg, prompt)
        parse(text)
    }.onFailure { XLog.w(TAG, "extract failed: ${it.message}") } // never logs the key

    /** OpenRouter chat completions with the image as a data URL; JSON-object response. */
    private fun readWithOpenRouter(apiKey: String, jpeg: ByteArray, prompt: String): String {
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("model", OPENROUTER_VISION_MODEL)
            put("temperature", 0.1)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "You read Indian medical prescriptions for an elderly user's care profile. Never guess; return only JSON."))
                .put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl))))))
        }
        val request = Request.Builder()
            .url(OPENROUTER_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/phani554/saarthi")
            .addHeader("X-Title", "Saathi")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        XLog.i(TAG, "Reading prescription via OpenRouter ($OPENROUTER_VISION_MODEL, key ${mask(apiKey)})")
        return http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("OpenRouter HTTP ${resp.code}")
            val content = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.opt("content")
            // content is normally a string; some providers return an array of text parts.
            when (content) {
                is String -> content
                is JSONArray -> (0 until content.length()).joinToString("") { content.optJSONObject(it)?.optString("text").orEmpty() }
                else -> ""
            }
        }
    }

    /** Fallback only when no OpenRouter key is saved and a Google key is. */
    private fun readWithGemini(apiKey: String, jpeg: ByteArray, prompt: String): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
                .put(JSONObject().put("text", prompt)))))
            put("generationConfig", JSONObject()
                .put("temperature", 0.1)
                .put("responseMimeType", "application/json"))
        }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        XLog.i(TAG, "Reading prescription via Gemini fallback (key ${mask(apiKey)})")
        return http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Gemini HTTP ${resp.code}")
            JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
        }
    }

    private fun mask(key: String): String = if (key.length <= 8) "****" else key.take(4) + "…" + key.takeLast(2)

    internal fun parse(text: String): Extraction {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "Could not read the prescription." }
        val o = JSONObject(text.substring(start, end + 1))
        val d = o.optJSONObject("doctor")
        val doctor = d?.let {
            val name = it.optString("name").trim()
            if (name.isBlank()) null else CareContact(
                role = CareRoles.DOCTOR, name = name, phone = it.optString("phone").trim(),
                note = listOf(it.optString("qualification"), it.optString("clinic")).filter { s -> s.isNotBlank() }.joinToString(", ")
            )
        }
        val date = o.optString("date").trim()
        val meds = (0 until (o.optJSONArray("medicines")?.length() ?: 0)).mapNotNull { i ->
            val m = o.getJSONArray("medicines").optJSONObject(i) ?: return@mapNotNull null
            val name = m.optString("name").trim()
            if (name.isBlank()) null else Medicine(
                name = name, dose = m.optString("dose").trim(), frequency = m.optString("frequency").trim(),
                duration = m.optString("duration").trim(), notes = m.optString("notes").trim(),
                prescribedBy = doctor?.name.orEmpty(), prescribedOn = date,
            )
        }
        fun strings(key: String) = (0 until (o.optJSONArray(key)?.length() ?: 0))
            .map { o.getJSONArray(key).optString(it).trim() }.filter { it.isNotBlank() }
        return Extraction(meds, doctor, strings("conditions"), date, strings("warnings"))
    }

    private fun loadScaledJpeg(context: Context, uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Could not open the image.")
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }
}
