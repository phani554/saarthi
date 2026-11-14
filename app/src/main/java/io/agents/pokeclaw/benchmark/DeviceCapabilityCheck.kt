// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.benchmark

import android.app.ActivityManager
import com.google.gson.annotations.SerializedName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import io.agents.pokeclaw.agent.llm.EngineHolder
import io.agents.pokeclaw.agent.llm.LocalBackendHealth
import io.agents.pokeclaw.agent.llm.LocalModelManager
import io.agents.pokeclaw.agent.llm.NpuGate
import io.agents.pokeclaw.agent.llm.LocalModelRuntime
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.utils.XLog
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Static device facts + live state, used by benchmark runs and the capability card. */
data class DeviceInfo(
    @SerializedName("manufacturer") val manufacturer: String? = null,
    @SerializedName("model") val model: String? = null,
    @SerializedName("device") val device: String? = null,
    @SerializedName("hardware") val hardware: String? = null,
    @SerializedName("board") val board: String? = null,
    @SerializedName("socManufacturer") val socManufacturer: String? = null,
    @SerializedName("socModel") val socModel: String? = null,
    @SerializedName("androidRelease") val androidRelease: String? = null,
    @SerializedName("sdkInt") val sdkInt: Int? = null,
    @SerializedName("abis64") val abis64: List<String>? = null,
    @SerializedName("totalRamMb") val totalRamMb: Long? = null,
    @SerializedName("availRamMb") val availRamMb: Long? = null,
    @SerializedName("lowMemory") val lowMemory: Boolean? = null,
    @SerializedName("cpuCores") val cpuCores: Int? = null,
    @SerializedName("batteryPct") val batteryPct: Int? = null,
    @SerializedName("batteryTempC") val batteryTempC: Double? = null,
    @SerializedName("thermalStatus") val thermalStatus: String? = null,
)

data class DynamicState(
    @SerializedName("availRamMb") val availRamMb: Long? = null,
    @SerializedName("batteryPct") val batteryPct: Int? = null,
    @SerializedName("batteryTempC") val batteryTempC: Double? = null,
    @SerializedName("thermalStatus") val thermalStatus: String? = null,
)

enum class Verdict { YES, RISKY, NO }

/** level: "ok", "warn" (makes the verdict RISKY), "bad" (makes it NO), "info" (no effect). */
data class CheckReason(val level: String, val text: String)

data class CapabilityResult(
    val verdict: Verdict,
    val reasons: List<CheckReason>,
    val modelName: String,
    val modelPath: String?,
    val modelDownloaded: Boolean,
)

data class RealTestResult(
    val ok: Boolean,
    val message: String,
    val backend: String? = null,
    val mtp: Boolean? = null,
    val engineWasAlreadyLoaded: Boolean = false,
    val loadMs: Long? = null,
    val firstReplyMs: Long? = null,
    val totalMs: Long? = null,
    val reply: String? = null,
)

object DeviceCapabilityCheck {

    private const val TAG = "DeviceCapabilityCheck"
    private const val MB = 1024L * 1024L
    private const val E2B_ID = "gemma4-e2b"
    private const val E2B_SIZE_FALLBACK = 2_588_147_712L
    private const val REAL_TEST_TIMEOUT_MS = 180_000L

    private val OPENCL_PATHS = listOf(
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        "/vendor/lib64/egl/libOpenCL.so",
    )

    private val realTestRunning = AtomicBoolean(false)

    // ==================== Device info ====================

    fun collectDeviceInfo(context: Context): DeviceInfo {
        val mem = memInfo(context)
        val dyn = collectDynamicState(context)
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            hardware = Build.HARDWARE,
            board = Build.BOARD,
            socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null,
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            abis64 = Build.SUPPORTED_64_BIT_ABIS?.toList(),
            totalRamMb = mem?.totalMem?.div(MB),
            availRamMb = mem?.availMem?.div(MB),
            lowMemory = mem?.lowMemory,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            batteryPct = dyn.batteryPct,
            batteryTempC = dyn.batteryTempC,
            thermalStatus = dyn.thermalStatus,
        )
    }

    fun collectDynamicState(context: Context): DynamicState {
        val mem = memInfo(context)
        var pct: Int? = null
        var temp: Double? = null
        try {
            // Sticky broadcast: null receiver just returns the last value, nothing is registered.
            val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (battery != null) {
                val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) pct = level * 100 / scale
                val t = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (t != Int.MIN_VALUE) temp = t / 10.0
            }
        } catch (e: Throwable) {
            XLog.w(TAG, "battery read failed: ${e.message}")
        }
        return DynamicState(
            availRamMb = mem?.availMem?.div(MB),
            batteryPct = pct,
            batteryTempC = temp,
            thermalStatus = thermalStatus(context),
        )
    }

    /** wifi / cellular / ethernet / vpn / none, at this moment. */
    fun networkType(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "none"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    } catch (_: Throwable) { "unknown" }

    /** Remaining battery charge in microamp-hours, or null when the phone does not report it. */
    fun chargeCounterUah(context: Context): Long? = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (v == Int.MIN_VALUE || v <= 0) null else v.toLong()
    } catch (_: Throwable) { null }

    private fun memInfo(context: Context): ActivityManager.MemoryInfo? = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    } catch (_: Throwable) { null }

    private fun thermalStatus(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "unknown"
            }
        } catch (_: Throwable) { null }
    }

    // ==================== Model file ====================

    private data class TargetModel(val name: String, val sizeBytes: Long, val path: String?, val downloaded: Boolean)

    /** The configured local model if its file exists, else the Gemma 4 E2B download. */
    private fun targetModel(context: Context): TargetModel {
        val e2b = LocalModelManager.AVAILABLE_MODELS.firstOrNull { it.id == E2B_ID }
        val configured = try { ModelConfigRepository.snapshot().local } catch (_: Throwable) { null }
        val configuredPath = configured?.modelPath?.takeIf { it.isNotBlank() && File(it).exists() }
        if (configuredPath != null) {
            val f = File(configuredPath)
            val name = configured?.displayName.orEmpty().ifBlank { f.name }
            return TargetModel(name, f.length(), configuredPath, true)
        }
        val name = e2b?.displayName ?: "Gemma 4 E2B"
        val size = e2b?.sizeBytes ?: E2B_SIZE_FALLBACK
        val path = e2b?.let { LocalModelManager.getModelPath(context, it) }
        return TargetModel(name, size, path, path != null)
    }

    // ==================== Static check ====================

    fun check(context: Context): CapabilityResult {
        val reasons = mutableListOf<CheckReason>()
        val target = targetModel(context)
        val mem = memInfo(context)
        val totalBytes = mem?.totalMem ?: 0L
        // Phones sold as "6 GB" report less (about 5.5 GB). Round like LocalModelManager.getDeviceRamGb.
        val ramGb = LocalModelManager.getDeviceRamGb(context)
        val ramText = String.format("%.1f", totalBytes / (1024.0 * 1024.0 * 1024.0))
        when {
            ramGb < 4 -> reasons += CheckReason("bad", "Memory (RAM) is about $ramGb GB ($ramText GB usable). Needs at least 4 GB.")
            ramGb < 6 -> reasons += CheckReason("warn", "Memory (RAM) is about $ramGb GB ($ramText GB usable). 6 GB or more is safer; the app may close.")
            else -> reasons += CheckReason("ok", "Memory (RAM) is about $ramGb GB. Enough.")
        }
        val availMb = mem?.availMem?.div(MB) ?: 0L
        if (mem != null && mem.availMem < target.sizeBytes) {
            reasons += CheckReason("warn", "Only $availMb MB memory free right now. Close other apps before using the offline model.")
        } else if (mem != null) {
            reasons += CheckReason("info", "$availMb MB memory free right now.")
        }

        if (target.downloaded) {
            reasons += CheckReason("ok", "Model is downloaded: ${File(target.path ?: "").name}")
        } else {
            val freeBytes = try { StatFs(LocalModelManager.getModelDir(context).absolutePath).availableBytes } catch (_: Throwable) { -1L }
            val needMb = target.sizeBytes / MB
            val freeMb = if (freeBytes >= 0) freeBytes / MB else -1L
            when {
                freeBytes < 0 -> reasons += CheckReason("warn", "Model not downloaded. Could not read free storage.")
                freeBytes < target.sizeBytes -> reasons += CheckReason("bad", "Model not downloaded and not enough storage: needs $needMb MB, free $freeMb MB.")
                freeBytes < target.sizeBytes + 500L * MB -> reasons += CheckReason("warn", "Model not downloaded. Storage is just enough: needs $needMb MB, free $freeMb MB.")
                else -> reasons += CheckReason("ok", "Model not downloaded yet. Storage is enough: needs $needMb MB, free $freeMb MB.")
            }
        }

        if (Build.VERSION.SDK_INT >= 28) {
            reasons += CheckReason("ok", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) is supported.")
        } else {
            reasons += CheckReason("bad", "Android SDK ${Build.VERSION.SDK_INT} is too old. Needs 28 or newer.")
        }

        val abis64 = Build.SUPPORTED_64_BIT_ABIS?.toList().orEmpty()
        if (abis64.contains("arm64-v8a")) {
            reasons += CheckReason("ok", "64-bit phone (arm64).")
        } else {
            reasons += CheckReason("bad", "Not a 64-bit ARM phone (${abis64.joinToString().ifBlank { "32-bit only" }}). The model cannot run.")
        }

        val openCl = OPENCL_PATHS.firstOrNull { safeExists(it) }
        if (openCl != null) {
            reasons += CheckReason("info", "Graphics chip (GPU) path likely available ($openCl).")
        } else {
            reasons += CheckReason("info", "No OpenCL library found. The model will likely run on the processor (CPU), which is slower.")
        }

        // NPU: same gate EngineHolder uses (NpuGate), so this screen says exactly why NPU will or won't be tried.
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else ""
        val nativeLibs = File(context.applicationInfo.nativeLibraryDir).list()?.toList().orEmpty()
        val userWantsNpu = io.agents.pokeclaw.utils.KVUtils.getLocalBackendPreference().equals("NPU", ignoreCase = true)
        val npuFiles = LocalModelManager.getModelDir(context).listFiles()
            ?.filter { it.name.endsWith(".litertlm", ignoreCase = true) && NpuGate.isNpuBuild(it.name) }
            .orEmpty()
        val npuCandidate = target.path?.takeIf { NpuGate.isNpuBuild(it) || userWantsNpu } ?: npuFiles.firstOrNull()?.path
        val chip = socModel.ifBlank { "unknown (Android < 12 does not report it)" }
        reasons += if (npuCandidate == null) {
            val missing = NpuGate.missingLibs(nativeLibs, socModel.ifBlank { null }, null)
            CheckReason(
                "info",
                "NPU not used: no NPU model file (name with _NPU_ or _qualcomm_<chip>) and Use NPU is off. " +
                    "Chip: $chip. " + if (missing.isEmpty()) "QNN libraries are bundled." else "Missing QNN libraries: ${missing.joinToString()}."
            )
        } else {
            val d = NpuGate.decide(npuCandidate, userWantsNpu, socModel.ifBlank { null }, nativeLibs)
            CheckReason("info", (if (d.tryNpu) "NPU will be tried first (falls back to GPU, then CPU): " else "NPU not used: ") +
                "${File(npuCandidate).name}: ${d.reason}.")
        }

        try {
            if (LocalBackendHealth.hasPendingGpuInitMarker()) {
                reasons += CheckReason("warn", "The graphics chip crashed last time the model started. Next start will use the slower CPU.")
            }
            if (LocalBackendHealth.isCpuSafeModeEnabled()) {
                val why = LocalBackendHealth.cpuSafeReason().ifBlank { "earlier crash" }
                reasons += CheckReason("warn", "Safe mode is on: the model uses CPU only ($why).")
            }
            if (LocalBackendHealth.hasVerifiedGpuSuccess()) {
                reasons += CheckReason("ok", "The graphics chip (GPU) has run the model on this phone before.")
            }
            if (LocalBackendHealth.isConservativeCpuModeSuggested()) {
                reasons += CheckReason("info", "This phone type starts on CPU first to avoid known GPU crashes.")
            }
        } catch (e: Throwable) {
            reasons += CheckReason("info", "Could not read past crash records: ${e.message}")
        }

        val verdict = when {
            reasons.any { it.level == "bad" } -> Verdict.NO
            reasons.any { it.level == "warn" } -> Verdict.RISKY
            else -> Verdict.YES
        }
        return CapabilityResult(verdict, reasons, target.name, target.path, target.downloaded)
    }

    private fun safeExists(path: String): Boolean = try { File(path).exists() } catch (_: Throwable) { false }

    // ==================== Real test ====================

    /**
     * Loads the downloaded model through the app's normal engine path (LocalModelRuntime/EngineHolder,
     * same NPU -> GPU -> CPU order and crash quarantine) and sends "Reply with OK".
     * Blocking: call from a background thread. Times out after 3 minutes.
     * The engine is closed afterwards unless it was already loaded before the test (then chat owns it).
     */
    fun runRealTest(context: Context): RealTestResult {
        val appCtx = context.applicationContext
        val target = targetModel(appCtx)
        val path = target.path
        if (!target.downloaded || path == null) {
            return RealTestResult(false, "The model is not downloaded yet. Download it in Settings first.")
        }
        val taskRunning = try { io.agents.pokeclaw.appViewModel.isTaskRunning() } catch (_: Throwable) { false }
        if (taskRunning) {
            return RealTestResult(false, "A task is running. Wait for it to finish, then try again.")
        }
        if (!realTestRunning.compareAndSet(false, true)) {
            return RealTestResult(false, "A test is already running.")
        }
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "benchmark-real-test") }
        val future = executor.submit(Callable {
            var weLoaded = false
            try {
                val wasLoaded = EngineHolder.getBackendLabel(path) != null
                val t0 = System.currentTimeMillis()
                val lease = LocalModelRuntime.acquireSharedEngine(appCtx, path, preferCpu = false)
                weLoaded = !wasLoaded
                val loadMs = System.currentTimeMillis() - t0
                val t1 = System.currentTimeMillis()
                val shot = LocalModelRuntime.runSingleShot(
                    context = appCtx,
                    modelPath = path,
                    systemPrompt = "You are a quick test. Answer in one word.",
                    prompt = "Reply with OK",
                    temperature = 0.0,
                    preferCpu = false,
                )
                val genMs = System.currentTimeMillis() - t1
                val engineInfo = BenchmarkRecorder.lastEngine?.takeIf { path.endsWith(it.modelFile) }
                val reply = shot.text?.take(60)
                RealTestResult(
                    ok = !reply.isNullOrBlank(),
                    message = if (!reply.isNullOrBlank()) "The model answered." else "The model loaded but gave an empty answer.",
                    backend = shot.backendLabel.ifBlank { lease.backendLabel },
                    mtp = engineInfo?.mtp,
                    engineWasAlreadyLoaded = wasLoaded,
                    loadMs = if (wasLoaded) engineInfo?.loadMs else loadMs,
                    firstReplyMs = genMs,
                    totalMs = loadMs + genMs,
                    reply = reply,
                )
            } catch (e: Throwable) {
                if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
                val msg = e.message.orEmpty()
                val friendly = when {
                    e is OutOfMemoryError -> "Not enough memory to load the model."
                    LocalModelRuntime.isSessionConflict(e) || LocalModelRuntime.isSessionConflict(e.cause) ->
                        "The chat screen is using the model. Close the chat and try again."
                    else -> "The model could not start: ${msg.take(160)}"
                }
                RealTestResult(false, friendly)
            } finally {
                // Close only an engine this test created; an engine loaded before belongs to chat/tasks.
                if (weLoaded) {
                    try { LocalModelRuntime.resetSharedEngine() } catch (e: Throwable) { XLog.w(TAG, "cleanup failed", e) }
                }
                realTestRunning.set(false)
            }
        })
        executor.shutdown()
        return try {
            future.get(REAL_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // The native call cannot be interrupted; the worker cleans up when it returns.
            RealTestResult(false, "No answer after ${REAL_TEST_TIMEOUT_MS / 1000} seconds. This phone is too slow for the offline model.")
        } catch (e: Throwable) {
            RealTestResult(false, "Test failed: ${(e.cause ?: e).message?.take(160)}")
        }
    }
}
