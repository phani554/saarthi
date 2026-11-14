// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import android.content.Context
import android.os.StatFs
import io.agents.pokeclaw.utils.XLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages on-device LLM model downloads and storage.
 *
 * Models are downloaded from HuggingFace and stored in the app's
 * external files directory for persistence across app restarts.
 */
object LocalModelManager {

    private const val TAG = "LocalModelManager"
    private const val SIZE_TOLERANCE_BYTES = 32L * 1024L * 1024L

    /** Available models for download */
    data class ModelInfo(
        val id: String,
        val displayName: String,
        val url: String,
        val fileName: String,
        val sizeBytes: Long,
        val minRamGb: Int
    )

    data class DeviceSupport(
        val deviceRamGb: Int,
        val minimumBuiltInRamGb: Int,
        val bestSupportedModel: ModelInfo?,
    )

    data class CatalogEntry(
        val model: ModelInfo,
        val isDownloaded: Boolean,
        val isSupported: Boolean,
        val path: String?,
    )

    data class ActiveModelState(
        val displayName: String,
        val metaText: String,
        val statusText: String,
        val statusKind: StatusKind,
    )

    enum class AvailabilitySource {
        MANAGED_DOWNLOAD,
        LINKED_FILE,
        MISSING,
    }

    data class ModelAvailability(
        val isAvailable: Boolean,
        val source: AvailabilitySource,
    )

    enum class StatusKind {
        READY,
        WARNING,
        NEUTRAL,
    }

    val AVAILABLE_MODELS = listOf(
        ModelInfo(
            id = "gemma4-e2b",
            displayName = "Gemma 4 E2B — 2.6GB",
            // Was ".../gemma-4-E2B-it_qualcomm_qcs8275.litertlm": wrong case (HF file is
            // "_Qualcomm_QCS8275", so the download 404'd) and an NPU build for a different chip
            // (QCS8275 IoT), not this phone's SM8635. The generic build runs on CPU/GPU everywhere.
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712L,
            minRamGb = 8
        ),
        ModelInfo(
            id = "gemma4-e4b",
            displayName = "Gemma 4 E4B — 3.6GB",
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_650_000_000L,
            minRamGb = 10
        ),
    )

    /**
     * Pick the best model for this device based on available RAM.
     * Devices with 12GB+ RAM get E4B, everyone else gets E2B.
     */
    fun recommendedModel(context: Context): ModelInfo {
        val totalRamGb = getDeviceRamGb(context)
        return if (totalRamGb >= 12) {
            AVAILABLE_MODELS.first { it.id == "gemma4-e4b" }
        } else {
            AVAILABLE_MODELS.first { it.id == "gemma4-e2b" }
        }
    }

    fun getDeviceRamGb(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024L * 1024L * 1024L)).toInt() + 1
    }

    fun deviceSupport(context: Context): DeviceSupport {
        val deviceRamGb = getDeviceRamGb(context)
        return DeviceSupport(
            deviceRamGb = deviceRamGb,
            minimumBuiltInRamGb = AVAILABLE_MODELS.minOf { it.minRamGb },
            bestSupportedModel = AVAILABLE_MODELS
                .filter { it.minRamGb <= deviceRamGb }
                .maxByOrNull { it.minRamGb }
        )
    }

    fun bestSupportedModel(context: Context): ModelInfo? {
        return deviceSupport(context).bestSupportedModel
    }

    fun isModelSupportedOnDevice(context: Context, model: ModelInfo): Boolean {
        return deviceSupport(context).deviceRamGb >= model.minRamGb
    }

    fun catalog(context: Context): List<CatalogEntry> {
        val support = deviceSupport(context)
        return AVAILABLE_MODELS.map { model ->
            CatalogEntry(
                model = model,
                isDownloaded = isModelDownloaded(context, model),
                isSupported = model.minRamGb <= support.deviceRamGb,
                path = getModelPath(context, model),
            )
        }
    }

    fun configuredBuiltInModel(localConfig: LocalModelConfig): ModelInfo? {
        return AVAILABLE_MODELS.find { matchesConfiguredModel(it, localConfig) }
    }

    fun availabilityForModel(
        context: Context,
        model: ModelInfo,
        localConfig: LocalModelConfig? = null
    ): ModelAvailability {
        if (isModelDownloaded(context, model)) {
            return ModelAvailability(
                isAvailable = true,
                source = AvailabilitySource.MANAGED_DOWNLOAD,
            )
        }

        val config = localConfig ?: return ModelAvailability(
            isAvailable = false,
            source = AvailabilitySource.MISSING,
        )

        val linkedFileExists = config.modelPath.isNotBlank() && File(config.modelPath).exists()
        if (linkedFileExists && matchesConfiguredModel(model, config)) {
            return ModelAvailability(
                isAvailable = true,
                source = AvailabilitySource.LINKED_FILE,
            )
        }

        return ModelAvailability(
            isAvailable = false,
            source = AvailabilitySource.MISSING,
        )
    }

    fun resolveActiveModelState(context: Context, localConfig: LocalModelConfig): ActiveModelState {
        val modelPath = localConfig.modelPath
        if (modelPath.isBlank()) {
            return ActiveModelState(
                displayName = "No model selected",
                metaText = "Download a model below",
                statusText = "● Not configured",
                statusKind = StatusKind.NEUTRAL,
            )
        }

        val matchedModel = configuredBuiltInModel(localConfig)
        if (matchedModel != null) {
            val availability = availabilityForModel(context, matchedModel, localConfig)
            return ActiveModelState(
                displayName = matchedModel.displayName,
                metaText = "${matchedModel.fileName} · On-device",
                statusText = when (availability.source) {
                    AvailabilitySource.MANAGED_DOWNLOAD -> "● Ready"
                    AvailabilitySource.LINKED_FILE -> "● Ready"
                    AvailabilitySource.MISSING -> "● Missing file"
                },
                statusKind = if (availability.isAvailable) StatusKind.READY else StatusKind.WARNING,
            )
        }

        return ActiveModelState(
            displayName = localConfig.displayName.ifBlank { File(modelPath).nameWithoutExtension },
            metaText = "On-device",
            statusText = if (File(modelPath).exists()) "● Ready" else "● Missing file",
            statusKind = if (File(modelPath).exists()) StatusKind.READY else StatusKind.WARNING,
        )
    }

    interface DownloadCallback {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long)
        fun onComplete(modelPath: String)
        fun onError(error: String)
        /** The user stopped the download ([cancelDownload]); the partial file is already deleted. */
        fun onCancelled() { onError("Download stopped") }
    }

    /** One running download. [cancelled] is checked between reads; [call] is cancelled to unblock a read. */
    private class DownloadHandle {
        @Volatile var cancelled = false
        @Volatile var call: okhttp3.Call? = null
    }

    /** In-flight downloads by model id, process-wide: a second screen can't start the same file twice. */
    private val activeDownloads = java.util.concurrent.ConcurrentHashMap<String, DownloadHandle>()

    fun isDownloading(modelId: String): Boolean = activeDownloads.containsKey(modelId)

    /**
     * Stops the running download of [model] and deletes its partial file (the download thread deletes it
     * once the stream is closed). With no download running, just deletes a leftover partial file.
     * A download that fails on its own (network drop) keeps its partial file so the next try resumes.
     */
    fun cancelDownload(context: Context, model: ModelInfo): Boolean {
        val handle = activeDownloads[model.id]
        if (handle == null) {
            val temp = File(getModelDir(context), "${model.fileName}.downloading")
            if (temp.exists()) temp.delete()
            return false
        }
        handle.cancelled = true
        try { handle.call?.cancel() } catch (_: Exception) {}
        XLog.i(TAG, "Download cancel requested: ${model.id}")
        return true
    }

    /**
     * Get the directory where models are stored.
     */
    fun getModelDir(context: Context): File {
        // getExternalFilesDir() is null when shared storage is unavailable; never use a relative path.
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Check if a model is already downloaded.
     */
    fun isModelDownloaded(context: Context, model: ModelInfo): Boolean {
        val file = File(getModelDir(context), model.fileName)
        return isValidModelFile(file, model)
    }

    /**
     * Get the path to a downloaded model.
     */
    fun getModelPath(context: Context, model: ModelInfo): String? {
        val file = File(getModelDir(context), model.fileName)
        return if (isValidModelFile(file, model)) file.absolutePath else null
    }

    private fun matchesConfiguredModel(model: ModelInfo, localConfig: LocalModelConfig): Boolean {
        if (localConfig.modelId.equals(model.id, ignoreCase = true)) return true

        val modelPath = localConfig.modelPath.lowercase()
        if (modelPath.endsWith(model.fileName.lowercase())) return true

        val display = localConfig.displayName.lowercase()
        return builtInAliases(model).any { alias ->
            modelPath.contains(alias) || display.contains(alias)
        }
    }

    private fun builtInAliases(model: ModelInfo): List<String> {
        return when (model.id) {
            "gemma4-e2b" -> listOf(
                "gemma4-e2b",
                "gemma-4-e2b",
                "gemma 4 e2b",
                "gemma4_2b",
                "gemma-4-2b",
                "gemma 4 2b",
                // "qcs8275" aliases removed: a QCS8275 NPU-AOT file can't load on GPU/CPU, so it must
                // not show as the generic E2B model being ready.
            )
            "gemma4-e4b" -> listOf(
                "gemma4-e4b",
                "gemma-4-e4b",
                "gemma 4 e4b",
                "gemma4_4b",
                "gemma-4-4b",
                "gemma 4 4b",
            )
            else -> emptyList()
        }
    }

    /**
     * Download a model from HuggingFace with progress reporting.
     * Supports resume via HTTP Range headers for partial downloads.
     *
     * Must be called from a background thread.
     */
    fun downloadModel(
        context: Context,
        model: ModelInfo,
        callback: DownloadCallback
    ) {
        val handle = DownloadHandle()
        if (activeDownloads.putIfAbsent(model.id, handle) != null) {
            callback.onError("${model.displayName} is already downloading")
            return
        }
        try {
            downloadModelInternal(context, model, callback, handle)
        } finally {
            activeDownloads.remove(model.id, handle)
        }
    }

    private fun downloadModelInternal(
        context: Context,
        model: ModelInfo,
        callback: DownloadCallback,
        handle: DownloadHandle
    ) {
        val modelDir = getModelDir(context)
        val targetFile = File(modelDir, model.fileName)
        val tempFile = File(modelDir, "${model.fileName}.downloading")
        cleanupInvalidFiles(model, targetFile, tempFile)

        // Check free space before starting download
        try {
            val stat = StatFs(modelDir.absolutePath)
            val availableBytes = stat.availableBytes
            val existingTempBytes = if (tempFile.exists()) tempFile.length() else 0L
            val bytesNeeded = model.sizeBytes - existingTempBytes
            if (bytesNeeded > 0 && availableBytes < bytesNeeded) {
                val needGb = String.format("%.1f", bytesNeeded / 1_000_000_000.0)
                val haveGb = String.format("%.1f", availableBytes / 1_000_000_000.0)
                XLog.e(TAG, "Not enough storage: need ${needGb}GB, have ${haveGb}GB available")
                callback.onError("Not enough storage: need ${needGb} GB free, only ${haveGb} GB available")
                return
            }
            XLog.d(TAG, "Storage check passed: need ${bytesNeeded / 1_000_000}MB, have ${availableBytes / 1_000_000}MB")
        } catch (e: Exception) {
            XLog.w(TAG, "Could not check storage, proceeding anyway", e)
        }

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            // Support resume
            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

            val requestBuilder = Request.Builder().url(model.url)
            if (existingBytes > 0) {
                requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                XLog.i(TAG, "Resuming download from byte $existingBytes")
            }

            val call = client.newCall(requestBuilder.build())
            handle.call = call
            if (handle.cancelled) call.cancel()
            // use{}: the response (and its connection) was never closed on the error/early-return paths.
            call.execute().use { response ->

                if (!response.isSuccessful && response.code != 206) {
                    callback.onError("Download failed: HTTP ${response.code}")
                    return
                }

                val isResumedResponse = existingBytes > 0 && response.code == 206
                if (existingBytes > 0 && !isResumedResponse) {
                    XLog.w(TAG, "Server ignored Range request for ${model.fileName}; restarting download from scratch")
                    tempFile.delete()
                }

                val totalBytes = if (isResumedResponse) {
                    // Partial content — total size from Content-Range header
                    val contentRange = response.header("Content-Range")
                    contentRange?.substringAfterLast("/")?.toLongOrNull() ?: model.sizeBytes
                } else {
                    response.body?.contentLength()?.takeIf { it > 0 } ?: model.sizeBytes
                }

                val body = response.body ?: run {
                    callback.onError("Empty response body")
                    return
                }

                val startingBytes = if (isResumedResponse) existingBytes else 0L
                val outputStream = FileOutputStream(tempFile, isResumedResponse)
                val buffer = ByteArray(8192)
                var downloadedBytes = startingBytes
                var lastReportTime = System.currentTimeMillis()
                var lastReportedBytes = startingBytes

                body.byteStream().use { input ->
                    outputStream.use { output ->
                        while (!handle.cancelled) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastReportTime >= 200) {
                                val elapsed = (now - lastReportTime) / 1000.0
                                val speed = ((downloadedBytes - lastReportedBytes) / elapsed).toLong()
                                callback.onProgress(downloadedBytes, totalBytes, speed)
                                lastReportTime = now
                                lastReportedBytes = downloadedBytes
                            }
                        }
                    }
                }

                if (handle.cancelled) {
                    tempFile.delete()
                    XLog.i(TAG, "Download cancelled by user: ${model.id}; partial file deleted")
                    callback.onCancelled()
                    return
                }

                if (!isValidModelFile(tempFile, model)) {
                    tempFile.delete()
                    callback.onError("Downloaded file looks incomplete or corrupted. Please retry.")
                    return
                }

                // Rename temp to final
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    callback.onError("Download finished but PokeClaw could not move the model into place")
                    return
                }

                XLog.i(TAG, "Model downloaded: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                callback.onComplete(targetFile.absolutePath)
            }

        } catch (e: Exception) {
            if (handle.cancelled) {
                // call.cancel() makes the blocked read throw; the stream is closed by now.
                tempFile.delete()
                XLog.i(TAG, "Download cancelled by user: ${model.id}; partial file deleted")
                callback.onCancelled()
                return
            }
            XLog.e(TAG, "Download failed", e)
            callback.onError("Download failed: ${e.message}")
        }
    }

    /** Progress of [importModelFile]; totalBytes is -1 when the provider does not report a size. */
    fun interface ImportProgress {
        fun onProgress(bytesCopied: Long, totalBytes: Long)
    }

    /**
     * Copies a .litertlm file the user picked (Storage Access Framework [uri]) into [getModelDir] and
     * returns the copied file. Blocking: call from a background thread. Copies to a temporary file first
     * and renames it when complete, so a failed or stopped copy never leaves a half model behind.
     * Throws IllegalArgumentException with a user-readable message for a wrong file or too little space.
     */
    fun importModelFile(
        context: Context,
        uri: android.net.Uri,
        progress: ImportProgress? = null,
        isCancelled: () -> Boolean = { false },
    ): File {
        val resolver = context.contentResolver
        var displayName: String? = null
        var size = -1L
        resolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIdx >= 0 && !c.isNull(nameIdx)) displayName = c.getString(nameIdx)
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
        if (displayName == null) displayName = uri.lastPathSegment
        val targetName = ModelImportNames.targetFileName(displayName)
            ?: throw IllegalArgumentException("Pick a .litertlm model file (got ${displayName ?: "unknown file"})")

        val dir = getModelDir(context)
        if (size > 0) {
            val free = try { StatFs(dir.absolutePath).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
            if (free < size + 64L * 1024L * 1024L) {
                throw IllegalArgumentException(
                    "Not enough space: the model needs ${size / 1_000_000} MB, ${free / 1_000_000} MB free"
                )
            }
        }

        val target = File(dir, targetName)
        val temp = File(dir, ModelImportNames.tempFileName(targetName))
        temp.delete()
        var copied = 0L
        try {
            val input = resolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Could not open the picked file")
            input.use { ins ->
                FileOutputStream(temp).use { out ->
                    val buf = ByteArray(1 shl 20)
                    var lastReport = 0L
                    while (true) {
                        if (isCancelled()) throw java.util.concurrent.CancellationException("Import stopped")
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - lastReport >= 8L * 1024L * 1024L) {
                            lastReport = copied
                            progress?.onProgress(copied, size)
                        }
                    }
                    out.fd.sync()
                }
            }
            if (copied <= 0L) throw IllegalArgumentException("The picked file is empty")
            if (size > 0 && copied != size) {
                throw IllegalStateException("Copy incomplete: $copied of $size bytes")
            }
            // Replacing the file the shared engine has open: release that engine first.
            if (target.exists() && EngineHolder.getBackendLabel(target.absolutePath) != null) {
                XLog.i(TAG, "Import replaces the loaded model ${target.name}; closing the engine")
                EngineHolder.close()
            }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("Could not replace the existing ${target.name}")
            }
            if (!temp.renameTo(target)) throw IllegalStateException("Could not save ${target.name}")
            progress?.onProgress(copied, size)
            XLog.i(TAG, "Imported model ${target.name} (${target.length()} bytes)")
            return target
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    /**
     * Delete a downloaded model to free space.
     */
    fun deleteModel(context: Context, model: ModelInfo): Boolean {
        val file = File(getModelDir(context), model.fileName)
        val tempFile = File(getModelDir(context), "${model.fileName}.downloading")
        tempFile.delete()
        return if (file.exists()) file.delete() else true
    }

    private fun cleanupInvalidFiles(model: ModelInfo, targetFile: File, tempFile: File) {
        if (targetFile.exists() && !isValidModelFile(targetFile, model)) {
            XLog.w(TAG, "Removing invalid completed model file: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            targetFile.delete()
        }
        if (tempFile.exists() && tempFile.length() > expectedUpperBound(model)) {
            XLog.w(TAG, "Removing oversized partial download: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            tempFile.delete()
        }
    }

    private fun isValidModelFile(file: File, model: ModelInfo): Boolean {
        if (!file.exists()) return false
        val length = file.length()
        if (length <= 0L) return false
        return length in expectedLowerBound(model)..expectedUpperBound(model)
    }

    private fun expectedLowerBound(model: ModelInfo): Long {
        return (model.sizeBytes - maxOf(SIZE_TOLERANCE_BYTES, model.sizeBytes / 20)).coerceAtLeast(1L)
    }

    private fun expectedUpperBound(model: ModelInfo): Long {
        return model.sizeBytes + maxOf(SIZE_TOLERANCE_BYTES, model.sizeBytes / 20)
    }
}
