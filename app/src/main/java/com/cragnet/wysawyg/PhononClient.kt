package com.cragnet.wysawyg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

/** No network dependencies or cloud fallback. Each deliberate request owns exactly one binding. */
class PhononClient(
    private val context: Context,
    /** Instrumentation may kill the private process after acceptance; production leaves this empty. */
    private val onSubmitted: () -> Unit = {}
) : TranscriptionBackend {
    companion object { private val ids = AtomicLong() }
    override suspend fun transcribe(wavBytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(wavBytes.size in 44..PrivateFiles.MAX_WAV_BYTES) { "Recording must be at most 5 minutes" }
        val id = ids.incrementAndGet()
        val result = CompletableDeferred<String>()
        val completion = RequestCompletion<String>(id) { value ->
            value.fold({ result.complete(it) }, { result.completeExceptionally(it) })
        }
        var binder: IBinder? = null
        var connectedOnce = false // never resubmit after Android automatically reconnects a binding
        var closed = false // accessed only by main-thread ServiceConnection/cleanup
        val death = IBinder.DeathRecipient {
            completion.failed(IllegalStateException("On-device transcription stopped. Tap Record to try again."))
        }
        val input = PrivateFiles.descriptor(context, wavBytes)
        val callback = object : IPhononCallback.Stub() {
            override fun onResult(requestId: Long, utf8: ParcelFileDescriptor?, error: String?) {
                try {
                    completion.result(requestId) {
                        check(error == null) { error ?: "On-device transcription failed" }
                        PrivateFiles.readText(requireNotNull(utf8) { "Missing transcription result" })
                    }
                } finally { utf8?.close() }
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (closed || connectedOnce) return
                connectedOnce = true
                binder = service
                try {
                    service.linkToDeath(death, 0)
                    IPhononService.Stub.asInterface(service).transcribe(id, input, callback)
                    onSubmitted()
                } catch (e: Exception) { completion.failed(e) }
                finally { input.close() }
            }
            override fun onServiceDisconnected(name: ComponentName) = death.binderDied()
            override fun onBindingDied(name: ComponentName) = death.binderDied()
            override fun onNullBinding(name: ComponentName) = completion.failed(IllegalStateException("On-device engine unavailable"))
        }
        var bound = false
        try {
            withContext(Dispatchers.Main) {
                bound = context.bindService(Intent(context, PhononService::class.java), connection, Context.BIND_AUTO_CREATE)
            }
            check(bound) { "Could not start on-device transcription" }
            // Includes binding/model load. A wedged worker fails explicitly; never retries or uploads.
            try {
                withTimeout(10 * 60 * 1000L) { result.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // Arbitrate timeout with a full result using the same gate as worker death.
                completion.failed(IllegalStateException("On-device transcription timed out. Tap Record to try again.", e))
                result.await()
            }
        } finally {
            // Ignore callbacks already queued after unbind; close their FDs in the callback's finally.
            completion.failed(IllegalStateException("Transcription connection closed"))
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                closed = true
                binder?.let { runCatching { it.unlinkToDeath(death, 0) } }
                if (bound) runCatching { context.unbindService(connection) }
                runCatching { input.close() }
            }
        }
    }
}
