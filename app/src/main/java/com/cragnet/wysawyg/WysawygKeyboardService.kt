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
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var savedText: TextView? = null
    private var insertButton: Button? = null
    private var displayedRecoveryId: String? = null
    private var recoveryRefresh: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        WysawygLogger.init(this)
        RecordingSessions.store(this)
        scope.launch { RecordingSessions.state.collect { render() } }
    }

    override fun onCreateInputView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 16, 16, 16)
        val controls = LinearLayout(this@WysawygKeyboardService).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        recordButton = ImageButton(this@WysawygKeyboardService).apply {
            setBackgroundResource(R.drawable.circle_button)
            layoutParams = LinearLayout.LayoutParams(128, 128)
            setOnClickListener { toggleRecording() }
        }
        controls.addView(recordButton)
        status = TextView(this@WysawygKeyboardService)
        controls.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(Button(this@WysawygKeyboardService).apply {
            text = "Recover"
            setOnClickListener {
                startActivity(Intent(this@WysawygKeyboardService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
        addView(controls)
        savedText = TextView(this@WysawygKeyboardService).apply { maxLines = 4 }
        addView(savedText)
        insertButton = Button(this@WysawygKeyboardService).apply {
            text = "Insert saved text"
            setOnClickListener { insertSavedText() }
        }
        addView(insertButton)
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
        eligible = EditorInputPolicy.allowsText(type) && currentInputConnection != null
        editorPackage = info?.packageName
        editorType = type
        render()
        // RecoveryStore has no observable stream. Poll only while this input view is open.
        recoveryRefresh?.cancel()
        recoveryRefresh = scope.launch {
            while (isActive) {
                delay(500)
                render()
            }
        }
    }

    private fun render() {
        val session = RecordingSessions.state.value
        val recovery = Recoveries.get(this).snapshot()
        recordButton?.apply {
            val description = if (session.canStop) "Stop recording" else "Start dictation"
            if (contentDescription?.toString() != description) {
                setImageResource(if (session.canStop) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now)
                contentDescription = description
            }
            isEnabled = session.canStop || session.canStart(eligible, recovery.canCapture)
        }
        status?.updateText(when (session.phase) {
            RecordingPhase.CAPTURING -> "Recording continues until Stop, even when the keyboard closes."
            RecordingPhase.STARTING -> "Preparing microphone…"
            RecordingPhase.TRANSCRIBING -> "Transcribing. Audio kept until text is saved."
            RecordingPhase.INTERRUPTED -> "Recording kept. Open Recover to retry on device or discard."
            RecordingPhase.IDLE -> "Tap to record. Stop is also in the notification."
        })
        val item = recovery.items.lastOrNull()
        displayedRecoveryId = item?.id
        savedText?.apply {
            visibility = if (item == null) View.GONE else View.VISIBLE
            updateText(if (item == null) "" else {
                val label = when {
                    item.id in recovery.unsavedIds -> "Text kept in memory; saving will be retried before insertion."
                    recovery.writeFailed || recovery.unreadable -> "Storage needs attention. Text retained."
                    !canInsertRecovery(item.id) -> "Finishing this recording. Text retained."
                    else -> "Saved text (${recovery.items.size}, latest first)"
                }
                "$label\n${item.text.take(160)}${if (item.text.length > 160) "…" else ""}"
            })
        }
        insertButton?.apply {
            visibility = if (item == null) View.GONE else View.VISIBLE
            isEnabled = item != null && eligible &&
                EditorInputPolicy.allowsText(currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL) &&
                currentInputConnection != null && canInsertRecovery(item.id)
        }
    }

    private fun canInsertRecovery(id: String): Boolean {
        val session = RecordingSessions.state.value
        // A staged transcript still belongs to the service until its automatic insertion finishes.
        return session.id != id || session.phase == RecordingPhase.IDLE || session.phase == RecordingPhase.INTERRUPTED
    }

    private fun insertSavedText() {
        val id = displayedRecoveryId ?: return
        val generation = inputGeneration
        val connection = currentInputConnection
        val isCurrentEditor = {
            eligible && inputGeneration == generation && connection != null &&
                currentInputConnection === connection &&
                EditorInputPolicy.allowsText(currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL) &&
                canInsertRecovery(id)
        }
        scope.launch {
            val result = insertKeyboardSavedText(Recoveries.get(this@WysawygKeyboardService), id, isCurrentEditor) { text ->
                if (connection == null) KeyboardCommitResult.FAILED
                else commitKeyboardText(connection, text, isCurrentEditor)
            }
            val message = when (result) {
                KeyboardSavedTextResult.INSERTED -> "Saved text inserted."
                KeyboardSavedTextResult.RETAINED -> "Could not insert here. Text retained; select a non-password text box and try again."
                KeyboardSavedTextResult.UNAVAILABLE -> "That saved text is no longer available."
                KeyboardSavedTextResult.BUSY -> "This saved text is already being inserted."
                KeyboardSavedTextResult.STORAGE_FAILED -> "Could not save text. Text retained; free storage and try again."
                KeyboardSavedTextResult.SENT_UNCONFIRMED -> "Text sent, but insertion could not be confirmed. Saved text retained; check the editor before trying again."
                KeyboardSavedTextResult.INSERTED_RETAINED -> "Text inserted, but removal could not be saved. Saved text retained; inserting again will duplicate it."
            }
            Toast.makeText(this@WysawygKeyboardService, message, Toast.LENGTH_LONG).show()
            OverlayService.refreshSavedText()
            render()
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
            val connection = owner.get()?.currentInputConnection
            if (!valid() || connection == null) false else commitKeyboardText(connection, text) {
                valid() && owner.get()?.let {
                    it.currentInputConnection === connection &&
                        EditorInputPolicy.allowsText(it.currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL)
                } == true
            } == KeyboardCommitResult.CONFIRMED
        })
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) inputGeneration++
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        recoveryRefresh?.cancel()
        recoveryRefresh = null
        inputGeneration++
        eligible = false
        render()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        recoveryRefresh?.cancel()
        recoveryRefresh = null
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
        savedText = null
        insertButton = null
        displayedRecoveryId = null
        super.onDestroy()
    }
}

private fun TextView.updateText(value: String) {
    if (text.toString() != value) text = value
}
