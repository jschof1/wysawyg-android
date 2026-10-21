package com.cragnet.wysawyg

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor

/** Private crash boundary: model, pool, scratch and decode never leave this single owner thread.
 * No Recoveries, preferences, HTTP, or main-process application coordinator here. */
class PhononService : Service() {
    // A service may be recreated while a timed-out decode is still finishing. Keep one
    // process-wide owner so the new instance cannot open a second pool concurrently.
    private object NativeOwner {
        val thread = HandlerThread("PhononOwner").apply { start() }
        val handler = Handler(thread.looper)
        var engine = 0L
        fun close() {
            if (engine != 0L) {
                PhononNative.close(engine)
                engine = 0L
            }
        }
    }
    private val owner get() = NativeOwner.handler
    private val lifecycleLock = Any()
    @Volatile private var closing = false
    private fun closeEngine() = NativeOwner.close()
    private val idle = Runnable { closeEngine() }
    private val service = object : IPhononService.Stub() {
        override fun transcribe(requestId: Long, wav: ParcelFileDescriptor?, callback: IPhononCallback?) = synchronized(lifecycleLock) {
            if (wav == null || callback == null) { wav?.close(); return@synchronized }
            if (closing || !owner.post {
                owner.removeCallbacks(idle)
                try {
                    check(wav.statSize in 44..PrivateFiles.MAX_WAV_BYTES.toLong()) { "Invalid recording size" }
                    if (NativeOwner.engine == 0L) NativeOwner.engine = PhononNative.open(assets)
                    check(NativeOwner.engine != 0L) { "Bundled speech model could not be opened" }
                    val text = PhononNative.transcribe(NativeOwner.engine, wav.fd)
                    check(text.size <= PrivateFiles.MAX_TEXT_BYTES) { "Transcription result is too large" }
                    PrivateFiles.descriptor(this@PhononService, text).use {
                        callback.onResult(requestId, it, null)
                    }
                } catch (e: Exception) {
                    runCatching { callback.onResult(requestId, null, e.message ?: "On-device transcription failed") }
                } catch (e: LinkageError) {
                    runCatching { callback.onResult(requestId, null, "Bundled native engine unavailable") }
                } finally {
                    wav.close()
                    if (!closing) owner.postDelayed(idle, 30_000)
                }
            }) {
                wav.close()
                runCatching { callback.onResult(requestId, null, "On-device engine is closing. Tap Record to try again.") }
            }
        }
    }
    override fun onBind(intent: Intent): IBinder = service
    override fun onDestroy() {
        synchronized(lifecycleLock) {
            closing = true
            owner.removeCallbacks(idle)
            owner.post { closeEngine() }
        }
        super.onDestroy()
    }
}
