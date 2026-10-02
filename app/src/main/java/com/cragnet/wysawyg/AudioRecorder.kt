package com.cragnet.wysawyg

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
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

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone not available" }
            recorder.startRecording()
        } catch (e: Exception) {
            recorder.release()
            throw e
        }
        audioRecord = recorder
        recordingError = null
        isRecording = true
        recordingThread = Thread {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(bufferSize)
            try {
                while (isRecording) {
                    val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    when {
                        read > 0 -> output.write(buffer, 0, read)
                        read < 0 -> error("Microphone read failed ($read)")
                        else -> Thread.sleep(10)
                    }
                }
                lastRecording = encodeWav(output.toByteArray())
            } catch (e: Exception) {
                recordingError = e
            } finally {
                isRecording = false
                runCatching { recorder.stop() }
                recorder.release()
                audioRecord = null
                output.close()
            }
        }.apply { name = "WysawygAudio"; start() }
    }

    @Synchronized fun stop(): ByteArray {
        isRecording = false
        recordingThread?.join(5000)
        check(recordingThread?.isAlive != true) { "Recording did not stop" }
        recordingThread = null
        recordingError?.let { throw IllegalStateException("Could not capture audio", it) }
        return lastRecording ?: throw IllegalStateException("No recording captured")
    }

    @Synchronized fun close() {
        if (recordingThread != null) stop()
        lastRecording = null
    }

    private fun encodeWav(pcm: ByteArray): ByteArray = ByteArrayOutputStream(pcm.size + 44).use { out ->
        out.write("RIFF".toByteArray())
        out.write(intToByteArray(pcm.size + 36))
        out.write("WAVEfmt ".toByteArray())
        out.write(intToByteArray(16))
        out.write(shortToByteArray(1))
        out.write(shortToByteArray(1))
        out.write(intToByteArray(SAMPLE_RATE))
        out.write(intToByteArray(SAMPLE_RATE * 2))
        out.write(shortToByteArray(2))
        out.write(shortToByteArray(16))
        out.write("data".toByteArray())
        out.write(intToByteArray(pcm.size))
        out.write(pcm)
        out.toByteArray()
    }

    private fun intToByteArray(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }

    private var lastRecording: ByteArray? = null
    private var recordingError: Exception? = null
}
