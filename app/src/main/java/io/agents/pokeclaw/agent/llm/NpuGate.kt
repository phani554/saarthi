// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import java.io.File

/**
 * Decides whether the Qualcomm NPU backend is worth trying for a model file.
 *
 * Replaces the old hard gate (file name had to contain "_NPU_SM8635"), which blocked every other
 * valid NPU build. Rules (the NPU is only ever *tried*; EngineHolder always falls back GPU -> CPU):
 *  1. The file is an NPU build (name contains "_NPU_", or "qualcomm" plus a chip id such as
 *     "_qualcomm_sm8635"), or the user asked for the NPU (local backend preference "NPU").
 *  2. The QNN libraries are in nativeLibraryDir. They ship with the APK through
 *     com.google.ai.edge.litert:litert-npu-runtime-qualcomm (dispatch library) and
 *     com.qualcomm.qti:qnn-runtime (libQnnHtp/libQnnSystem + Hexagon stub/skel per HTP version).
 *  3. The chip the file was compiled for (parsed from the name, e.g. SM8635) matches
 *     Build.SOC_MODEL (API 31+). If either side is unknown, the NPU is tried anyway.
 *
 * Pure logic, no Android calls, so it is unit-tested directly.
 */
object NpuGate {

    data class Decision(val tryNpu: Boolean, val reason: String)

    /** Libraries every Qualcomm NPU run needs, whatever the chip. */
    val CORE_LIBS = listOf("libLiteRtDispatch_Qualcomm.so", "libQnnHtp.so", "libQnnSystem.so")

    /**
     * Hexagon (HTP) version per Snapdragon part, from Qualcomm's QNN supported-SoC table.
     * Only used to name the stub/skel pair to look for; an unknown chip just needs any pair.
     */
    private val HTP_ARCH = mapOf(
        "SM8450" to "V69", "SM8475" to "V69",
        "SM8550" to "V73", "SM8635" to "V73",
        "SM8650" to "V75",
        "SM8750" to "V79",
    )

    // Chip ids as they appear in LiteRT-LM file names: SM8635, QCS8275, SA8295, MT6991 ...
    private val SOC_TOKEN = Regex("(?i)(?<![A-Za-z0-9])(SM|QCS|SA|MT)(\\d{4})(?![0-9])")

    /** Chip the file was compiled for, upper-cased (e.g. "SM8635"), or null when the name has none. */
    fun targetSocFromFileName(fileName: String): String? =
        SOC_TOKEN.find(File(fileName).name)?.let { (it.groupValues[1] + it.groupValues[2]).uppercase() }

    /** True when the file name says it is an NPU (AOT-compiled) build. */
    fun isNpuBuild(fileName: String): Boolean {
        val name = File(fileName).name
        if (name.contains("_NPU_", ignoreCase = true) || name.contains("-NPU-", ignoreCase = true)) return true
        return name.contains("qualcomm", ignoreCase = true) && targetSocFromFileName(name) != null
    }

    /** Library names to look for on this phone (core libs + the HTP stub/skel for the chip). */
    fun requiredLibs(deviceSoc: String?, targetSoc: String?): List<String> {
        val arch = HTP_ARCH[deviceSoc?.uppercase()] ?: HTP_ARCH[targetSoc?.uppercase()]
        return if (arch != null) CORE_LIBS + listOf("libQnnHtp${arch}Stub.so", "libQnnHtp${arch}Skel.so") else CORE_LIBS
    }

    /** Names from [requiredLibs] missing in [presentLibs]; with an unknown chip, also flags "no stub/skel at all". */
    fun missingLibs(presentLibs: Collection<String>, deviceSoc: String?, targetSoc: String?): List<String> {
        val required = requiredLibs(deviceSoc, targetSoc)
        val missing = required.filterNot { it in presentLibs }.toMutableList()
        if (required.size == CORE_LIBS.size &&
            presentLibs.none { it.startsWith("libQnnHtpV") && it.endsWith("Stub.so") }
        ) missing += "libQnnHtpV*Stub.so"
        return missing
    }

    /**
     * @param fileName       model file name or path
     * @param userWantsNpu   local backend preference is "NPU" ("Use NPU")
     * @param deviceSoc      Build.SOC_MODEL on API 31+, else null
     * @param presentLibs    file names in applicationInfo.nativeLibraryDir
     */
    fun decide(fileName: String, userWantsNpu: Boolean, deviceSoc: String?, presentLibs: Collection<String>): Decision {
        val npuBuild = isNpuBuild(fileName)
        if (!npuBuild && !userWantsNpu) {
            return Decision(false, "not an NPU build (no _NPU_ in the name) and Use NPU is off")
        }
        val target = targetSocFromFileName(fileName)
        val device = deviceSoc?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it != "UNKNOWN" }
        val missing = missingLibs(presentLibs, device, target)
        if (missing.isNotEmpty()) {
            return Decision(false, "QNN libraries missing from the APK: ${missing.joinToString()}")
        }
        if (target != null && device != null && !device.contains(target) && !target.contains(device)) {
            return Decision(false, "file is compiled for $target but this phone is $device")
        }
        val why = buildString {
            append(if (npuBuild) "NPU build" else "Use NPU is on")
            append(when {
                target != null && device != null -> " for $target, phone is $device"
                target == null && device == null -> ", chip of file and phone unknown; trying with GPU/CPU fallback"
                target == null -> ", file names no chip (phone $device); trying with GPU/CPU fallback"
                else -> " for $target, phone chip unknown (Android < 12); trying with GPU/CPU fallback"
            })
        }
        return Decision(true, why)
    }
}
