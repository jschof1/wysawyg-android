package com.cragnet.wysawyg

/** Backups omit API keys and consent. Imports always select on-device transcription. */
object SettingsFormat {
    const val VERSION_KEY = "formatVersion"
    const val VERSION = 2
    val credentialKeys = listOf("alarma_url", "api_key", "model", "system_prompt")
    /** Writing-style choices travel only when set, as String or Boolean like their preferences. */
    private val styleKeys get() = StyleSettings.BOOLEAN_KEYS + StyleSettings.STYLE_KEYS + RecordingIndicator.KEY
    fun export(values: Map<String, *>): Map<String, Any> = buildMap {
        put(VERSION_KEY, VERSION)
        put(TranscriptionMode.KEY, TranscriptionMode.fromStored(values[TranscriptionMode.KEY] as? String).name)
        credentialKeys.filterNot { it == "api_key" }.forEach { key -> (values[key] as? String)?.let { put(key, it) } }
        styleKeys.forEach { key -> values[key]?.takeIf { StyleSettings.valid(key, it) }?.let { put(key, it) } }
    }
    fun import(values: Map<String, *>): Map<String, Any> = buildMap {
        put(TranscriptionMode.KEY, TranscriptionMode.OFFLINE.name)
        credentialKeys.forEach { key -> (values[key] as? String)?.let { put(key, it) } }
        styleKeys.forEach { key -> values[key]?.takeIf { StyleSettings.valid(key, it) }?.let { put(key, it) } }
    }
}
