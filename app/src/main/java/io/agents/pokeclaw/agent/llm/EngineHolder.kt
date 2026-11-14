// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import io.agents.pokeclaw.utils.XLog

/**
 * Process-wide singleton that keeps a single LiteRT-LM Engine alive across
 * the chat UI and the task agent.
 *
 * Why: Engine initialisation on CPU backend takes 2-3 s. Without this,
 * ComposeChatActivity closes its engine before a task, TaskOrchestrator opens a
 * new one, then after the task chat reloads again — 4-6 s wasted per round trip.
 *
 * Thread safety: all mutations are @Synchronized so chat executor and task
 * executor threads can both call getOrCreate() safely.
 */
object EngineHolder {

    private const val TAG = "EngineHolder"

    private var engine: Engine? = null
    private var currentModelPath: String? = null
    private var currentBackendLabel: String? = null
    /** Local backend preference (Auto "" / NPU / GPU / CPU) the current engine was built under. */
    private var currentPreference: String? = null

    /**
     * Return the existing Engine if the model path matches, otherwise close the
     * old one and create a fresh Engine for the new model.
     *
     * @param modelPath  absolute path to the .litertlm model file
     * @param cacheDir   app's cacheDir.path — passed in so this object stays
     *                   context-free and easier to unit-test
     */
    @Synchronized
    fun getOrCreate(modelPath: String, cacheDir: String, preferCpu: Boolean = false): Engine {
        val existing = engine
        // preferCpu (chat's CPU retry after an accelerator failure) must not get the old GPU/NPU engine back.
        val wantCpuSwap = preferCpu && currentBackendLabel != null && currentBackendLabel != "CPU"
        // A new choice in Settings › Models (Auto/NPU/GPU/CPU) rebuilds the engine on its next use.
        val preference = try {
            io.agents.pokeclaw.utils.KVUtils.getLocalBackendPreference().uppercase()
        } catch (_: Throwable) {
            ""
        }
        val preferenceChanged = currentPreference != null && currentPreference != preference
        if (existing != null && currentModelPath == modelPath && currentBackendLabel != null && !wantCpuSwap && !preferenceChanged) {
            XLog.d(TAG, "getOrCreate: reusing engine for $modelPath (${currentBackendLabel ?: "unknown"})")
            return existing
        }

        // Different model or first call — close old engine first
        if (existing != null) {
            XLog.i(
                TAG,
                "getOrCreate: runtime changed (model=$currentModelPath/${currentBackendLabel ?: "?"} -> $modelPath), closing old engine"
            )
            try {
                existing.close()
            } catch (e: Exception) {
                XLog.w(TAG, "getOrCreate: error closing old engine", e)
            }
            engine = null
            currentModelPath = null
        }

        // Old code: GPU only, and any GPU failure (OpenCL missing, driver issue) killed local mode.
        // Now: NPU (when NpuGate allows it: NPU build or "Use NPU", chip matches, QNN libs bundled) -> GPU -> CPU.
        val nativeDir = io.agents.pokeclaw.ClawApplication.instance.applicationInfo.nativeLibraryDir
        // Crash quarantine: a native abort during GPU/NPU/MTP init can't be caught, so the attempt is
        // marked before it starts. If the process dies, the next launch promotes the marker to CPU-safe
        // mode and shouldForceCpu() returns true, so only plain CPU (no MTP) is tried.
        val forceCpu = try {
            LocalBackendHealth.shouldForceCpu(preferCpu)
        } catch (e: Exception) {
            XLog.w(TAG, "getOrCreate: backend health check failed, keeping default order", e)
            false
        }
        val order = buildList {
            if (!forceCpu) {
                // "GPU" chosen in Settings › Models skips the NPU even for an NPU build.
                val userPrefersGpu = try {
                    io.agents.pokeclaw.utils.KVUtils.getLocalBackendPreference().equals("GPU", ignoreCase = true)
                } catch (_: Throwable) {
                    false
                }
                if (userPrefersGpu) {
                    lastNpuDecision = NpuGate.Decision(false, "GPU chosen in Settings")
                } else if (shouldTryNpu(modelPath, nativeDir)) {
                    add("NPU")
                }
                add("GPU")
            }
            add("CPU")
        }
        var lastError: Throwable? = null
        val benchInitStart = System.currentTimeMillis() // benchmark: total time incl. failed attempts
        // Gemma 4 files downloaded after 2026-05-05 carry an MTP "draft" model (speculative decoding,
        // Google: up to 2.2x decode on mobile GPU). Try it on GPU/CPU first; if the file has no drafter
        // or the runtime is too old, the same backend is retried without it.
        val attempts = order.flatMap { label ->
            if (label == "NPU" || forceCpu) listOf(label to false) else listOf(label to true, label to false)
        }
        for ((label, mtp) in attempts) {
            val backend = backendFor(label, nativeDir) ?: continue
            if (!setSpeculativeDecoding(mtp) && mtp) continue
            val risky = label != "CPU" || mtp
            if (risky) LocalBackendHealth.markGpuInitStarted(modelPath)
            val benchAttemptStart = System.currentTimeMillis()
            try {
                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    // Was 8172 (typo). NPU files have a fixed 4096 KV cache; the generic file gets 8192.
                    maxNumTokens = if (label == "NPU") 4096 else 8192,
                    cacheDir = cacheDir
                )
                val newEngine = Engine(engineConfig)
                try {
                    newEngine.initialize()
                } catch (e: Throwable) {
                    try { newEngine.close() } catch (_: Throwable) {}
                    throw e
                }
                engine = newEngine
                currentModelPath = modelPath
                currentBackendLabel = label
                // Read again: the crash quarantine above may have changed it (e.g. to CPU).
                currentPreference = try {
                    io.agents.pokeclaw.utils.KVUtils.getLocalBackendPreference().uppercase()
                } catch (_: Throwable) {
                    preference
                }
                if (label != "CPU") LocalBackendHealth.noteGpuInitSuccess(modelPath)
                else if (risky) LocalBackendHealth.markGpuInitFinished()
                XLog.i(TAG, "getOrCreate: engine ready for $modelPath with $label backend (mtp=$mtp)")
                try {
                    val now = System.currentTimeMillis()
                    io.agents.pokeclaw.benchmark.BenchmarkRecorder.noteEngineLoaded(
                        modelPath, label, mtp, now - benchAttemptStart, now - benchInitStart
                    )
                } catch (_: Throwable) {}
                return newEngine
            } catch (e: VirtualMachineError) {
                if (risky) LocalBackendHealth.markGpuInitFinished()
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: UnsatisfiedLinkError/ExceptionInInitializerError from one
                // backend's native libs must fall through to the next backend.
                if (risky) LocalBackendHealth.markGpuInitFinished()
                XLog.e(TAG, "getOrCreate: $label (mtp=$mtp) init failed for $modelPath, trying next option", e)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No LiteRT backend could load $modelPath")
    }

    /**
     * ExperimentalFlags.enableSpeculativeDecoding via reflection. litertlm 0.16.1 declares the setter as
     * setEnableSpeculativeDecoding(java.lang.Boolean); 0.10.0 used a primitive boolean. Try both.
     * Returns false when neither exists.
     */
    private fun setSpeculativeDecoding(on: Boolean): Boolean = try {
        val cls = Class.forName("com.google.ai.edge.litertlm.ExperimentalFlags")
        val instance = cls.getField("INSTANCE").get(null)
        val setter = try {
            cls.getMethod("setEnableSpeculativeDecoding", Boolean::class.javaObjectType)
        } catch (_: NoSuchMethodException) {
            cls.getMethod("setEnableSpeculativeDecoding", Boolean::class.javaPrimitiveType)
        }
        setter.invoke(instance, on)
        true
    } catch (e: Throwable) {
        XLog.w(TAG, "setSpeculativeDecoding($on) unavailable: ${e.message}")
        false
    }

    /**
     * NPU gate (see NpuGate): NPU build or user's "Use NPU" preference, chip in the file name matches
     * Build.SOC_MODEL (unknown on either side = try anyway), QNN libs present. Never a hard-coded file list;
     * a failed NPU init always falls through to GPU and then CPU.
     */
    private fun shouldTryNpu(modelPath: String, nativeDir: String): Boolean {
        val decision = try {
            val userWantsNpu = io.agents.pokeclaw.utils.KVUtils.getLocalBackendPreference().equals("NPU", ignoreCase = true)
            val deviceSoc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) android.os.Build.SOC_MODEL else null
            val present = java.io.File(nativeDir).list()?.toList().orEmpty()
            NpuGate.decide(modelPath, userWantsNpu, deviceSoc, present)
        } catch (e: Throwable) {
            NpuGate.Decision(false, "NPU check failed: ${e.message}")
        }
        lastNpuDecision = decision
        XLog.i(TAG, "NPU ${if (decision.tryNpu) "will be tried" else "skipped"} for ${java.io.File(modelPath).name}: ${decision.reason}")
        return decision.tryNpu
    }

    /** Last NPU gate result (for the phone check screen and debug reports). */
    @Volatile
    var lastNpuDecision: NpuGate.Decision? = null
        private set

    private fun backendFor(label: String, nativeDir: String): Backend? = when (label) {
        "GPU" -> Backend.GPU()
        "CPU" -> Backend.CPU()
        "NPU" -> try {
            // FastRPC must find our skel before the first QNN call.
            android.system.Os.setenv("ADSP_LIBRARY_PATH", "$nativeDir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/vendor/dsp", true)
            // Backend.NPU(String) exists in litertlm 0.10.0 and 0.16.1; reflection only guards odd builds.
            Class.forName("com.google.ai.edge.litertlm.Backend\$NPU")
                .getConstructor(String::class.java)
                .newInstance(nativeDir) as Backend
        } catch (e: Throwable) {
            XLog.w(TAG, "Backend.NPU not available in this litertlm version: ${e.message}")
            null
        }
        else -> null
    }

    /**
     * Explicitly close and release the engine. Call only when the model is being
     * unloaded entirely (e.g. user deletes the model file). Normal chat/task
     * transitions should NOT call this — they just close their Conversation objects.
     */
    @Synchronized
    fun close() {
        XLog.i(TAG, "close: releasing engine for $currentModelPath")
        try {
            engine?.close()
        } catch (e: Exception) {
            XLog.w(TAG, "close: error closing engine", e)
        }
        engine = null
        currentModelPath = null
        currentBackendLabel = null
        currentPreference = null
        XLog.i(TAG, "close: done")
    }


    /** Returns the actual backend label of the current shared engine, if any. */
    @Synchronized
    fun getBackendLabel(modelPath: String? = null): String? {
        return if (modelPath == null || currentModelPath == modelPath) currentBackendLabel else null
    }
}
