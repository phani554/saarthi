package io.agents.pokeclaw.benchmark

import android.content.Context
import android.os.Build
import io.agents.pokeclaw.BuildConfig
import io.agents.pokeclaw.utils.KVUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Send report": posts runs.jsonl, summary.csv and the tree traces to the developer's
 * Apps Script receiver, which saves them into a Drive folder per tester.
 * The URL and token come from local.properties (BENCH_UPLOAD_URL, BENCH_UPLOAD_TOKEN);
 * when the URL is empty the button says sending is not set up.
 */
object BenchmarkUploader {
    private const val KEY_TESTER = "bench_tester_name"

    data class Part(val name: String, val content: String, val mime: String = "text/plain")
    sealed class Result {
        data class Sent(val files: Int) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun isConfigured(): Boolean = BuildConfig.BENCH_UPLOAD_URL.isNotBlank()
    fun testerName(): String = KVUtils.getString(KEY_TESTER, "")
    fun setTesterName(name: String) { KVUtils.putString(KEY_TESTER, name.trim()) }

    fun collect(context: Context): List<Part> {
        val parts = mutableListOf<Part>()
        val runs = BenchmarkRecorder.runsFile(context)
        if (runs.exists() && runs.length() > 0) parts += Part("runs.jsonl", runs.readText())
        val csv = File(context.cacheDir, "bench_upload_summary.csv")
        if (runCatching { BenchmarkRecorder.writeCsv(context, csv) }.isSuccess && csv.exists()) {
            parts += Part("summary.csv", csv.readText(), "text/csv")
        }
        BenchmarkRecorder.tracesDir(context).listFiles()?.sortedBy { it.name }?.forEach {
            parts += Part(it.name, it.readText())
        }
        return parts
    }

    /** JSON body, built by hand so it is unit-testable without org.json. */
    fun payload(token: String, tester: String, device: String, parts: List<Part>): String = buildString {
        append("{\"token\":").append(q(token))
        append(",\"tester\":").append(q(tester))
        append(",\"device\":").append(q(device))
        append(",\"files\":[")
        parts.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append("{\"name\":").append(q(p.name)).append(",\"mime\":").append(q(p.mime))
                .append(",\"content\":").append(q(p.content)).append('}')
        }
        append("]}")
    }

    /** Blocking; call off the main thread. */
    fun send(context: Context): Result {
        if (!isConfigured()) return Result.Failed("Sending is not set up in this build.")
        val parts = collect(context)
        if (parts.none { it.name == "runs.jsonl" }) return Result.Failed("No runs to send yet.")
        val tester = testerName().ifBlank { Build.MODEL ?: "unknown" }
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val body = payload(BuildConfig.BENCH_UPLOAD_TOKEN, tester, device, parts)
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).build()
        val req = Request.Builder().url(BuildConfig.BENCH_UPLOAD_URL)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    !resp.isSuccessful -> Result.Failed("Server said ${resp.code}.")
                    text.contains("\"ok\":true") -> Result.Sent(parts.size)
                    else -> Result.Failed(text.take(120).ifBlank { "Empty reply." })
                }
            }
        } catch (e: Exception) {
            Result.Failed("No connection (${e.javaClass.simpleName}).")
        }
    }

    internal fun q(s: String): String {
        val sb = StringBuilder(s.length + 16).append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
