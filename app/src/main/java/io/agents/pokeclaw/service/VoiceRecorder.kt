// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import io.agents.pokeclaw.utils.XLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * In-app PCM audio recorder that captures microphone input directly
 * into 16kHz WAV bytes without triggering system speech dialog popups.
 */
object VoiceRecorder {

    private const val TAG = "VoiceRecorder"
    private const val SAMPLE_RATE = 16000
    private var isRecordingActive = false
    private var audioRecord: AudioRecord? = null
    private val recordingExecutor = Executors.newSingleThreadExecutor()

    fun isRecording(): Boolean = isRecordingActive

    @Synchronized
    fun startRecording(
        context: Context,
        maxDurationMs: Long = 7000L,
        onAudioCaptured: (ByteArray) -> Unit
    ) {
        if (isRecordingActive) {
            XLog.w(TAG, "Recording is already active")
            return
        }

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            XLog.e(TAG, "RECORD_AUDIO permission not granted for VoiceRecorder")
            onAudioCaptured(ByteArray(0))
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                XLog.e(TAG, "AudioRecord failed to initialize")
                cleanup()
                onAudioCaptured(ByteArray(0))
                return
            }

            audioRecord?.startRecording()
            isRecordingActive = true
            XLog.i(TAG, "AudioRecord started successfully (sampleRate=$SAMPLE_RATE)")

            recordingExecutor.submit {
                val pcmOutputStream = ByteArrayOutputStream()
                val buffer = ByteArray(bufferSize)
                val startTime = System.currentTimeMillis()
                var silenceStartTime = -1L

                try {
                    while (isRecordingActive && ((System.currentTimeMillis() - startTime) < maxDurationMs)) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (read > 0) {
                            pcmOutputStream.write(buffer, 0, read)

                            // Calculate RMS volume level for silence auto-stop
                            var sum = 0.0
                            for (i in 0 until read step 2) {
                                if (i + 1 < read) {
                                    val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                                    sum += sample * sample
                                }
                            }
                            val rms = sqrt(sum / (read / 2))
                            val isSilent = rms < 180.0 // threshold for soft speech vs quiet pause

                            if (isSilent && pcmOutputStream.size() > SAMPLE_RATE * 3) { // At least 1.5s recorded
                                if (silenceStartTime == -1L) {
                                    silenceStartTime = System.currentTimeMillis()
                                } else if ((System.currentTimeMillis() - silenceStartTime) > 1800L) { // 1.8s continuous quiet
                                    XLog.i(TAG, "Auto-stopping recording due to quiet pause (1.8s)")
                                    break
                                }
                            } else {
                                silenceStartTime = -1L
                            }
                        }
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Error during AudioRecord read loop", e)
                } finally {
                    cleanup()
                    val pcmBytes = pcmOutputStream.toByteArray()
                    XLog.i(TAG, "Finished recording PCM bytes: size=${pcmBytes.size}")
                    if (pcmBytes.size > 2000) {
                        val wavBytes = addWavHeader(pcmBytes, SAMPLE_RATE, 1, 16)
                        onAudioCaptured(wavBytes)
                    } else {
                        XLog.w(TAG, "Recorded audio too short (${pcmBytes.size} bytes)")
                        onAudioCaptured(ByteArray(0))
                    }
                }
            }

        } catch (e: Exception) {
            XLog.e(TAG, "Exception starting AudioRecord", e)
            cleanup()
            onAudioCaptured(ByteArray(0))
        }
    }

    @Synchronized
    fun stopRecording() {
        if (!isRecordingActive) return
        XLog.i(TAG, "Requesting AudioRecord stop")
        isRecordingActive = false
    }

    private fun cleanup() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        isRecordingActive = false
    }

    private fun addWavHeader(
        pcmBytes: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int
    ): ByteArray {
        val totalDataLen = pcmBytes.size + 36
        val byteRate = sampleRate * channels * (bitDepth / 8)
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * (bitDepth / 8)).toByte()
        header[33] = 0
        header[34] = bitDepth.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmBytes.size and 0xff).toByte()
        header[41] = ((pcmBytes.size shr 8) and 0xff).toByte()
        header[42] = ((pcmBytes.size shr 16) and 0xff).toByte()
        header[43] = ((pcmBytes.size shr 24) and 0xff).toByte()

        val wavBytes = ByteArray(44 + pcmBytes.size)
        System.arraycopy(header, 0, wavBytes, 0, 44)
        System.arraycopy(pcmBytes, 0, wavBytes, 44, pcmBytes.size)
        return wavBytes
    }
}
