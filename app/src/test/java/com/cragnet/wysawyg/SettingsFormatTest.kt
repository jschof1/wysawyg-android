package com.cragnet.wysawyg

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFormatTest {
    @Test fun cloudKeyOnlyRoundTripLeavesUntouchedDefaultsAbsent() {
        val stored = mapOf(TranscriptionMode.KEY to "CLOUD", "api_key" to "existing private key")

        val exported = SettingsFormat.export(stored)

        assertEquals(stored + (SettingsFormat.VERSION_KEY to SettingsFormat.VERSION), exported)
        assertEquals(stored, SettingsFormat.import(exported))
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

        assertEquals(stored, SettingsFormat.import(SettingsFormat.export(stored)))
    }

    @Test fun existingCredentialValuesSurviveRoundTrip() {
        val stored = mapOf(
            TranscriptionMode.KEY to "CLOUD",
            "alarma_url" to "https://example.test/v1/audio/transcriptions",
            "api_key" to "existing private key",
            "model" to "custom-speech-model",
            "system_prompt" to "Keep punctuation.\nUse British spelling."
        )

        assertEquals(stored, SettingsFormat.import(SettingsFormat.export(stored)))
    }
}
