package com.cragnet.wysawyg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsFormatTest {
    @Test fun exportsExcludeSecretsAndImportsCannotOptIntoCloud() {
        val stored = mapOf(TranscriptionMode.KEY to "CLOUD", "api_key" to "existing private key")

        val exported = SettingsFormat.export(stored)

        assertFalse(exported.containsKey("api_key"))
        assertEquals(mapOf(TranscriptionMode.KEY to "CLOUD", SettingsFormat.VERSION_KEY to SettingsFormat.VERSION), exported)
        assertEquals(mapOf(TranscriptionMode.KEY to "OFFLINE"), SettingsFormat.import(exported))
        val malicious = stored + mapOf(SettingsFormat.VERSION_KEY to SettingsFormat.VERSION,
            "accessibility_v1" to true, "cloud_origin_v1" to "https://example.test/")
        assertEquals(mapOf(TranscriptionMode.KEY to "OFFLINE", "api_key" to "existing private key"), SettingsFormat.import(malicious))
    }

    @Test fun freshSettingsRoundTripDoesNotCreateCredentials() {
        val imported = SettingsFormat.import(SettingsFormat.export(emptyMap<String, Any>()))

        assertEquals(mapOf(TranscriptionMode.KEY to "OFFLINE"), imported)
    }

    @Test fun explicitlyBlankCredentialsRemainPresentAfterRoundTrip() {
        val stored = mapOf(
            TranscriptionMode.KEY to "CLOUD",
            "alarma_url" to "",
            "api_key" to "",
            "model" to "",
            "system_prompt" to ""
        )

        assertEquals(stored - "api_key" + (TranscriptionMode.KEY to "OFFLINE"), SettingsFormat.import(SettingsFormat.export(stored)))
    }

    @Test fun existingCredentialValuesSurviveRoundTrip() {
        val stored = mapOf(
            TranscriptionMode.KEY to "CLOUD",
            "alarma_url" to "https://example.test/v1/audio/transcriptions",
            "api_key" to "existing private key",
            "model" to "custom-speech-model",
            "system_prompt" to "Keep punctuation.\nUse British spelling."
        )

        assertEquals(stored - "api_key" + (TranscriptionMode.KEY to "OFFLINE"), SettingsFormat.import(SettingsFormat.export(stored)))
    }

    @Test fun writingStyleChoicesRoundTripWithTheirTypes() {
        val stored = mapOf(TranscriptionMode.KEY to "OFFLINE", StyleSettings.KEY_MATCH_APP to false,
            StyleSettings.KEY_SMART to true, StyleSettings.KEY_STYLE_PREFIX + "personal" to "very_casual")
        assertEquals(stored, SettingsFormat.import(SettingsFormat.export(stored)))
    }

    @Test fun wronglyTypedStyleValuesAreDroppedOnImport() {
        val imported = SettingsFormat.import(mapOf(SettingsFormat.VERSION_KEY to 2, StyleSettings.KEY_MATCH_APP to "false",
            StyleSettings.KEY_STYLE_PREFIX + "personal" to true, StyleSettings.KEY_STYLE_PREFIX + "email" to "shouty"))
        assertEquals(mapOf(TranscriptionMode.KEY to "OFFLINE"), imported)
    }

    @Test fun recordingIndicatorRoundTripsAndRejectsUnknownValues() {
        val stored = mapOf(TranscriptionMode.KEY to "OFFLINE", RecordingIndicator.KEY to "minimal")
        assertEquals(stored, SettingsFormat.import(SettingsFormat.export(stored)))
        assertEquals(mapOf(TranscriptionMode.KEY to "OFFLINE"),
            SettingsFormat.import(mapOf(SettingsFormat.VERSION_KEY to 2, RecordingIndicator.KEY to "flashing")))
    }
}
