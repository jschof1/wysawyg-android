package com.cragnet.wysawyg

/** Settings JSON v2 adds an explicit opt-in mode. No migration infers cloud from credentials. */
object SettingsFormat {
    const val VERSION_KEY = "formatVersion"
    const val VERSION = 2
    val credentialKeys = listOf("alarma_url", "api_key", "model", "system_prompt")
    /** Writing-style choices travel only when set, as String or Boolean like their preferences. */
    private val styleKeys get() = StyleSettings.BOOLEAN_KEYS + StyleSettings.STYLE_KEYS
    fun export(values: Map<String, *>): Map<String, Any> = buildMap {
        put(VERSION_KEY, VERSION)
        put(TranscriptionMode.KEY, TranscriptionMode.fromStored(values[TranscriptionMode.KEY] as? String).name)
        credentialKeys.forEach { key -> (values[key] as? String)?.let { put(key, it) } }
        styleKeys.forEach { key -> values[key]?.takeIf { StyleSettings.valid(key, it) }?.let { put(key, it) } }
    }
    fun import(values: Map<String, *>): Map<String, Any> = buildMap {
        val version = if (values[VERSION_KEY] == VERSION) VERSION else 1
        put(TranscriptionMode.KEY, TranscriptionMode.fromImport(version, values[TranscriptionMode.KEY] as? String).name)
        credentialKeys.forEach { key -> (values[key] as? String)?.let { put(key, it) } }
        styleKeys.forEach { key -> values[key]?.takeIf { StyleSettings.valid(key, it) }?.let { put(key, it) } }
    }
}
