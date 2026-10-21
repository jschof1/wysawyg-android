package com.cragnet.wysawyg

import android.content.Context

/** Main-process, application-lifetime coordinator. Never used by PhononService. */
object Transcriptions {
    @Volatile private var client: TranscriptionClient? = null
    @Synchronized fun get(context: Context): TranscriptionClient = client ?: run {
        val app = context.applicationContext
        val recovery = Recoveries.get(app)
        val offline by lazy { PhononClient(app) }
        val cloud by lazy { OllamaClient(app) }
        TranscriptionClient(
            CaptureAdmission { recovery.snapshot().canCapture },
            { TranscriptionMode.fromStored(app.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(TranscriptionMode.KEY, null)) },
            TranscriptionRouter({ offline }, { TranscriptionBackend { cloud.transcribe(it) } })
        ).also { client = it }
    }
}
