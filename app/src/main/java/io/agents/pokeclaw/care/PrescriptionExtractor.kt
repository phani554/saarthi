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
 * Reads a prescription photo with Gemini (vision) and returns medicines, doctor and conditions.
 * Nothing is saved here; the UI shows the result and the user confirms before it is stored.
 */
object PrescriptionExtractor {
    private const val TAG = "PrescriptionExtractor"
    private const val MODEL = "gemini-2.5-flash"

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

    /** Blocking; call off the main thread. */
    fun extract(context: Context, imageUri: Uri): kotlin.Result<Extraction> = runCatching {
        val apiKey = KVUtils.getLlmApiKey().trim()
        require(apiKey.isNotBlank()) { "No Gemini API key set (Settings → Models)." }
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
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Gemini HTTP ${resp.code}")
            JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
        }
        parse(text)
    }.onFailure { XLog.w(TAG, "extract failed: ${it.message}") }

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
