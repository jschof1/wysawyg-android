package com.cragnet.wysawyg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.IBinder
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {
    private enum class State { IDLE, RECORDING, BUSY }
    private lateinit var windowManager: WindowManager
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var client: OllamaClient
    private var bubble: View? = null
    private var button: ImageButton? = null
    private var progress: ProgressBar? = null
    private lateinit var parameters: WindowManager.LayoutParams
    private var state = State.IDLE
    private var editorVisible = false
    private var keyboardBounds = Rect()
    private var target: TextInjectorService.Target? = null
    private var movedByUser = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val CHANNEL_ID = "wysawyg_overlay"
        private const val STOP = "com.cragnet.wysawyg.STOP_DICTATION"
        private var instance: OverlayService? = null
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Dictation", NotificationManager.IMPORTANCE_LOW)
        )
        val size = dp(52)
        parameters = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
        WysawygLogger.i("Floating dictation ready${if (intent == null) " after process restart" else ""}")
        return START_STICKY
    }

    private fun showForeground(recording: Boolean) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            Build.VERSION.SDK_INT >= 30 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> 0
        }
        ServiceCompat.startForeground(this, 1, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WYSAWYG dictation")
            .setContentText(if (recording) "Recording. Tap the microphone to finish." else "Ready when your keyboard is open")
            .setSmallIcon(R.drawable.ic_dictation_mic)
            .setContentIntent(open).setOngoing(true)
            .addAction(0, "Pause", stop).build(), type)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onEditorChanged(visible: Boolean, keyboard: Rect) {
        editorVisible = visible
        keyboardBounds = Rect(keyboard)
        if (!visible) {
            hideBubble()
            if (state == State.RECORDING) cancelRecording()
        } else {
            showBubble()
        }
    }

    private fun showBubble() {
        if (bubble != null) {
            if (!movedByUser) {
                positionAboveKeyboard()
            } else {
                parameters.x = parameters.x.coerceIn(0, (resources.displayMetrics.widthPixels - parameters.width).coerceAtLeast(0))
                parameters.y = parameters.y.coerceIn(0, (keyboardBounds.top - parameters.height - dp(24)).coerceAtLeast(0))
                bubble?.let { windowManager.updateViewLayout(it, parameters) }
            }
            return
        }
        try {
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_button, null)
            button = view.findViewById(R.id.dictationButton)
            progress = view.findViewById(R.id.dictationProgress)
            if (!movedByUser) positionAboveKeyboard(update = false)
            windowManager.addView(view, parameters)
            bubble = view
            button?.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                when (state) {
                    State.IDLE -> startRecording()
                    State.RECORDING -> stopAndTranscribe()
                    State.BUSY -> Unit
                }
            }
            button?.setOnLongClickListener {
                if (state == State.RECORDING) cancelRecording()
                true
            }
            button?.let { makeDraggable(it) }
            renderState()
            WysawygLogger.i("Dictation button shown")
        } catch (e: Exception) {
            WysawygLogger.e("Unable to show dictation button", e)
            hideBubble()
        }
    }

    private fun positionAboveKeyboard(update: Boolean = true) {
        val width = resources.displayMetrics.widthPixels
        parameters.x = width - parameters.width - dp(16)
        // TOP coordinates already exclude the status bar; avoid the keyboard toolbar.
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusBarId != 0) resources.getDimensionPixelSize(statusBarId) else dp(24)
        parameters.y = (keyboardBounds.top - parameters.height - dp(12) - statusBar).coerceAtLeast(dp(12))
        if (update) bubble?.let { windowManager.updateViewLayout(it, parameters) }
    }

    private fun hideBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        button = null
        progress = null
    }

    private fun renderState() {
        button?.apply {
            backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (state == State.RECORDING) "#E54B5F" else "#465BE8"))
            setImageResource(if (state == State.RECORDING) R.drawable.ic_dictation_stop else R.drawable.ic_dictation_mic)
            imageAlpha = if (state == State.BUSY) 0 else 255
            contentDescription = when (state) {
                State.IDLE -> "Start dictation"
                State.RECORDING -> "Finish dictation. Hold to cancel"
                State.BUSY -> "Transcribing"
            }
        }
        progress?.visibility = if (state == State.BUSY) View.VISIBLE else View.GONE
    }

    private fun startRecording() {
        state = State.BUSY
        renderState()
        scope.launch {
            try {
                target = TextInjectorService.instance?.captureTargetForDictation()
                if (target == null || !editorVisible) {
                    target = null
                    state = State.IDLE
                    renderState()
                    Toast.makeText(this@OverlayService, "Tap the text box again before dictating.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                showForeground(recording = true)
                audioRecorder.start()
                state = State.RECORDING
                renderState()
                WysawygLogger.i("Overlay recording started")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                target = null
                state = State.IDLE
                renderState()
                showForeground(recording = false)
                WysawygLogger.e("Recording failed", e)
                Toast.makeText(this@OverlayService, "Could not use the microphone. Check microphone permission.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cancelRecording() {
        target = null
        state = State.BUSY
        renderState()
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { audioRecorder.stop() } }
            showForeground(recording = false)
            state = State.IDLE
            renderState()
        }
    }

    private fun stopAndTranscribe() {
        val destination = target ?: return
        state = State.BUSY
        renderState()
        scope.launch {
            try {
                val audio = withContext(Dispatchers.IO) { audioRecorder.stop() }
                showForeground(recording = false)
                val text = client.transcribe(audio)
                if (text.isNotBlank()) {
                    val result = TextInjectorService.instance?.insertForDictation(destination, text)
                    when (result) {
                        TextInjectorService.InsertResult.INSERTED -> WysawygLogger.i("Dictation inserted directly")
                        TextInjectorService.InsertResult.UNSUPPORTED -> Toast.makeText(this@OverlayService,
                            "This field does not support direct dictation. Try the WYSAWYG keyboard.", Toast.LENGTH_LONG).show()
                        else -> Toast.makeText(this@OverlayService,
                            "The text field changed. Dictation was not inserted.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@OverlayService, "No speech detected", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WysawygLogger.e("Transcription failed", e)
                Toast.makeText(this@OverlayService, "Transcription failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (instance === this@OverlayService) showForeground(recording = false)
                target = null
                state = State.IDLE
                renderState()
            }
        }
    }

    private fun makeDraggable(view: View) {
        var x = 0
        var y = 0
        var downX = 0f
        var downY = 0f
        var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x = parameters.x; y = parameters.y
                    downX = event.rawX; downY = event.rawY
                    dragging = false
                    false // Let the button handle clicks and hold-to-cancel.
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) dragging = true
                    if (dragging) {
                        view.cancelLongPress()
                        view.isPressed = false
                        movedByUser = true
                        parameters.x = (x + dx.toInt()).coerceIn(0, (resources.displayMetrics.widthPixels - parameters.width).coerceAtLeast(0))
                        parameters.y = (y + dy.toInt()).coerceIn(0, (keyboardBounds.top - parameters.height - dp(24)).coerceAtLeast(0))
                        bubble?.let { windowManager.updateViewLayout(it, parameters) }
                    }
                    dragging
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) { view.isPressed = false; true } else false
                }
                MotionEvent.ACTION_CANCEL -> { view.isPressed = false; false }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        instance = null
        runCatching { audioRecorder.close() }
        scope.cancel()
        hideBubble()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
