package com.cragnet.wysawyg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Unknown, fresh and legacy settings always opt into local processing. */
enum class TranscriptionMode {
    OFFLINE, CLOUD;
    companion object {
        const val KEY = "transcription_mode"
        fun fromStored(value: String?): TranscriptionMode = if (value == CLOUD.name) CLOUD else OFFLINE
        fun fromImport(version: Int, value: String?): TranscriptionMode =
            if (version == 2) fromStored(value) else OFFLINE
    }
}

fun interface TranscriptionBackend {
    suspend fun transcribe(wavBytes: ByteArray): String
}

/** Only the selected factory is invoked. OFFLINE never even constructs the HTTP client. */
class TranscriptionRouter(
    private val offline: () -> TranscriptionBackend,
    private val cloud: () -> TranscriptionBackend
) {
    fun select(mode: TranscriptionMode): TranscriptionBackend = when (mode) {
        TranscriptionMode.OFFLINE -> offline()
        TranscriptionMode.CLOUD -> cloud()
    }
}

/** One app-wide reservation from before microphone acquisition through staging/failure.
 * This also prevents two editors competing for the microphone or the fifth recovery slot. */
class CaptureAdmission(private val canCapture: () -> Boolean) {
    private var current: Reservation? = null
    @Synchronized fun acquire(): Reservation? {
        if (current != null || !canCapture()) return null
        return Reservation().also { current = it }
    }
    inner class Reservation internal constructor() {
        private var accepted = false
        fun accept() = synchronized(this@CaptureAdmission) {
            check(current === this && !accepted) { "Capture reservation expired" }
            accepted = true
        }
        fun cancelCapture() = synchronized(this@CaptureAdmission) {
            if (current === this && !accepted) current = null
        }
        internal fun complete() = synchronized(this@CaptureAdmission) {
            if (current === this) current = null
        }
    }
}

class TranscriptionClient(
    val admission: CaptureAdmission,
    private val mode: () -> TranscriptionMode,
    private val router: TranscriptionRouter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val serial = Mutex()

    suspend fun transcribe(
        wavBytes: ByteArray,
        reservation: CaptureAdmission.Reservation = admission.acquire()
            ?: error("Finish the current dictation or resolve recovered transcripts first"),
        onTranscribed: (String) -> Unit = {}
    ): String = suspendCancellableCoroutine { waiter ->
        // Acceptance transfers ownership away from the UI. Cancellation only cancels its waiter.
        try {
            reservation.accept()
        } catch (e: Exception) {
            // A stale or reused token must not release another accepted request.
            if (waiter.isActive) waiter.resumeWithException(e)
            return@suspendCancellableCoroutine
        }
        try {
            val selectedMode = mode()
            val work = scope.launch {
                try {
                    val text = serial.withLock { router.select(selectedMode).transcribe(wavBytes) }
                    onTranscribed(text) // Always stage exactly once, even if the UI has gone away.
                    reservation.complete()
                    if (waiter.isActive) waiter.resume(text)
                } catch (e: Exception) {
                    reservation.complete()
                    if (waiter.isActive) waiter.resumeWithException(e)
                }
            }
            work.invokeOnCompletion { failure ->
                // Also covers a scope stopped before the dispatched body could start.
                if (failure != null) {
                    reservation.complete()
                    if (waiter.isActive) waiter.resumeWithException(failure)
                }
            }
        } catch (e: Exception) {
            reservation.complete()
            if (waiter.isActive) waiter.resumeWithException(e)
        }
    }
}
