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
        val router = TranscriptionRouter({ offline }, {
            val configuration = cloud.configuration()
            check(PrivacyConsent.hasCloud(app, configuration.destination)) { app.getString(R.string.cloud_setup_needed) }
            TranscriptionBackend { audio -> cloud.transcribe(audio, configuration) }
        })
        TranscriptionClient(
            CaptureAdmission(prepare = { router.select(effectiveMode(app)) }) { recovery.snapshot().canCapture }
        ).also { client = it }
    }

    fun effectiveMode(context: Context): TranscriptionMode {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (TranscriptionMode.fromStored(prefs.getString(TranscriptionMode.KEY, null)) != TranscriptionMode.CLOUD) {
            return TranscriptionMode.OFFLINE
        }
        val approved = runCatching {
            PrivacyConsent.hasCloud(context, CloudDestination.parse(
                prefs.getString(MainActivity.PREF_ALARMA_URL, "https://ollama.com/api/chat").orEmpty(), BuildConfig.DEBUG))
        }.getOrDefault(false)
        return if (approved) TranscriptionMode.CLOUD else TranscriptionMode.OFFLINE
    }
}
