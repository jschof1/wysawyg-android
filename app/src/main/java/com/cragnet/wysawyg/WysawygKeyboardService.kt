package com.cragnet.wysawyg

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WysawygKeyboardService : InputMethodService() {
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var ollamaClient: OllamaClient
    private lateinit var recoveries: RecoveryStore
    private var isRecording = false
    private var busy = false
    private var eligible = false
    private var inputGeneration = 0
    private var recordingGeneration = 0
    private var recordingConnection: InputConnection? = null
    private var recordButton: ImageButton? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        WysawygLogger.init(this)
        audioRecorder = AudioRecorder(this)
        ollamaClient = OllamaClient(this)
        recoveries = Recoveries.get(this)
    }

    override fun onCreateInputView(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }
        recordButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundResource(R.drawable.circle_button)
            contentDescription = "Start dictation"
            layoutParams = LinearLayout.LayoutParams(128, 128)
            setOnClickListener { toggleRecording() }
        }
        layout.addView(recordButton)
        return layout
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        cancelCapture()
        inputGeneration++
        val type = info?.inputType ?: InputType.TYPE_NULL
        val variation = type and InputType.TYPE_MASK_VARIATION
        val password = when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        eligible = type != InputType.TYPE_NULL && !password
        render()
    }

    private fun render() {
        recordButton?.apply {
            setImageResource(if (isRecording) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now)
            contentDescription = if (isRecording) "Finish dictation" else "Start dictation"
            isEnabled = eligible && !busy && (isRecording || recoveries.snapshot().canCapture)
        }
    }

    private fun toggleRecording() {
        if (busy || !eligible) return
        if (isRecording) {
            isRecording = false
            busy = true
            render()
            stopAndTranscribe()
        } else if (recoveries.snapshot().canCapture) {
            try {
                audioRecorder.start()
                recordingGeneration = inputGeneration
                recordingConnection = currentInputConnection
                isRecording = true
                render()
            } catch (e: Exception) {
                runCatching { audioRecorder.close() }
                WysawygLogger.e("Failed to start recording", e)
                Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopAndTranscribe() {
        val generation = recordingGeneration
        val connection = recordingConnection
        recordingConnection = null
        scope.launch {
            try {
                val wav = withContext(Dispatchers.IO) { audioRecorder.stop() }
                var staged: RecoveredTranscript? = null
                ollamaClient.transcribe(wav) { text -> if (text.isNotBlank()) staged = recoveries.stage(text) }
                val item = staged
                if (item != null && eligible && generation == inputGeneration && connection?.commitText(item.text, 1) == true) {
                    if (!withContext(NonCancellable + Dispatchers.IO) { recoveries.remove(item.id) }) {
                        Toast.makeText(this@WysawygKeyboardService,
                            "Inserted, but recovery removal was not saved. Check before inserting again.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@WysawygKeyboardService,
                        if (item == null) "No speech detected" else "Transcript kept in Recovered transcripts.", Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WysawygLogger.e("Transcription failed", e)
                Toast.makeText(this@WysawygKeyboardService, "Transcription failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { audioRecorder.close() } }
                busy = false
                render()
            }
        }
    }

    private fun cancelCapture() {
        if (!isRecording) return
        isRecording = false
        recordingConnection = null
        busy = true
        scope.launch {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { audioRecorder.close() } }
            busy = false
            render()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputGeneration++
        eligible = false
        cancelCapture()
        render()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        inputGeneration++
        eligible = false
        cancelCapture()
        render()
        super.onFinishInput()
    }

    override fun onDestroy() {
        scope.cancel()
        isRecording = false
        recordingConnection = null
        recordButton = null
        runCatching { audioRecorder.close() }
        super.onDestroy()
    }
}
