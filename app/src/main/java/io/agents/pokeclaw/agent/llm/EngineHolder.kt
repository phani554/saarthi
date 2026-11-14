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

    /**
     * Return the existing Engine if the model path matches, otherwise close the
     * old one and create a fresh Engine for the new model.
     *
     * @param modelPath  absolute path to the .litertlm model file
     * @param cacheDir   app's cacheDir.path — passed in so this object stays
     *                   context-free and easier to unit-test
     */
    @Synchronized
    fun getOrCreate(modelPath: String, cacheDir: String): Engine {
        val existing = engine
        if (existing != null && currentModelPath == modelPath && currentBackendLabel != null) {
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
        // Now: NPU (only for an SM8635 AOT file with the QNN libs bundled) -> GPU -> CPU.
        val nativeDir = io.agents.pokeclaw.ClawApplication.instance.applicationInfo.nativeLibraryDir
        val order = buildList {
            if (isNpuModel(modelPath) && npuLibsPresent(nativeDir)) add("NPU")
            add("GPU")
            add("CPU")
        }
        var lastError: Exception? = null
        for (label in order) {
            val backend = backendFor(label, nativeDir) ?: continue
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
                } catch (e: Exception) {
                    try { newEngine.close() } catch (_: Exception) {}
                    throw e
                }
                engine = newEngine
                currentModelPath = modelPath
                currentBackendLabel = label
                XLog.i(TAG, "getOrCreate: engine ready for $modelPath with $label backend")
                return newEngine
            } catch (e: Exception) {
                XLog.e(TAG, "getOrCreate: $label init failed for $modelPath, trying next backend", e)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No LiteRT backend could load $modelPath")
    }

    /** SM8635 (iQOO Neo 10R) NPU builds are AOT-compiled per chip, e.g. gemma-4-E2B-it_NPU_SM8635.litertlm. */
    private fun isNpuModel(path: String): Boolean = path.contains("_NPU_SM8635", ignoreCase = true)

    private fun npuLibsPresent(dir: String): Boolean =
        listOf("libLiteRtDispatch_Qualcomm.so", "libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV73Stub.so", "libQnnHtpV73Skel.so")
            .all { java.io.File(dir, it).exists() }
            .also { if (!it) XLog.w(TAG, "NPU model selected but QNN V73 libs are not in $dir; using GPU/CPU") }

    private fun backendFor(label: String, nativeDir: String): Backend? = when (label) {
        "GPU" -> Backend.GPU()
        "CPU" -> Backend.CPU()
        "NPU" -> try {
            // FastRPC must find our skel before the first QNN call.
            android.system.Os.setenv("ADSP_LIBRARY_PATH", "$nativeDir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/vendor/dsp", true)
            // Reflection so this compiles on litertlm 0.10.0 (no Backend.NPU); works on 0.18.1+.
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
        XLog.i(TAG, "close: done")
    }


    /** Returns the actual backend label of the current shared engine, if any. */
    @Synchronized
    fun getBackendLabel(modelPath: String? = null): String? {
        return if (modelPath == null || currentModelPath == modelPath) currentBackendLabel else null
    }
}
