package com.cragnet.wysawyg

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

/** The foreground service owns this recorder; closing it preserves every written PCM buffer. */
class AudioRecorder(private val context: Context) {
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    @Volatile var level = 0f
        private set
    @Volatile var recordingError: Exception? = null
        private set
    val recording: Boolean get() = isRecording
    val hasWriter: Boolean get() = recordingThread?.isAlive == true

    @Synchronized fun start(store: DurablePcmStore, capture: DurablePcmStore.Capture) {
        check(recordingThread == null) { "Recording already active" }
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Allow microphone access in Speakeasy settings before recording"
        }
        val bufferSize = AudioRecord.getMinBufferSize(DurablePcmStore.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(DurablePcmStore.BYTES_PER_SECOND * 2)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, DurablePcmStore.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        val writer = try { store.openWriter(capture) } catch (e: Exception) { recorder.release(); throw e }
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone not available" }
            recorder.startRecording()
        } catch (e: Exception) { writer.close(); recorder.release(); throw e }
        recordingError = null
        isRecording = true
        recordingThread = Thread {
            val buffer = ByteArray(4096)
            try {
                writer.use {
                    while (isRecording) {
                        if (android.os.Build.VERSION.SDK_INT >= 29 && recorder.activeRecordingConfiguration?.isClientSilenced == true) {
                            error("Android interrupted microphone access")
                        }
                        val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                        when {
                            read > 0 -> { writer.append(buffer, read); level = PcmLevel.normalized(buffer, read) }
                            read < 0 -> error("Microphone read failed ($read)")
                            else -> Thread.sleep(10)
                        }
                    }
                }
            } catch (e: Exception) { recordingError = e }
            finally {
                isRecording = false
                level = 0f
                runCatching { recorder.stop() }
                recorder.release()
            }
        }.apply { name = "WysawygAudio"; start() }
    }

    @Synchronized fun stop() {
        isRecording = false
        recordingThread?.join(5000)
        check(recordingThread?.isAlive != true) { "Recording is still stopping; audio retained" }
        recordingThread = null
        level = 0f
    }

    fun close() = stop()
}
