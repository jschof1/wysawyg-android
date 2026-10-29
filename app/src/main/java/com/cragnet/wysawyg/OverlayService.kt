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
    private lateinit var windowManager: WindowManager
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var client: TranscriptionClient
    private var capture: CaptureAdmission.Reservation? = null
    private lateinit var recoveries: RecoveryStore
    private lateinit var parameters: WindowManager.LayoutParams
    private var bubble: View? = null
    private var session = RecordingSession()
    private val state: RecordingPhase get() = session.phase
    private lateinit var audioStore: DurablePcmStore
    private var pendingAudio: DurablePcmStore.Capture? = null
    private var destination: RecordingDestination? = null
    private var editorVisible = false
    private var keyboardBounds = Rect()
    private var movedByUser = false
    private var destroyed = false
    private var recoveryIndex = 0
    private var recoveryAction = false
    private var recoveryVisible = false
    private var recoveryCollapsed = false
    private var unconfirmedRecoveryId: String? = null
    private var startedAt = 0L
    private var operation: Job? = null
    private var meter: Job? = null
    /** Chosen in settings; read when each recording starts. */
    private var indicator = RecordingIndicator.DETAILED
    private var pulse: android.animation.ObjectAnimator? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val CHANNEL_ID = "wysawyg_overlay"
        const val STOP = "com.cragnet.wysawyg.STOP_RECORDING"
        const val RETRY = "com.cragnet.wysawyg.RETRY_RECORDING_OFFLINE"
        const val DISCARD = "com.cragnet.wysawyg.DISCARD_RECORDING"
        private const val PAUSE = "com.cragnet.wysawyg.PAUSE_DICTATION"
        private const val START_IME = "com.cragnet.wysawyg.START_IME_RECORDING"
        private var imeStart: RecordingDestination? = null

        fun startFromIme(context: Context, destination: RecordingDestination) {
            if (RecordingSessions.state.value.phase != RecordingPhase.IDLE || imeStart != null) return
            imeStart = destination
            try {
                ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java).setAction(START_IME))
            } catch (e: Exception) {
                imeStart = null
                Toast.makeText(context, "Could not start microphone service. Open Speakeasy and check permissions.", Toast.LENGTH_LONG).show()
            }
        }
        private var nextStartAttemptAt = 0L
        private var instance: OverlayService? = null
            private set
        fun isRunning(): Boolean = instance != null
        fun isEnabled(context: Context): Boolean = context
            .getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(MainActivity.PREF_OVERLAY_ENABLED, false)

        fun hasPermissions(context: Context): Boolean = Settings.canDrawOverlays(context) &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        @Synchronized fun ensureRunning(context: Context): Boolean {
            if (!isEnabled(context) || !hasPermissions(context) || !PrivacyConsent.hasAccessibility(context)) return false
            if (isRunning()) return true
            val now = SystemClock.elapsedRealtime()
            if (now < nextStartAttemptAt) return false
            nextStartAttemptAt = now + 5000
            return try {
                ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
                true
            } catch (e: Exception) {
                WysawygLogger.e("Unable to resume floating dictation", e)
                false
            }
        }

        fun pause(context: Context) {
            nextStartAttemptAt = 0L
            context.getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(MainActivity.PREF_OVERLAY_ENABLED, false).apply()
            instance?.apply {
                destination = null
                if (session.keepsIndicator) {
                    toast("New starts paused. Active recording continues until Stop; saved audio is kept.")
                    refreshSurface()
                } else stopSelf()
            }
        }

        fun updateEditor(visible: Boolean, keyboard: Rect) {
            instance?.onEditorChanged(visible, keyboard)
        }

        internal fun refreshSavedText() { instance?.refreshSurface() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        WysawygLogger.init(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioRecorder = AudioRecorder(this)
        client = Transcriptions.get(this)
        recoveries = Recoveries.get(this)
        audioStore = RecordingSessions.store(this)
        pendingAudio = audioStore.pending()
        session = pendingAudio?.let { RecordingSession(RecordingPhase.INTERRUPTED, it.id,
            message = "Recording interrupted. Audio kept; retry on device or discard.") } ?: RecordingSession()
        RecordingSessions.update(session)
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
        // Restarts can expose pending audio, but never reacquire a microphone automatically.
        showForeground(state == RecordingPhase.CAPTURING || audioRecorder.recording)
        val matchesSession = intent?.getStringExtra("sessionId") == session.id
        when (intent?.action) {
            STOP -> if (matchesSession) stopAndTranscribe()
            RETRY -> if (matchesSession) retryAudio()
            DISCARD -> if (matchesSession) discardAudio()
            PAUSE -> pause(this)
            START_IME -> {
                val requested = imeStart
                imeStart = null
                if (requested != null) startRecording(requested)
            }
        }
        TextInjectorService.instance?.refreshEditor()
        refreshSurface()
        if (state == RecordingPhase.IDLE && (!isEnabled(this) || !hasPermissions(this) ||
                !PrivacyConsent.hasAccessibility(this))) stopSelf()
        return START_NOT_STICKY
    }

    private fun showForeground(recording: Boolean) {
        if (destroyed) return
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val action = if (recording) STOP else PAUSE
        val stop = PendingIntent.getService(this, if (recording) 1 else 2,
            Intent(this, OverlayService::class.java).setAction(action).putExtra("sessionId", session.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            Build.VERSION.SDK_INT >= 30 -> if (recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            else -> 0
        }
        ServiceCompat.startForeground(this, 1, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speakeasy dictation")
            .setContentText(when (state) {
                RecordingPhase.CAPTURING -> "Recording continues across screens. Tap Stop to transcribe."
                RecordingPhase.TRANSCRIBING -> "Transcribing; captured audio is kept until text is saved."
                RecordingPhase.INTERRUPTED -> session.message
                else -> "Ready when your keyboard is open"
            })
            .setSmallIcon(R.drawable.ic_dictation_mic)
            .setContentIntent(open).setOngoing(true)
            .apply {
                if (recording) addAction(0, "Stop", stop)
                else if (state == RecordingPhase.IDLE) addAction(0, "Pause new recordings", stop)
                if (state == RecordingPhase.INTERRUPTED) addAction(0, "Recover", open)
            }.build(), type)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onEditorChanged(visible: Boolean, keyboard: Rect) {
        if (destroyed) return
        editorVisible = visible
        keyboardBounds = Rect(keyboard)
        session = session.editorChanged()
        refreshSurface()
    }

    private fun refreshSurface() {
        if (destroyed) return
        val saved = recoveries.snapshot()
        val hasSavedText = saved.items.isNotEmpty() || saved.unreadable || saved.writeFailed
        if (Settings.canDrawOverlays(this) && (session.keepsIndicator ||
            ((editorVisible || hasSavedText) && isEnabled(this) && PrivacyConsent.hasAccessibility(this)))) showBubble()
        else hideBubble()
    }

    private fun setSession(value: RecordingSession) {
        session = value
        RecordingSessions.update(value)
        showForeground(state == RecordingPhase.CAPTURING || audioRecorder.recording)
        refreshSurface()
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
                    when {
                        state == RecordingPhase.IDLE -> startRecording()
                        state == RecordingPhase.CAPTURING && indicator == RecordingIndicator.MINIMAL -> stopAndTranscribe()
                    }
                }
                // Holding Stop keeps audio for later, without sending it for transcription.
                setOnLongClickListener {
                    if (state == RecordingPhase.CAPTURING && indicator == RecordingIndicator.MINIMAL) {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        stopAndKeep()
                        true
                    } else false
                }
                makeDraggable(this) {
                    (state == RecordingPhase.IDLE || state == RecordingPhase.CAPTURING && indicator == RecordingIndicator.MINIMAL) && !recoveryVisible
                }
            }
            view<Button>(R.id.retryAudio)?.setOnClickListener { retryAudio() }
            view<Button>(R.id.manageAudio)?.setOnClickListener {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            view<Button>(R.id.collapseRecovery)?.setOnClickListener { recoveryCollapsed = true; renderState() }
            view<Button>(R.id.showRecovery)?.setOnClickListener { recoveryCollapsed = false; renderState() }
            view<Button>(R.id.stopRecording)?.apply {
                setOnClickListener { stopAndTranscribe() }
                setOnLongClickListener { if (state == RecordingPhase.CAPTURING) stopAndKeep(); true }
            }
            view<Button>(R.id.cancelRecording)?.setOnClickListener { stopAndKeep() }
            view<View>(R.id.compactStop)?.apply {
                setOnClickListener { stopAndTranscribe() }
                setOnLongClickListener { if (state == RecordingPhase.CAPTURING) stopAndKeep(); true }
            }
            view<View>(R.id.compactPanel)?.let { makeDraggable(it) }
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
        val oldBounds = listOf(parameters.x, parameters.y, parameters.width, parameters.height)
        val screen = resources.displayMetrics
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusBarId != 0) resources.getDimensionPixelSize(statusBarId) else dp(24)
        val bottom = if (editorVisible && keyboardBounds.top > statusBar) keyboardBounds.top else screen.heightPixels - dp(48)
        val available = (bottom - statusBar - dp(12)).coerceAtLeast(1)
        val width = when {
            state == RecordingPhase.CAPTURING && indicator == RecordingIndicator.MINIMAL -> 64
            state == RecordingPhase.CAPTURING && indicator == RecordingIndicator.COMPACT -> 236
            state == RecordingPhase.CAPTURING || state == RecordingPhase.INTERRUPTED || recoveryVisible -> 320
            view<View>(R.id.showRecovery)?.visibility == View.VISIBLE -> 256
            else -> 64 // 56dp bubble plus its 4dp padding on each side
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
        if (update && oldBounds != listOf(parameters.x, parameters.y, parameters.width, parameters.height)) {
            windowManager.updateViewLayout(panel, parameters)
        }
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
        view<View>(R.id.audioRecoveryPanel)?.visibility = if (state == RecordingPhase.INTERRUPTED) View.VISIBLE else View.GONE
        view<TextView>(R.id.audioRecoveryStatus)?.text = session.message
        view<View>(R.id.retryAudio)?.isEnabled = !client.admission.isHeld && !audioRecorder.hasWriter
        view<View>(R.id.manageAudio)?.isEnabled = !client.admission.isHeld && !audioRecorder.hasWriter
        val item = selectedRecovery()
        val hasRecovery = item != null || snapshot.unreadable || snapshot.writeFailed
        recoveryVisible = state == RecordingPhase.IDLE && hasRecovery && !recoveryCollapsed
        val recording = state == RecordingPhase.CAPTURING
        val minimal = recording && indicator == RecordingIndicator.MINIMAL
        view<View>(R.id.idlePanel)?.visibility = if ((recording && !minimal) || recoveryVisible || state == RecordingPhase.INTERRUPTED) View.GONE else View.VISIBLE
        view<View>(R.id.recordingPanel)?.visibility = if (recording && indicator == RecordingIndicator.DETAILED) View.VISIBLE else View.GONE
        view<View>(R.id.compactPanel)?.visibility = if (recording && indicator == RecordingIndicator.COMPACT) View.VISIBLE else View.GONE
        view<View>(R.id.recordingRing)?.visibility = if (minimal) View.VISIBLE else View.INVISIBLE
        view<View>(R.id.recordingDot)?.visibility = if (minimal) View.VISIBLE else View.GONE
        renderPulse(recording)
        view<View>(R.id.recoveryPanel)?.visibility = if (recoveryVisible) View.VISIBLE else View.GONE
        view<ImageButton>(R.id.dictationButton)?.apply {
            imageAlpha = if (state == RecordingPhase.IDLE || minimal) 255 else 0
            isEnabled = session.canStart(editorVisible && isEnabled(this@OverlayService), snapshot.canCapture && !recoveryAction) || minimal
            setImageResource(if (minimal) R.drawable.ic_dictation_stop else R.drawable.se_mic)
            setBackgroundResource(if (minimal) R.drawable.dictation_circle_recording else R.drawable.dictation_circle)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(if (minimal) R.color.se_mint else R.color.se_on_mint))
            scaleX = if (minimal) 0.72f else 1f
            scaleY = scaleX
            contentDescription = when {
                minimal -> getString(R.string.finish_dictation)
                state == RecordingPhase.IDLE -> "Start dictation"
                state == RecordingPhase.STARTING -> "Preparing microphone"
                else -> "Transcribing"
            }
        }
        view<Button>(R.id.showRecovery)?.apply {
            visibility = if (state == RecordingPhase.IDLE && hasRecovery && recoveryCollapsed) View.VISIBLE else View.GONE
            text = if (snapshot.items.isEmpty()) "Recovery needs attention" else getString(R.string.saved_text_count, snapshot.items.size)
            isEnabled = !recoveryAction
        }
        view<Button>(R.id.collapseRecovery)?.isEnabled = !recoveryAction
        view<ProgressBar>(R.id.dictationProgress)?.visibility = if (state == RecordingPhase.STARTING || state == RecordingPhase.TRANSCRIBING) View.VISIBLE else View.GONE
        view<TextView>(R.id.recoveryTitle)?.text = if (item == null) "Recovery storage needs attention" else "Saved text ${recoveryIndex + 1} of ${snapshot.items.size}"
        view<TextView>(R.id.recoveryText)?.let { if (it.text.toString() != item?.text.orEmpty()) it.text = item?.text.orEmpty() }
        view<TextView>(R.id.recoveryStatus)?.text = when {
            item != null && item.id == unconfirmedRecoveryId -> "Text was sent to the field. Check it before inserting again; your saved copy is kept."
            item?.id in snapshot.unsavedIds -> "Not saved. Kept in memory only; Copy before closing the app. New recording paused."
            snapshot.unsavedIds.isNotEmpty() -> "A transcript is not saved. Use the arrows to find it and Copy before closing. Recording paused."
            snapshot.unreadable -> "Saved recovery file is unreadable. Discard unreadable data to resume."
            snapshot.writeFailed -> "Could not update storage. An inserted transcript may reappear. Check it before inserting again."
            snapshot.items.size >= RecoveryStore.CAPACITY -> "5 saved; recording paused until you insert or discard one. Expires after 24 hours."
            else -> "Saved privately for 24 hours, even with the keyboard closed. Tap a text box, then Insert here. Your clipboard stays unchanged."
        }
        view<View>(R.id.recoveryScroll)?.visibility = if (item != null) View.VISIBLE else View.GONE
        view<View>(R.id.recoveryActions)?.visibility = if (item != null) View.VISIBLE else View.GONE
        view<Button>(R.id.previousRecovery)?.isEnabled = !recoveryAction && recoveryIndex > 0
        view<Button>(R.id.nextRecovery)?.isEnabled = !recoveryAction && recoveryIndex < snapshot.items.lastIndex
        view<Button>(R.id.discardRecovery)?.apply {
            text = if (item == null && snapshot.unreadable) "Discard unreadable data" else "Discard"
            isEnabled = !recoveryAction && (item != null || snapshot.unreadable)
        }
        view<Button>(R.id.insertRecovery)?.apply {
            isEnabled = !recoveryAction
            text = if (item != null && item.id == unconfirmedRecoveryId) "Insert again" else "Insert here"
        }
        view<Button>(R.id.copyRecovery)?.isEnabled = !recoveryAction
        view<Button>(R.id.retryRecoverySave)?.apply {
            visibility = if (snapshot.writeFailed && !snapshot.unreadable) View.VISIBLE else View.GONE
            isEnabled = !recoveryAction
        }
        if (updateLayout && bubble != null) layoutPanel()
    }

    private fun startRecording(requested: RecordingDestination? = null) {
        if (state != RecordingPhase.IDLE || recoveryAction || audioStore.pending() != null || !recoveries.snapshot().canCapture) return
        val notificationsVisible = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        if (!Settings.canDrawOverlays(this) && !notificationsVisible) {
            toast("Allow Speakeasy notifications before recording without the floating indicator.")
            return
        }
        // Overlay starts require a live eligible editor. IME starts carry their own eligibility token.
        if (requested == null && (!editorVisible || !isEnabled(this))) return
        capture = try { client.admission.acquire() } catch (error: Exception) {
            toast(error.message ?: getString(R.string.cloud_setup_needed)); return
        } ?: run { toast("Finish the current dictation or resolve recovered transcripts first."); return }
        setSession(RecordingSession(RecordingPhase.STARTING))
        operation = scope.launch {
            try {
                val selected = requested ?: TextInjectorService.instance?.captureTargetForDictation()?.let { target ->
                    RecordingDestination(target.dictationContext, {
                        TextInjectorService.instance?.captureTarget()?.let {
                            it.generation == target.generation && it.node == target.node
                        } == true
                    }) { text ->
                        TextInjectorService.instance?.insertForDictation(target, text) == TextInjectorService.InsertResult.INSERTED
                    }
                }
                if (requested != null) check(requested.isValid()) { "Tap a non-password text box before dictating." }
                destination = selected?.takeIf { it.isValid() }
                pendingAudio = audioStore.create()
                startedAt = SystemClock.elapsedRealtime()
                // Promote before acquiring AudioRecord; applies equally to the optional IME.
                session = RecordingSession(RecordingPhase.CAPTURING, pendingAudio!!.id, startedAt)
                showForeground(recording = true)
                audioRecorder.start(audioStore, pendingAudio!!)
                indicator = RecordingIndicator.load(getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE))
                setSession(session)
                meter = scope.launch {
                    while (isActive && state == RecordingPhase.CAPTURING) {
                        if (!audioRecorder.recording) {
                            interruptRecording("Microphone interrupted. Captured audio kept; retry on device or discard.")
                            break
                        }
                        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
                        val time = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
                        view<TextView>(R.id.recordingTimer)?.text = time
                        view<TextView>(R.id.compactTimer)?.text = time
                        val level = audioRecorder.level
                        view<ProgressBar>(R.id.recordingLevel)?.progress = (level * 100).toInt()
                        view<ProgressBar>(R.id.compactLevel)?.progress = (level * 100).toInt()
                        view<View>(R.id.recordingRing)?.apply {
                            val scale = 0.9f + 0.1f * level.coerceIn(0f, 1f)
                            animate().scaleX(scale).scaleY(scale).alpha(0.6f + 0.4f * level).setDuration(90).start()
                        }
                        delay(100)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                runCatching { audioRecorder.close() }
                capture?.cancelCapture(); capture = null; destination = null
                pendingAudio = audioStore.pending()
                setSession(pendingAudio?.let { RecordingSession(RecordingPhase.INTERRUPTED, it.id,
                    message = "Recording stopped: ${e.message}. Audio kept.") } ?: RecordingSession())
                toast(e.message ?: "Could not start recording")
            }
        }
    }

    private fun stopMeter() {
        meter?.cancel()
        meter = null
        view<ProgressBar>(R.id.recordingLevel)?.progress = 0
        view<ProgressBar>(R.id.compactLevel)?.progress = 0
    }

    /** A slow blink on the red recording dots; follows the system animation setting. */
    private fun renderPulse(recording: Boolean) {
        if (!recording) {
            pulse?.cancel()
            pulse = null
            listOf(R.id.recordingDot, R.id.compactDot).forEach { view<View>(it)?.alpha = 1f }
            return
        }
        if (pulse != null) return
        val dot = view<View>(if (indicator == RecordingIndicator.COMPACT) R.id.compactDot else R.id.recordingDot) ?: return
        pulse = android.animation.ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 700
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopAndKeep() = interruptRecording("Recording stopped and kept. Retry on device or discard.")

    private fun interruptRecording(reason: String) {
        if (state != RecordingPhase.CAPTURING) return
        stopMeter()
        destination = null
        setSession(session.stop()) // closes the command gate before asynchronous stop
        operation = scope.launch {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { audioRecorder.stop() } }
            capture?.cancelCapture(); capture = null
            setSession(session.interrupted(reason))
        }
    }

    private fun stopAndTranscribe() {
        if (!session.canStop) return // repeated notification/button taps are idempotent
        val audio = pendingAudio ?: return
        val reservation = capture ?: return
        val originalDestination = destination
        stopMeter()
        setSession(session.stop())
        operation = scope.launch {
            try {
                withContext(Dispatchers.IO) { audioRecorder.stop() }
                showForeground(recording = false)
                if (audioRecorder.recordingError != null) {
                    error("Microphone was interrupted. Retry the captured audio on device.")
                }
                transcribe(audio, reservation, originalDestination)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failedAudio(audio, e) }
            finally { reservation.cancelCapture(); capture = null; if (!destroyed) renderState() }
        }
    }

    private fun retryAudio() {
        if (state != RecordingPhase.INTERRUPTED || client.admission.isHeld || audioRecorder.hasWriter) return
        val audio = pendingAudio ?: return
        if (audioStore.pending()?.id != audio.id) {
            pendingAudio = null
            setSession(RecordingSession())
            return
        }
        val reservation = client.admission.acquire { PhononClient(applicationContext) } ?: run {
            toast("Resolve saved transcripts before retrying this recording."); return
        }
        destination = null
        setSession(session.copy(phase = RecordingPhase.TRANSCRIBING))
        operation = scope.launch {
            try { transcribe(audio, reservation, null) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failedAudio(audio, e) }
            finally { reservation.cancelCapture() }
        }
    }

    private suspend fun transcribe(audio: DurablePcmStore.Capture, reservation: CaptureAdmission.Reservation,
        originalDestination: RecordingDestination?) {
        var staged: RecoveredTranscript? = null
        val app = applicationContext
        client.transcribeRecording(audio, reservation, { raw ->
            // Recovery has no old editor and deliberately skips context-dependent rewriting.
            if (originalDestination == null) raw else Interpretation.apply(app, raw, originalDestination.context)
        }) { text ->
            staged = recoveries.stageRecording(text, audio, audioStore)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // A response may outlive the old service. Only reconcile that exact interrupted session.
                val current = instance
                if (current == null) {
                    if (RecordingSessions.state.value.id == audio.id &&
                        RecordingSessions.state.value.phase == RecordingPhase.INTERRUPTED) {
                        RecordingSessions.update(RecordingSession())
                    }
                } else if (current.session.id == audio.id && current.state == RecordingPhase.INTERRUPTED) {
                    current.pendingAudio = null
                    current.setSession(RecordingSession())
                    if (!isEnabled(current)) current.stopSelf()
                }
            }
        }
        currentCoroutineContext().ensureActive()
        // A service destroyed while awaiting HTTP cannot insert. Its callback only stages this audio ID.
        if (destroyed || session.id != audio.id) return
        pendingAudio = null
        val item = staged
        val inserted = try {
            item != null && originalDestination != null && originalDestination === destination &&
                originalDestination.isValid() && originalDestination.insert(item.text)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
        if (inserted && item != null) {
            if (!withContext(NonCancellable + Dispatchers.IO) { recoveries.remove(item.id) }) {
                toast("Inserted, but recovery removal was not saved. Check before inserting again.")
            }
        } else {
            recoveryCollapsed = false
            recoveryIndex = recoveries.snapshot().items.indexOfFirst { it.id == item?.id }.coerceAtLeast(0)
            toast("Transcript kept in Speakeasy. Tap a text box, then Insert here.")
        }
        destination = null
        setSession(RecordingSession())
        if (!isEnabled(this)) stopSelf()
    }

    private fun failedAudio(audio: DurablePcmStore.Capture, failure: Exception) {
        if (destroyed || session.id != audio.id) return
        destination = null
        setSession(session.interrupted(failure.message ?: "Transcription interrupted. Audio kept."))
        toast(session.message)
    }

    private fun discardAudio() {
        if (state != RecordingPhase.INTERRUPTED || client.admission.isHeld || audioRecorder.hasWriter) return
        val audio = pendingAudio ?: return
        setSession(session.copy(phase = RecordingPhase.TRANSCRIBING))
        operation = scope.launch {
            if (withContext(Dispatchers.IO) { audioStore.remove(audio) }) {
                pendingAudio = null
                setSession(RecordingSession())
                if (!isEnabled(this@OverlayService)) stopSelf()
            } else setSession(session.interrupted("Could not discard audio. Storage needs attention."))
        }
    }

    private suspend fun completeTranscript(item: RecoveredTranscript) {
        currentCoroutineContext().ensureActive()
        if (destroyed || !isEnabled(this)) return
        val result = TextInjectorService.instance?.insertHere(item.text)
        if (result == TextInjectorService.InsertResult.INSERTED) {
            unconfirmedRecoveryId = null
            if (!withContext(NonCancellable + Dispatchers.IO) { recoveries.remove(item.id) }) {
                toast("Inserted, but recovery removal was not saved. Check before inserting again.")
            }
        } else {
            recoveryCollapsed = false
            recoveryIndex = recoveries.snapshot().items.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
            if (result == TextInjectorService.InsertResult.SENT_UNCONFIRMED) {
                unconfirmedRecoveryId = item.id
                toast("Text sent. Check the field before inserting again; your saved copy is kept.")
            } else toast("Text is safe in Speakeasy. Tap inside a non-password text box, then Insert here.")
        }
        if (!destroyed) refreshSurface()
    }

    private fun insertRecovery() {
        if (recoveryAction || state != RecordingPhase.IDLE) return
        val item = selectedRecovery() ?: return
        if (!recoveries.tryBeginInsertion(item.id)) { refreshSurface(); return }
        recoveryAction = true
        renderState()
        operation = scope.launch {
            try {
                completeTranscript(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WysawygLogger.e("Recovery insertion failed", e)
                toast("Insertion could not be confirmed. Check the field; your saved text is kept.")
            } finally {
                recoveries.finishInsertion(item.id)
                recoveryAction = false
                operation = null
                if (!destroyed) refreshSurface()
            }
        }
    }

    private fun recoveryMutation(action: () -> Boolean) {
        if (recoveryAction || state != RecordingPhase.IDLE) return
        recoveryAction = true
        renderState()
        operation = scope.launch {
            try {
                if (!withContext(Dispatchers.IO) { action() }) toast("Could not update recovery storage. Text retained.")
            } finally {
                recoveryAction = false
                operation = null
                if (!destroyed) refreshSurface()
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
        pulse?.cancel()
        instance = null
        stopMeter()
        operation?.cancel()
        operation = null
        scope.cancel()
        destination = null
        runCatching { audioRecorder.close() }
        capture?.cancelCapture()
        capture = null
        pendingAudio = audioStore.pending()
        RecordingSessions.update(pendingAudio?.let { RecordingSession(RecordingPhase.INTERRUPTED, it.id,
            message = "Recording interrupted. Audio kept; retry on device or discard.") } ?: RecordingSession())
        hideBubble()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun toast(text: String) {
        if (!destroyed) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
