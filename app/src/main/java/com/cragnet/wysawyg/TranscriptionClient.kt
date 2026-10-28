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

enum class TranscriptionMode {
    OFFLINE, CLOUD;
    companion object {
        const val KEY = "transcription_mode"
        fun fromStored(value: String?): TranscriptionMode = if (value == CLOUD.name) CLOUD else OFFLINE
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
class CaptureAdmission(
    private val prepare: (() -> TranscriptionBackend)? = null,
    private val canCapture: () -> Boolean
) {
    private var current: Reservation? = null
    @Synchronized fun acquire(): Reservation? {
        if (current != null || !canCapture()) return null
        return Reservation(prepare?.invoke()).also { current = it }
    }
    inner class Reservation internal constructor(internal val backend: TranscriptionBackend?) {
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
        /** Shapes the raw transcript for its destination. Failures keep the raw transcript. */
        interpret: suspend (String) -> String = { it },
        onTranscribed: (String) -> Unit = {}
    ): String = suspendCancellableCoroutine { waiter ->
        try {
            reservation.accept()
        } catch (e: Exception) {
            if (waiter.isActive) waiter.resumeWithException(e)
            return@suspendCancellableCoroutine
        }
        try {
            val backend = reservation.backend ?: router.select(mode())
            val work = scope.launch {
                try {
                    val raw = serial.withLock { backend.transcribe(wavBytes) }
                    val text = try { interpret(raw) } catch (e: Exception) { raw }
                    onTranscribed(text)
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
