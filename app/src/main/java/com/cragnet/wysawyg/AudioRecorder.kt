package com.cragnet.wysawyg

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorder(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "AudioRecorder"
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false

    @Synchronized fun start() {
        check(recordingThread == null) { "Recording already active" }
        lastRecording = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Allow microphone access in WYSAWYG settings before recording")
        }
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = minBufferSize.coerceAtLeast(4096)
        WysawygLogger.i("AudioRecorder.start: minBufferSize=$minBufferSize bufferSize=$bufferSize")

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            WysawygLogger.e("AudioRecord failed to initialize")
            throw IllegalStateException("AudioRecord not initialized")
        }

        audioRecord?.startRecording()
        isRecording = true

        val buffer = ByteArray(bufferSize)
        val outputStream = ByteArrayOutputStream()

        recordingThread = Thread {
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    outputStream.write(buffer, 0, read)
                } else if (read < 0) {
                    isRecording = false
                }
            }

            val pcmBytes = outputStream.toByteArray()
            outputStream.close()
            audioRecord?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            audioRecord?.release()
            audioRecord = null

            val wavFile = File(context.cacheDir, "recording.wav")
            writeWav(wavFile, pcmBytes)
            lastRecording = wavFile.readBytes()
        }.apply { start() }
    }

    fun stop(): ByteArray {
        isRecording = false
        audioRecord?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
        recordingThread?.join(5000)
        check(recordingThread?.isAlive != true) { "Recording did not stop" }
        recordingThread = null
        return lastRecording ?: throw IllegalStateException("No recording captured")
    }

    private fun writeWav(file: File, pcmBytes: ByteArray) {
        FileOutputStream(file).use { out ->
            val totalDataLen = pcmBytes.size + 36
            val longSampleRate = SAMPLE_RATE.toLong()
            val byteRate = (16 * SAMPLE_RATE * 1 / 8).toLong()

            out.write("RIFF".toByteArray())
            out.write(intToByteArray(totalDataLen))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intToByteArray(16))
            out.write(shortToByteArray(1))
            out.write(shortToByteArray(1))
            out.write(intToByteArray(longSampleRate.toInt()))
            out.write(intToByteArray(byteRate.toInt()))
            out.write(shortToByteArray((16 * 1 / 8).toShort()))
            out.write(shortToByteArray(16))
            out.write("data".toByteArray())
            out.write(intToByteArray(pcmBytes.size))
            out.write(pcmBytes)
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }

    private var lastRecording: ByteArray? = null
}
