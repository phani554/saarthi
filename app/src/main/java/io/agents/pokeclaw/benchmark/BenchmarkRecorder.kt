// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.benchmark

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.agents.pokeclaw.BuildConfig
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.llm.ActiveModelMode
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * Benchmark mode: records one JSON line per agent task to filesDir/benchmarks/runs.jsonl.
 *
 * Cost when OFF: every hook starts with a single volatile read ([current] == null) and returns.
 * Recording only starts in [startTask], which reads the KV toggle once per task.
 */
object BenchmarkRecorder {

    private const val TAG = "BenchmarkRecorder"
    const val KEY_ENABLED = "BENCHMARK_MODE_ENABLED"
    private const val DIR = "benchmarks"
    private const val RUNS_FILE = "runs.jsonl"
    private const val MAX_CAPTURES = 200
    private const val MAX_TOOL_CALLS = 300
    private const val MAX_LLM_CALLS = 300

    private val gson = Gson()
    private val idGen = AtomicLong(0)
    private val writeLock = Any()

    @Volatile private var enabledCache: Boolean? = null
    @Volatile private var current: RunBuilder? = null

    /** Last engine load seen in this process (set by EngineHolder on every successful load). */
    @Volatile var lastEngine: EngineLoadInfo? = null
        private set

    // ==================== Toggle ====================

    fun isEnabled(): Boolean {
        enabledCache?.let { return it }
        val v = try { KVUtils.getBoolean(KEY_ENABLED, false) } catch (_: Throwable) { false }
        enabledCache = v
        return v
    }

    fun setEnabled(enabled: Boolean) {
        try { KVUtils.putBoolean(KEY_ENABLED, enabled) } catch (e: Throwable) { XLog.w(TAG, "setEnabled failed", e) }
        enabledCache = enabled
    }

    /** True only while a task is being recorded. Hot-path check for all hooks. */
    @JvmStatic
    fun isRecording(): Boolean = current != null

    // ==================== Task lifecycle ====================

    /**
     * Start recording a task. Returns a run id (0 when benchmark mode is off).
     * [providerName]/[modelName]/[modelPathOrUrl] come from the AgentConfig used by the task.
     */
    fun startTask(taskText: String, isLocal: Boolean, modelName: String, modelPathOrUrl: String): Long {
        if (!isEnabled()) return 0L
        return try {
            val id = idGen.incrementAndGet()
            val ctx = ClawApplication.instance
            val snapshot = try { ModelConfigRepository.snapshot() } catch (_: Throwable) { null }
            val provider = when {
                isLocal -> "LOCAL"
                snapshot != null && snapshot.activeMode == ActiveModelMode.CLOUD -> snapshot.activeCloud.providerName
                else -> "CLOUD"
            }
            val model = ModelRec(
                mode = if (isLocal) "local" else "cloud",
                provider = provider,
                modelName = modelName.ifBlank { null },
                localModelFile = if (isLocal) modelPathOrUrl.substringAfterLast('/').ifBlank { null } else null,
                cloudHost = if (!isLocal) hostOf(modelPathOrUrl) else null,
                localBackendPreference = if (isLocal) snapshot?.local?.backendPreference?.ifBlank { "auto" } else null,
            )
            val b = RunBuilder(
                id = id,
                startMs = System.currentTimeMillis(),
                taskText = taskText.take(200),
                isLocal = isLocal,
                modelPath = if (isLocal) modelPathOrUrl else "",
                model = model,
                device = DeviceCapabilityCheck.collectDeviceInfo(ctx),
            )
            current = b
            TreeCompressionTrace.startRun()
            id
        } catch (e: Throwable) {
            XLog.w(TAG, "startTask failed", e)
            0L
        }
    }

    /** Replace the task text (e.g. with the parsed user request instead of the full envelope). */
    fun setTaskText(text: String) {
        val b = current ?: return
        synchronized(b) { b.taskText = text.take(200) }
    }

    fun onLlmCall(ms: Long, inputTokens: Int?, outputTokens: Int?, success: Boolean) {
        val b = current ?: return
        synchronized(b) {
            if (b.llmCalls.size < MAX_LLM_CALLS) b.llmCalls += LlmCallRec(ms, inputTokens, outputTokens, success)
        }
    }

    fun onToolCall(name: String, ms: Long, success: Boolean) {
        val b = current ?: return
        synchronized(b) {
            if (b.toolCalls.size < MAX_TOOL_CALLS) b.toolCalls += ToolCallRec(name, ms, success)
        }
    }

    fun onStep(step: Int) {
        val b = current ?: return
        synchronized(b) { if (step > b.steps) b.steps = step }
    }

    /** Called from the agent's terminal callbacks. Last call wins. */
    fun setOutcome(outcome: String, message: String?, round: Int, actualModelName: String? = null) {
        val b = current ?: return
        synchronized(b) {
            b.outcome = outcome
            b.outcomeMessage = message?.replace('\n', ' ')?.take(200)
            if (round > b.steps) b.steps = round
            if (!actualModelName.isNullOrBlank()) b.actualModelName = actualModelName
        }
    }

    /**
     * Accessibility tree capture. raw = full dump (all nodes), pruned = what the model sees.
     * mode = "pruned" (normal) or "full" (GetScreenInfoTool.useFullTree debug mode).
     */
    @JvmStatic
    fun onScreenCapture(rawNodes: Int, rawChars: Int, prunedNodes: Int, prunedChars: Int, prunedBuildMs: Double, fullBuildMs: Double, mode: String) {
        val b = current ?: return
        synchronized(b) {
            b.captureCount++
            if (b.captures.size < MAX_CAPTURES) {
                b.captures += TreeCaptureRec(
                    mode = mode,
                    rawNodes = rawNodes,
                    rawChars = rawChars,
                    prunedNodes = prunedNodes,
                    prunedChars = prunedChars,
                    nodeReductionPct = pct(rawNodes, prunedNodes),
                    charReductionPct = pct(rawChars, prunedChars),
                    prunedBuildMs = round2(prunedBuildMs),
                    fullBuildMs = round2(fullBuildMs),
                )
            }
        }
    }

    /** EngineHolder: an engine finished loading. Always stored (rare event), attached to a run if one is active. */
    fun noteEngineLoaded(modelPath: String, backend: String, mtp: Boolean, loadMs: Long, totalInitMs: Long) {
        val info = EngineLoadInfo(modelPath.substringAfterLast('/'), backend, mtp, loadMs, totalInitMs, System.currentTimeMillis())
        lastEngine = info
        val b = current ?: return
        synchronized(b) { b.engineLoadedDuringTask = info }
    }

    /** LocalLlmClient: the backend actually used for this task. */
    fun noteLocalBackend(label: String) {
        val b = current ?: return
        synchronized(b) { b.localBackend = label }
    }

    /** LocalLlmClient: one native sendMessage (a chat() call can make 2 when the model's action JSON was broken). */
    fun noteLocalSend(ms: Long, promptChars: Int) {
        val b = current ?: return
        synchronized(b) {
            if (b.localSends.size < MAX_LLM_CALLS) b.localSends += LocalSendRec(ms, promptChars)
        }
    }

    /**
     * Finish and write the run. [runId] must be the id returned by [startTask]; a stale task
     * finishing after a newer one started does nothing.
     */
    fun finishTask(runId: Long, cancelled: Boolean) {
        if (runId == 0L) return
        val b = current ?: return
        if (b.id != runId) return
        current = null
        try {
            val built = synchronized(b) { b.build(cancelled) }
            val traceName = writeTrace(ClawApplication.instance, built)
            val record = if (traceName != null) built.copy(treeTraceFile = traceName) else built
            append(ClawApplication.instance, record)
        } catch (e: Throwable) {
            XLog.w(TAG, "finishTask: could not write benchmark run", e)
        }
    }

    // ==================== Storage ====================

    fun runsFile(context: Context): File = File(File(context.filesDir, DIR), RUNS_FILE)

    /** Folder holding one spelled-out tree compression trace per recorded run. */
    fun tracesDir(context: Context): File = File(File(context.filesDir, DIR), "traces")

    private fun writeTrace(context: Context, r: RunRecord): String? = try {
        val header = buildString {
            append("Task: ").append(r.task?.text ?: "?").append("\n")
            append("Time: ").append(r.timestamp ?: "?").append("  Model: ").append(r.model?.modelName ?: r.model?.localModelFile ?: "?")
            append("  Outcome: ").append(r.task?.outcome ?: "?").append("\n")
            r.averages?.let {
                append("Run average: ").append(it.avgRawNodes ?: "?").append(" -> ").append(it.avgPrunedNodes ?: "?")
                    .append(" nodes (").append(it.avgNodeReductionPct ?: "?").append("% fewer), ")
                    .append(it.avgRawChars ?: "?").append(" -> ").append(it.avgPrunedChars ?: "?")
                    .append(" chars (").append(it.avgCharReductionPct ?: "?").append("% smaller) over ")
                    .append(r.tree?.captureCount ?: 0).append(" screens.")
            }
        }
        TreeCompressionTrace.takeRunText(header)?.let { text ->
            val name = "run-${r.timestampMs ?: System.currentTimeMillis()}.md"
            val dir = tracesDir(context).apply { mkdirs() }
            File(dir, name).writeText(text)
            name
        }
    } catch (e: Throwable) {
        XLog.w(TAG, "tree trace not written", e)
        null
    }

    /** The trace text of a run, or null. */
    fun readTrace(context: Context, name: String?): String? =
        name?.let { File(tracesDir(context), it) }?.takeIf { it.exists() }?.readText()

    private fun append(context: Context, record: RunRecord) {
        val line = gson.toJson(record)
        synchronized(writeLock) {
            val f = runsFile(context)
            f.parentFile?.mkdirs()
            f.appendText(line + "\n")
        }
        XLog.i(TAG, "benchmark run written (${line.length} chars)")
    }

    fun clear(context: Context) {
        synchronized(writeLock) {
            try { runsFile(context).delete() } catch (_: Throwable) {}
            try { tracesDir(context).deleteRecursively() } catch (_: Throwable) {}
        }
    }

    /** Last [n] runs, newest first. Lines that fail to parse are skipped. */
    fun readRecent(context: Context, n: Int = 20): List<RunSummary> {
        val lines = synchronized(writeLock) {
            val f = runsFile(context)
            if (!f.exists()) emptyList() else f.readLines()
        }
        return lines.asReversed().asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { parseSummary(it) }
            .take(n)
            .toList()
    }

    /** Writes a CSV summary of every run to [out]. Returns the number of rows. */
    fun writeCsv(context: Context, out: File): Int {
        val lines = synchronized(writeLock) {
            val f = runsFile(context)
            if (!f.exists()) emptyList() else f.readLines()
        }
        var rows = 0
        out.parentFile?.mkdirs()
        out.bufferedWriter().use { w ->
            w.write("timestamp,app_version,device,soc,ram_total_mb,mode,provider,model,backend,mtp,model_load_ms,task,total_ms,steps,llm_calls,avg_llm_ms,tool_calls,tree_captures,avg_raw_nodes,avg_pruned_nodes,avg_node_reduction_pct,avg_char_reduction_pct,avg_prune_ms,outcome,message\n")
            for (line in lines) {
                if (line.isBlank()) continue
                val r = try { gson.fromJson(line, RunRecord::class.java) } catch (_: Throwable) { null } ?: continue
                val cols = listOf(
                    r.timestamp,
                    r.appVersion,
                    "${r.device?.manufacturer} ${r.device?.model}",
                    r.device?.socModel ?: r.device?.hardware,
                    r.device?.totalRamMb?.toString(),
                    r.model?.mode,
                    r.model?.provider,
                    r.model?.modelName ?: r.model?.localModelFile,
                    r.model?.backend,
                    r.model?.mtp?.toString(),
                    r.model?.modelLoadMs?.toString(),
                    r.task?.text,
                    r.task?.totalMs?.toString(),
                    r.task?.steps?.toString(),
                    r.task?.llmCalls?.toString(),
                    r.averages?.avgLlmMs?.toString(),
                    r.task?.toolCalls?.size?.toString(),
                    r.tree?.captureCount?.toString(),
                    r.averages?.avgRawNodes?.toString(),
                    r.averages?.avgPrunedNodes?.toString(),
                    r.averages?.avgNodeReductionPct?.toString(),
                    r.averages?.avgCharReductionPct?.toString(),
                    r.averages?.avgPrunedBuildMs?.toString(),
                    r.task?.outcome,
                    r.task?.outcomeMessage,
                )
                w.write(cols.joinToString(",") { csv(it) })
                w.write("\n")
                rows++
            }
        }
        return rows
    }

    private fun parseSummary(line: String): RunSummary? = try {
        val r = gson.fromJson(line, RunRecord::class.java)
        RunSummary(
            timestamp = r.timestamp ?: "",
            task = r.task?.text ?: "",
            model = listOfNotNull(r.model?.modelName ?: r.model?.localModelFile, r.model?.backend).joinToString(" · "),
            totalSec = (r.task?.totalMs ?: 0L) / 1000.0,
            steps = r.task?.steps ?: 0,
            treeReductionPct = r.averages?.avgNodeReductionPct,
            outcome = r.task?.outcome ?: "?",
            treeTraceFile = r.treeTraceFile,
        )
    } catch (_: Throwable) {
        null
    }

    // ==================== Helpers ====================

    internal fun istTimestamp(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(Date(ms)) + " IST"
    }

    private fun hostOf(url: String): String? = try {
        android.net.Uri.parse(url).host
    } catch (_: Throwable) { null }

    private fun pct(raw: Int, pruned: Int): Double =
        if (raw <= 0) 0.0 else round2((raw - pruned) * 100.0 / raw)

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun csv(v: String?): String {
        val s = v ?: ""
        return if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"").replace('\n', ' ') + "\"" else s
    }

    // ==================== In-progress run ====================

    private class RunBuilder(
        val id: Long,
        val startMs: Long,
        var taskText: String,
        val isLocal: Boolean,
        val modelPath: String,
        val model: ModelRec,
        val device: DeviceInfo,
    ) {
        var steps = 0
        val llmCalls = mutableListOf<LlmCallRec>()
        val toolCalls = mutableListOf<ToolCallRec>()
        val localSends = mutableListOf<LocalSendRec>()
        val captures = mutableListOf<TreeCaptureRec>()
        var captureCount = 0
        var outcome: String? = null
        var outcomeMessage: String? = null
        var actualModelName: String? = null
        var localBackend: String? = null
        var engineLoadedDuringTask: EngineLoadInfo? = null

        fun build(cancelled: Boolean): RunRecord {
            val end = System.currentTimeMillis()
            val ctx = ClawApplication.instance
            val engine = engineLoadedDuringTask
            val last = BenchmarkRecorder.lastEngine?.takeIf { isLocal && modelPath.endsWith(it.modelFile) }
            val finalOutcome = when {
                cancelled -> "cancelled"
                outcome != null -> outcome
                else -> "fail"
            }
            val modelOut = model.copy(
                modelName = model.modelName ?: actualModelName,
                actualModelName = actualModelName,
                backend = if (isLocal) (localBackend ?: engine?.backend ?: last?.backend) else null,
                mtp = if (isLocal) (engine ?: last)?.mtp else null,
                modelLoadMs = if (isLocal) (engine ?: last)?.loadMs else null,
                engineInitTotalMs = if (isLocal) (engine ?: last)?.totalInitMs else null,
                engineLoadedDuringTask = if (isLocal) (engine != null) else null,
            )
            val inTok = llmCalls.mapNotNull { it.inputTokens }
            val outTok = llmCalls.mapNotNull { it.outputTokens }
            val task = TaskRec(
                text = taskText,
                startMs = startMs,
                endMs = end,
                totalMs = end - startMs,
                steps = steps,
                llmCalls = llmCalls.size,
                llmLatenciesMs = llmCalls.map { it.ms },
                llmInputTokens = inTok.takeIf { it.isNotEmpty() },
                llmOutputTokens = outTok.takeIf { it.isNotEmpty() },
                totalInputTokens = inTok.takeIf { it.isNotEmpty() }?.sum(),
                totalOutputTokens = outTok.takeIf { it.isNotEmpty() }?.sum(),
                failedLlmCalls = llmCalls.count { !it.success }.takeIf { it > 0 },
                localSends = localSends.takeIf { it.isNotEmpty() }?.toList(),
                toolCalls = toolCalls.toList(),
                outcome = finalOutcome,
                outcomeMessage = outcomeMessage,
            )
            val okLlm = llmCalls.filter { it.success }
            val pruned = captures.filter { it.mode == "pruned" }
            val avg = AveragesRec(
                avgLlmMs = okLlm.takeIf { it.isNotEmpty() }?.map { it.ms }?.average()?.let { BenchmarkRecorder.round2(it) },
                avgToolMs = toolCalls.takeIf { it.isNotEmpty() }?.map { it.ms }?.average()?.let { BenchmarkRecorder.round2(it) },
                avgRawNodes = pruned.takeIf { it.isNotEmpty() }?.map { it.rawNodes }?.average()?.let { BenchmarkRecorder.round2(it) },
                avgPrunedNodes = pruned.takeIf { it.isNotEmpty() }?.map { it.prunedNodes }?.average()?.let { BenchmarkRecorder.round2(it) },
                avgRawChars = pruned.takeIf { it.isNotEmpty() }?.map { it.rawChars }?.average()?.let { BenchmarkRecorder.round2(it) },
                avgPrunedChars = pruned.takeIf { it.isNotEmpty() }?.map { it.prunedChars }?.average()?.let { BenchmarkRecorder.round2(it) },
                avgNodeReductionPct = pruned.takeIf { it.isNotEmpty() }?.map { it.nodeReductionPct }?.average()?.let { BenchmarkRecorder.round2(it) },
                avgCharReductionPct = pruned.takeIf { it.isNotEmpty() }?.map { it.charReductionPct }?.average()?.let { BenchmarkRecorder.round2(it) },
                avgPrunedBuildMs = pruned.takeIf { it.isNotEmpty() }?.map { it.prunedBuildMs }?.average()?.let { BenchmarkRecorder.round2(it) },
                avgFullBuildMs = pruned.takeIf { it.isNotEmpty() }?.map { it.fullBuildMs }?.average()?.let { BenchmarkRecorder.round2(it) },
            )
            return RunRecord(
                schema = 1,
                timestamp = BenchmarkRecorder.istTimestamp(startMs),
                timestampMs = startMs,
                appVersion = BuildConfig.VERSION_NAME,
                versionInfo = BuildConfig.VERSION_INFO,
                device = device,
                deviceAtEnd = DeviceCapabilityCheck.collectDynamicState(ctx),
                model = modelOut,
                task = task,
                tree = TreeRec(captureCount = captureCount, captures = captures.toList()),
                averages = avg,
            )
        }
    }
}

// ==================== JSON records (Gson; null fields are omitted) ====================

data class EngineLoadInfo(
    val modelFile: String,
    val backend: String,
    val mtp: Boolean,
    val loadMs: Long,
    val totalInitMs: Long,
    val atMs: Long,
)

data class RunRecord(
    @SerializedName("schema") val schema: Int? = null,
    @SerializedName("timestamp") val timestamp: String? = null,
    @SerializedName("timestampMs") val timestampMs: Long? = null,
    @SerializedName("appVersion") val appVersion: String? = null,
    @SerializedName("versionInfo") val versionInfo: String? = null,
    @SerializedName("device") val device: DeviceInfo? = null,
    @SerializedName("deviceAtEnd") val deviceAtEnd: DynamicState? = null,
    @SerializedName("model") val model: ModelRec? = null,
    @SerializedName("task") val task: TaskRec? = null,
    @SerializedName("tree") val tree: TreeRec? = null,
    @SerializedName("averages") val averages: AveragesRec? = null,
    @SerializedName("treeTraceFile") val treeTraceFile: String? = null,
)

data class ModelRec(
    @SerializedName("mode") val mode: String? = null,
    @SerializedName("provider") val provider: String? = null,
    @SerializedName("modelName") val modelName: String? = null,
    @SerializedName("actualModelName") val actualModelName: String? = null,
    @SerializedName("localModelFile") val localModelFile: String? = null,
    @SerializedName("cloudHost") val cloudHost: String? = null,
    @SerializedName("localBackendPreference") val localBackendPreference: String? = null,
    @SerializedName("backend") val backend: String? = null,
    @SerializedName("mtp") val mtp: Boolean? = null,
    @SerializedName("modelLoadMs") val modelLoadMs: Long? = null,
    @SerializedName("engineInitTotalMs") val engineInitTotalMs: Long? = null,
    @SerializedName("engineLoadedDuringTask") val engineLoadedDuringTask: Boolean? = null,
)

data class LlmCallRec(@SerializedName("ms") val ms: Long, @SerializedName("inputTokens") val inputTokens: Int?, @SerializedName("outputTokens") val outputTokens: Int?, @SerializedName("success") val success: Boolean)
data class ToolCallRec(@SerializedName("name") val name: String, @SerializedName("ms") val ms: Long, @SerializedName("success") val success: Boolean)
data class LocalSendRec(@SerializedName("ms") val ms: Long, @SerializedName("promptChars") val promptChars: Int)

data class TaskRec(
    @SerializedName("text") val text: String? = null,
    @SerializedName("startMs") val startMs: Long? = null,
    @SerializedName("endMs") val endMs: Long? = null,
    @SerializedName("totalMs") val totalMs: Long? = null,
    @SerializedName("steps") val steps: Int? = null,
    @SerializedName("llmCalls") val llmCalls: Int? = null,
    @SerializedName("llmLatenciesMs") val llmLatenciesMs: List<Long>? = null,
    @SerializedName("llmInputTokens") val llmInputTokens: List<Int>? = null,
    @SerializedName("llmOutputTokens") val llmOutputTokens: List<Int>? = null,
    @SerializedName("totalInputTokens") val totalInputTokens: Int? = null,
    @SerializedName("totalOutputTokens") val totalOutputTokens: Int? = null,
    @SerializedName("failedLlmCalls") val failedLlmCalls: Int? = null,
    @SerializedName("localSends") val localSends: List<LocalSendRec>? = null,
    @SerializedName("toolCalls") val toolCalls: List<ToolCallRec>? = null,
    @SerializedName("outcome") val outcome: String? = null,
    @SerializedName("outcomeMessage") val outcomeMessage: String? = null,
)

data class TreeCaptureRec(
    @SerializedName("mode") val mode: String,
    @SerializedName("rawNodes") val rawNodes: Int,
    @SerializedName("rawChars") val rawChars: Int,
    @SerializedName("prunedNodes") val prunedNodes: Int,
    @SerializedName("prunedChars") val prunedChars: Int,
    @SerializedName("nodeReductionPct") val nodeReductionPct: Double,
    @SerializedName("charReductionPct") val charReductionPct: Double,
    @SerializedName("prunedBuildMs") val prunedBuildMs: Double,
    @SerializedName("fullBuildMs") val fullBuildMs: Double,
)

data class TreeRec(@SerializedName("captureCount") val captureCount: Int? = null, @SerializedName("captures") val captures: List<TreeCaptureRec>? = null)

data class AveragesRec(
    @SerializedName("avgLlmMs") val avgLlmMs: Double? = null,
    @SerializedName("avgToolMs") val avgToolMs: Double? = null,
    @SerializedName("avgRawNodes") val avgRawNodes: Double? = null,
    @SerializedName("avgPrunedNodes") val avgPrunedNodes: Double? = null,
    @SerializedName("avgRawChars") val avgRawChars: Double? = null,
    @SerializedName("avgPrunedChars") val avgPrunedChars: Double? = null,
    @SerializedName("avgNodeReductionPct") val avgNodeReductionPct: Double? = null,
    @SerializedName("avgCharReductionPct") val avgCharReductionPct: Double? = null,
    @SerializedName("avgPrunedBuildMs") val avgPrunedBuildMs: Double? = null,
    @SerializedName("avgFullBuildMs") val avgFullBuildMs: Double? = null,
)

/** Row for the "last 20 runs" list. */
data class RunSummary(
    val timestamp: String,
    val task: String,
    val model: String,
    val totalSec: Double,
    val steps: Int,
    val treeReductionPct: Double?,
    val outcome: String,
    val treeTraceFile: String? = null,
)
