package com.cragnet.wysawyg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class OverlayService : Service() {
    private enum class State { IDLE, STARTING, RECORDING, BUSY, CANCELLING }
    private lateinit var windowManager: WindowManager
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var client: OllamaClient
    private lateinit var recoveries: RecoveryStore
    private lateinit var parameters: WindowManager.LayoutParams
    private var bubble: View? = null
    private var state = State.IDLE
    private var editorVisible = false
    private var keyboardBounds = Rect()
    private var target: TextInjectorService.Target? = null
    private var movedByUser = false
    private var destroyed = false
    private var recoveryIndex = 0
    private var recoveryAction = false
    private var recoveryVisible = false
    private var recoveryCollapsed = false
    private var startedAt = 0L
    private var operation: Job? = null
    private var meter: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val CHANNEL_ID = "wysawyg_overlay"
        private const val STOP = "com.cragnet.wysawyg.STOP_DICTATION"
        private var instance: OverlayService? = null
            private set
        fun isRunning(): Boolean = instance != null
        fun isEnabled(context: Context): Boolean = context
            .getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(MainActivity.PREF_OVERLAY_ENABLED, false)

        fun hasPermissions(context: Context): Boolean = Settings.canDrawOverlays(context) &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun ensureRunning(context: Context): Boolean {
            if (!isEnabled(context) || !hasPermissions(context)) return false
            if (isRunning()) return true
            return try {
                ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
                true
            } catch (e: Exception) {
                WysawygLogger.e("Unable to resume floating dictation", e)
                false
            }
        }

        fun pause(context: Context) {
            context.getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(MainActivity.PREF_OVERLAY_ENABLED, false).apply()
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun updateEditor(visible: Boolean, keyboard: Rect) {
            instance?.onEditorChanged(visible, keyboard)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        WysawygLogger.init(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioRecorder = AudioRecorder(this)
        client = OllamaClient(this)
        recoveries = Recoveries.get(this)
        recoveryIndex = recoveries.snapshot().items.lastIndex.coerceAtLeast(0)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Dictation", NotificationManager.IMPORTANCE_LOW)
        )
        parameters = WindowManager.LayoutParams(
            dp(52), dp(52), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            pause(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isEnabled(this) || !hasPermissions(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        showForeground(state == State.RECORDING)
        TextInjectorService.instance?.refreshEditor()
        return START_STICKY
    }

    private fun showForeground(recording: Boolean) {
        if (destroyed) return
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            Build.VERSION.SDK_INT >= 30 -> if (recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            else -> 0
        }
        ServiceCompat.startForeground(this, 1, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WYSAWYG dictation")
            .setContentText(if (recording) "Recording. Stop to transcribe or Cancel to discard." else "Ready when your keyboard is open")
            .setSmallIcon(R.drawable.ic_dictation_mic)
            .setContentIntent(open).setOngoing(true)
            .addAction(0, "Pause", stop).build(), type)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onEditorChanged(visible: Boolean, keyboard: Rect) {
        if (destroyed) return
        editorVisible = visible
        keyboardBounds = Rect(keyboard)
        if (!visible) {
            hideBubble()
            if (state == State.RECORDING || state == State.STARTING) cancelRecording()
        } else {
            showBubble()
        }
    }

    private fun showBubble() {
        if (bubble != null) {
            renderState()
            return
        }
        try {
            val content = LayoutInflater.from(this).inflate(R.layout.overlay_button, null)
            bubble = ScrollView(this).apply {
                isFocusable = false
                isFillViewport = false
                addView(content)
            }
            renderState(updateLayout = false)
            layoutPanel(update = false)
            windowManager.addView(bubble, parameters)
            view<ImageButton>(R.id.dictationButton)?.apply {
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (state == State.IDLE) startRecording()
                }
                makeDraggable(this) { state == State.IDLE && !recoveryVisible }
            }
            view<Button>(R.id.collapseRecovery)?.setOnClickListener { recoveryCollapsed = true; renderState() }
            view<Button>(R.id.showRecovery)?.setOnClickListener { recoveryCollapsed = false; renderState() }
            view<Button>(R.id.stopRecording)?.apply {
                setOnClickListener { stopAndTranscribe() }
                setOnLongClickListener { if (state == State.RECORDING) cancelRecording(); true }
            }
            view<Button>(R.id.cancelRecording)?.setOnClickListener { cancelRecording() }
            view<View>(R.id.recordingGrip)?.let { makeDraggable(it) }
            view<View>(R.id.recoveryGrip)?.let { makeDraggable(it) }
            view<Button>(R.id.copyRecovery)?.setOnClickListener {
                selectedRecovery()?.let { item ->
                    toast(if (Recoveries.copy(this, item)) "Copied. Transcript kept for recovery."
                        else "Could not copy the full transcript. Text retained; try Insert here.")
                }
            }
            view<Button>(R.id.insertRecovery)?.setOnClickListener { insertRecovery() }
            view<Button>(R.id.discardRecovery)?.setOnClickListener {
                if (!recoveryAction) recoveryMutation {
                    val item = selectedRecovery()
                    if (item != null) recoveries.remove(item.id) else recoveries.discardUnreadable()
                }
            }
            view<Button>(R.id.retryRecoverySave)?.setOnClickListener { recoveryMutation { recoveries.retrySave() } }
            view<Button>(R.id.previousRecovery)?.setOnClickListener { recoveryIndex--; renderState() }
            view<Button>(R.id.nextRecovery)?.setOnClickListener { recoveryIndex++; renderState() }
        } catch (e: Exception) {
            WysawygLogger.e("Unable to show dictation controls", e)
            hideBubble()
        }
    }

    private inline fun <reified T : View> view(id: Int): T? = bubble?.findViewById(id)

    private fun layoutPanel(update: Boolean = true) {
        val panel = bubble ?: return
        val screen = resources.displayMetrics
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusBarId != 0) resources.getDimensionPixelSize(statusBarId) else dp(24)
        val available = (keyboardBounds.top - statusBar - dp(12)).coerceAtLeast(1)
        val width = when {
            state == State.RECORDING || recoveryVisible -> 288
            view<View>(R.id.showRecovery)?.visibility == View.VISIBLE -> 244
            else -> 52
        }
        parameters.width = dp(width).coerceAtMost(screen.widthPixels - dp(24))
        panel.measure(View.MeasureSpec.makeMeasureSpec(parameters.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
        parameters.height = panel.measuredHeight.coerceAtLeast(1)
        if (!movedByUser) {
            parameters.x = screen.widthPixels - parameters.width - dp(16)
            parameters.y = available - parameters.height
        }
        parameters.x = parameters.x.coerceIn(0, (screen.widthPixels - parameters.width).coerceAtLeast(0))
        parameters.y = parameters.y.coerceIn(0, (available - parameters.height).coerceAtLeast(0))
        if (update) windowManager.updateViewLayout(panel, parameters)
    }

    private fun hideBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
    }

    private fun selectedRecovery(): RecoveredTranscript? {
        val items = recoveries.snapshot().items
        recoveryIndex = recoveryIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        return items.getOrNull(recoveryIndex)
    }

    private fun renderState(updateLayout: Boolean = true) {
        val snapshot = recoveries.snapshot()
        val item = selectedRecovery()
        val hasRecovery = item != null || snapshot.unreadable || snapshot.writeFailed
        recoveryVisible = state == State.IDLE && hasRecovery && !recoveryCollapsed
        view<View>(R.id.idlePanel)?.visibility = if (state == State.RECORDING || recoveryVisible) View.GONE else View.VISIBLE
        view<View>(R.id.recordingPanel)?.visibility = if (state == State.RECORDING) View.VISIBLE else View.GONE
        view<View>(R.id.recoveryPanel)?.visibility = if (recoveryVisible) View.VISIBLE else View.GONE
        view<ImageButton>(R.id.dictationButton)?.apply {
            imageAlpha = if (state == State.IDLE) 255 else 0
            isEnabled = state == State.IDLE && snapshot.canCapture && !recoveryAction
            contentDescription = if (state == State.IDLE) "Start dictation" else if (state == State.STARTING) "Preparing microphone" else "Transcribing"
        }
        view<Button>(R.id.showRecovery)?.apply {
            visibility = if (state == State.IDLE && hasRecovery && recoveryCollapsed) View.VISIBLE else View.GONE
            text = if (snapshot.items.isEmpty()) "Recovery needs attention" else getString(R.string.saved_text_count, snapshot.items.size)
            isEnabled = !recoveryAction
        }
        view<Button>(R.id.collapseRecovery)?.isEnabled = !recoveryAction
        view<ProgressBar>(R.id.dictationProgress)?.visibility = if (state != State.IDLE && state != State.RECORDING) View.VISIBLE else View.GONE
        view<TextView>(R.id.recoveryTitle)?.text = if (item == null) "Recovery storage needs attention" else "Recovered ${recoveryIndex + 1} of ${snapshot.items.size}"
        view<TextView>(R.id.recoveryText)?.let { if (it.text.toString() != item?.text.orEmpty()) it.text = item?.text.orEmpty() }
        view<TextView>(R.id.recoveryStatus)?.text = when {
            item?.id in snapshot.unsavedIds -> "Not saved. Kept in memory only; Copy before closing the app. New recording paused."
            snapshot.unsavedIds.isNotEmpty() -> "A transcript is not saved. Use the arrows to find it and Copy before closing. Recording paused."
            snapshot.unreadable -> "Saved recovery file is unreadable. Discard unreadable data to resume."
            snapshot.writeFailed -> "Could not update storage. An inserted transcript may reappear. Check it before inserting again."
            snapshot.items.size >= RecoveryStore.CAPACITY -> "5 saved; recording paused until you insert or discard one. Expires after 24 hours."
            else -> "Saved for 24 hours. Select a text box, then Insert here. Copy keeps this transcript."
        }
        view<View>(R.id.recoveryScroll)?.visibility = if (item != null) View.VISIBLE else View.GONE
        view<View>(R.id.recoveryActions)?.visibility = if (item != null) View.VISIBLE else View.GONE
        view<Button>(R.id.previousRecovery)?.isEnabled = !recoveryAction && recoveryIndex > 0
        view<Button>(R.id.nextRecovery)?.isEnabled = !recoveryAction && recoveryIndex < snapshot.items.lastIndex
        view<Button>(R.id.discardRecovery)?.apply {
            text = if (item == null && snapshot.unreadable) "Discard unreadable data" else "Discard"
            isEnabled = !recoveryAction && (item != null || snapshot.unreadable)
        }
        view<Button>(R.id.insertRecovery)?.isEnabled = !recoveryAction
        view<Button>(R.id.copyRecovery)?.isEnabled = !recoveryAction
        view<Button>(R.id.retryRecoverySave)?.apply {
            visibility = if (snapshot.writeFailed && !snapshot.unreadable) View.VISIBLE else View.GONE
            isEnabled = !recoveryAction
        }
        if (updateLayout && bubble != null) layoutPanel()
    }

    private fun startRecording() {
        if (state != State.IDLE || recoveryAction || !recoveries.snapshot().canCapture) return
        state = State.STARTING
        renderState()
        operation = scope.launch {
            try {
                val destination = TextInjectorService.instance?.captureTargetForDictation()
                currentCoroutineContext().ensureActive()
                if (destination == null || !editorVisible || destroyed || !isEnabled(this@OverlayService)) {
                    state = State.IDLE
                    renderState()
                    toast("Tap the text box again before dictating.")
                    return@launch
                }
                target = destination
                showForeground(recording = true)
                audioRecorder.start()
                startedAt = SystemClock.elapsedRealtime()
                state = State.RECORDING
                renderState()
                meter = scope.launch {
                    while (isActive && state == State.RECORDING) {
                        if (!audioRecorder.recording) {
                            toast("Microphone stopped. Recording discarded.")
                            cancelRecording()
                            break
                        }
                        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
                        view<TextView>(R.id.recordingTimer)?.text = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
                        view<ProgressBar>(R.id.recordingLevel)?.progress = (audioRecorder.level * 100).toInt()
                        delay(100)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { audioRecorder.close() }
                target = null
                state = State.IDLE
                renderState()
                showForeground(recording = false)
                WysawygLogger.e("Recording failed", e)
                toast("Could not use the microphone. Check microphone permission.")
            }
        }
    }

    private fun stopMeter() {
        meter?.cancel()
        meter = null
        view<ProgressBar>(R.id.recordingLevel)?.progress = 0
    }

    private fun cancelRecording() {
        if (state != State.RECORDING && state != State.STARTING) return
        operation?.cancel()
        target = null
        stopMeter()
        state = State.CANCELLING
        renderState()
        operation = scope.launch {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { audioRecorder.close() } }
            if (!destroyed) {
                showForeground(recording = false)
                state = State.IDLE
                renderState()
                operation = null
            }
        }
    }

    private fun stopAndTranscribe() {
        if (state != State.RECORDING) return
        val destination = target ?: return
        stopMeter()
        state = State.BUSY
        renderState()
        operation = scope.launch {
            try {
                val audio = withContext(Dispatchers.IO) { audioRecorder.stop() }
                showForeground(recording = false)
                var staged: RecoveredTranscript? = null
                client.transcribe(audio) { text ->
                    // Runs before the cancellable IO return, including a response arriving during Pause.
                    if (text.isNotBlank()) staged = recoveries.stage(text)
                }
                currentCoroutineContext().ensureActive()
                val item = staged
                if (item == null) toast("No speech detected") else completeTranscript(destination, item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WysawygLogger.e("Transcription failed", e)
                toast("Transcription failed: ${e.message}")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { audioRecorder.close() } }
                target = null
                if (!destroyed) {
                    showForeground(recording = false)
                    state = State.IDLE
                    renderState()
                    operation = null
                }
            }
        }
    }

    private suspend fun completeTranscript(destination: TextInjectorService.Target, item: RecoveredTranscript) {
        currentCoroutineContext().ensureActive()
        if (destroyed || !isEnabled(this)) return
        val result = TextInjectorService.instance?.insertForDictation(destination, item.text)
        if (result == TextInjectorService.InsertResult.INSERTED) {
            if (!withContext(NonCancellable + Dispatchers.IO) { recoveries.remove(item.id) }) {
                toast("Inserted, but recovery removal was not saved. Check before inserting again.")
            }
        } else {
            recoveryCollapsed = false
            recoveryIndex = recoveries.snapshot().items.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
            toast("Transcript kept. Select a text box and tap Insert here, or Copy.")
        }
        if (!destroyed) renderState()
    }

    private fun insertRecovery() {
        if (recoveryAction || state != State.IDLE) return
        val item = selectedRecovery() ?: return
        recoveryAction = true
        renderState()
        operation = scope.launch {
            try {
                val destination = TextInjectorService.instance?.captureTargetForDictation()
                currentCoroutineContext().ensureActive()
                if (!editorVisible || destination == null) toast("Select a non-password text box with the keyboard open.")
                else completeTranscript(destination, item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WysawygLogger.e("Recovery insertion failed", e)
                toast("Could not insert here. Transcript retained; try Copy.")
            } finally {
                recoveryAction = false
                operation = null
                if (!destroyed) renderState()
            }
        }
    }

    private fun recoveryMutation(action: () -> Boolean) {
        if (recoveryAction || state != State.IDLE) return
        recoveryAction = true
        renderState()
        operation = scope.launch {
            try {
                if (!withContext(Dispatchers.IO) { action() }) toast("Could not update recovery storage. Text retained.")
            } finally {
                recoveryAction = false
                operation = null
                if (!destroyed) renderState()
            }
        }
    }

    private fun makeDraggable(view: View, enabled: () -> Boolean = { true }) {
        var x = 0
        var y = 0
        var downX = 0f
        var downY = 0f
        var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.isClickable = true
        view.setOnTouchListener { _, event ->
            if (!enabled()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    x = parameters.x; y = parameters.y
                    downX = event.rawX; downY = event.rawY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) dragging = true
                    if (dragging) {
                        view.cancelLongPress()
                        view.isPressed = false
                        movedByUser = true
                        parameters.x = x + dx.toInt()
                        parameters.y = y + dy.toInt()
                        layoutPanel()
                    }
                    dragging
                }
                MotionEvent.ACTION_UP -> if (dragging) { view.isPressed = false; true } else false
                MotionEvent.ACTION_CANCEL -> { view.isPressed = false; false }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        instance = null
        stopMeter()
        operation?.cancel()
        operation = null
        scope.cancel()
        target = null
        runCatching { audioRecorder.close() }
        hideBubble()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun toast(text: String) {
        if (!destroyed) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
