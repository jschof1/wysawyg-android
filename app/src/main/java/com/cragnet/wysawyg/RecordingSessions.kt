package com.cragnet.wysawyg

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** App-scoped observation and commands. Only OverlayService acquires the microphone. */
object RecordingSessions {
    private val mutable = MutableStateFlow(RecordingSession())
    val state = mutable.asStateFlow()
    private var storage: DurablePcmStore? = null

    @Synchronized fun store(context: Context): DurablePcmStore = storage ?: DurablePcmStore(
        File(context.applicationContext.noBackupFilesDir, "recordings")
    ).also {
        storage = it
        it.pending()?.let { pending ->
            mutable.value = RecordingSession(RecordingPhase.INTERRUPTED, pending.id,
                message = "Recording interrupted. Audio kept; retry on device or discard.")
        }
    }

    internal fun update(value: RecordingSession) { mutable.value = value }

    fun command(context: Context, action: String, id: String? = state.value.id) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java).setAction(action).putExtra("sessionId", id))
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Open Speakeasy to manage this recording. Audio is kept.",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/** A destination may decline insertion at any time; it never owns recording lifetime. */
data class RecordingDestination(
    val context: DictationContext,
    val isValid: () -> Boolean = { true },
    val insert: suspend (String) -> Boolean
)
