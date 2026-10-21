package com.cragnet.wysawyg

import android.content.res.AssetManager

/** Loaded only inside :phonon, on its owner thread. Probe library is generic ARMv8-A. */
internal object PhononNative {
    init {
        System.loadLibrary("phonon_probe")
        System.loadLibrary(if (hasDotProduct()) "phonon_dotprod" else "phonon_baseline")
    }
    private external fun hasDotProduct(): Boolean
    external fun open(assets: AssetManager): Long
    external fun transcribe(engine: Long, wavFd: Int): ByteArray
    external fun close(engine: Long)
}
