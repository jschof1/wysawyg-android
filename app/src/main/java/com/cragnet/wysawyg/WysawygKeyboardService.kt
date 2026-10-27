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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class WysawygKeyboardService : InputMethodService() {
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var client: TranscriptionClient
    private var capture: CaptureAdmission.Reservation? = null
    private var limitMonitor: Job? = null
    private lateinit var recoveries: RecoveryStore
    private var isRecording = false
    private var busy = false
    private var eligible = false
    private var inputGeneration = 0
    private var recordingGeneration = 0
    private var recordingConnection: InputConnection? = null
    private var editorPackage: String? = null
    private var editorType = InputType.TYPE_NULL
    private var recordingContext = DictationContext()
    private var recordButton: ImageButton? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        WysawygLogger.init(this)
        audioRecorder = AudioRecorder(this)
        client = Transcriptions.get(this)
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
        layout.addView(android.widget.TextView(this).apply { text = "Recordings finish after 5 minutes" })
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
        editorPackage = info?.packageName
        editorType = type
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
            capture = client.admission.acquire() ?: run {
                Toast.makeText(this, "Finish the current dictation or resolve recovered transcripts first.", Toast.LENGTH_LONG).show()
                return
            }
            try {
                audioRecorder.start()
                recordingGeneration = inputGeneration
                recordingConnection = currentInputConnection
                recordingContext = DictationContext(editorPackage, editorType)
                isRecording = true
                Toast.makeText(this, "Recording. Finishes automatically after 5 minutes.", Toast.LENGTH_SHORT).show()
                limitMonitor = scope.launch {
                    while (isRecording) {
                        delay(100)
                        if (!audioRecorder.recording && isRecording) {
                            if (audioRecorder.limitReached) {
                                Toast.makeText(this@WysawygKeyboardService, "5-minute limit reached. Transcribing.", Toast.LENGTH_LONG).show()
                                toggleRecording()
                            } else cancelCapture()
                        }
                    }
                }
                render()
            } catch (e: Exception) {
                runCatching { audioRecorder.close() }
                capture?.cancelCapture()
                capture = null
                WysawygLogger.e("Failed to start recording", e)
                Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopAndTranscribe() {
        limitMonitor?.cancel()
        val reservation = capture ?: return
        val generation = recordingGeneration
        val connection = recordingConnection
        recordingConnection = null
        scope.launch {
            try {
                val wav = withContext(Dispatchers.IO) { audioRecorder.stop() }
                var staged: RecoveredTranscript? = null
                // Reading the editor is IPC that a slow app can stall, so keep it off the main thread.
                val shape = withContext(Dispatchers.IO) {
                    runCatching {
                        recordingContext.copy(
                            textBefore = connection?.getTextBeforeCursor(200, 0)?.toString().orEmpty(),
                            textAfter = connection?.getTextAfterCursor(200, 0)?.toString().orEmpty())
                    }.getOrDefault(recordingContext)
                }
                val text = client.transcribe(wav, reservation, { Interpretation.apply(this@WysawygKeyboardService, it, shape) }) { text ->
                    if (text.isNotBlank()) staged = recoveries.stage(text)
                }
                currentCoroutineContext().ensureActive()
                val item = staged
                if (item == null && text.isNotEmpty() && eligible && generation == inputGeneration) {
                    // Only a spoken "new line": nothing worth recovering, so insert it directly.
                    connection?.commitText(text, 1)
                } else if (item != null && eligible && generation == inputGeneration && connection?.commitText(item.text, 1) == true) {
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
                reservation.cancelCapture()
                capture = null
                busy = false
                render()
            }
        }
    }

    private fun cancelCapture() {
        if (!isRecording) return
        limitMonitor?.cancel()
        isRecording = false
        recordingConnection = null
        busy = true
        scope.launch {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { audioRecorder.close() } }
            capture?.cancelCapture()
            capture = null
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
        capture?.cancelCapture()
        capture = null
        super.onDestroy()
    }
}
