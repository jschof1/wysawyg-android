package com.cragnet.wysawyg

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** Optional control surface. The shared foreground service owns recording and transcription. */
class WysawygKeyboardService : InputMethodService() {
    private var eligible = false
    private var inputGeneration = 0
    private var editorPackage: String? = null
    private var editorType = InputType.TYPE_NULL
    private var recordButton: ImageButton? = null
    private var status: TextView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        WysawygLogger.init(this)
        RecordingSessions.store(this)
        scope.launch { RecordingSessions.state.collect { render() } }
    }

    override fun onCreateInputView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(16, 16, 16, 16)
        recordButton = ImageButton(this@WysawygKeyboardService).apply {
            setBackgroundResource(R.drawable.circle_button)
            layoutParams = LinearLayout.LayoutParams(128, 128)
            setOnClickListener { toggleRecording() }
        }
        addView(recordButton)
        status = TextView(this@WysawygKeyboardService)
        addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(this@WysawygKeyboardService).apply {
            text = "Recover"
            setOnClickListener {
                startActivity(Intent(this@WysawygKeyboardService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
        render()
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        inputGeneration++
        eligible = false
        render()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputGeneration++
        val type = info?.inputType ?: InputType.TYPE_NULL
        val variation = type and InputType.TYPE_MASK_VARIATION
        val password = when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        eligible = type != InputType.TYPE_NULL && !password && currentInputConnection != null
        editorPackage = info?.packageName
        editorType = type
        render()
    }

    private fun render() {
        val session = RecordingSessions.state.value
        recordButton?.apply {
            setImageResource(if (session.canStop) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now)
            contentDescription = if (session.canStop) "Stop recording" else "Start dictation"
            isEnabled = session.canStop || session.canStart(eligible, Recoveries.get(this@WysawygKeyboardService).snapshot().canCapture)
        }
        status?.text = when (session.phase) {
            RecordingPhase.CAPTURING -> "Recording continues until Stop, even when the keyboard closes."
            RecordingPhase.STARTING -> "Preparing microphone…"
            RecordingPhase.TRANSCRIBING -> "Transcribing. Audio kept until text is saved."
            RecordingPhase.INTERRUPTED -> "Recording kept. Open Recover to retry on device or discard."
            RecordingPhase.IDLE -> "Tap to record. Stop is also in the notification."
        }
    }

    private fun toggleRecording() {
        val session = RecordingSessions.state.value
        if (session.canStop) {
            RecordingSessions.command(this, OverlayService.STOP)
            return
        }
        if (!session.canStart(eligible, Recoveries.get(this).snapshot().canCapture)) return
        val generation = inputGeneration
        val owner = WeakReference(this)
        val valid = { owner.get()?.let { it.eligible && it.inputGeneration == generation && it.currentInputConnection != null } == true }
        OverlayService.startFromIme(this, RecordingDestination(DictationContext(editorPackage, editorType), valid) { text ->
            if (!valid()) false else owner.get()?.currentInputConnection?.commitText(text, 1) == true
        })
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) inputGeneration++
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputGeneration++
        eligible = false
        render()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        inputGeneration++
        eligible = false
        render()
        super.onFinishInput()
    }

    override fun onDestroy() {
        inputGeneration++
        eligible = false
        scope.cancel()
        recordButton = null
        status = null
        super.onDestroy()
    }
}
