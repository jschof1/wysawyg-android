package com.cragnet.wysawyg

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyConsentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    private val privacy = context.getSharedPreferences("speakeasy_privacy", Context.MODE_PRIVATE)
    private val device = UiDevice.getInstance(instrumentation)
    private var savedSettings: Map<String, Any?> = emptyMap()
    private var savedPrivacy: Map<String, Any?> = emptyMap()
    private var capturedState = false
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        check(!OverlayService.isRunning()) { "Pause floating dictation before running this isolated suite." }
        savedSettings = settings.all.toMap()
        savedPrivacy = privacy.all.toMap()
        capturedState = true
        server = MockWebServer().apply { start(java.net.InetAddress.getLoopbackAddress(), 0) }
        assertTrue(privacy.edit().clear().commit())
        assertTrue(settings.edit().putBoolean(MainActivity.PREF_OVERLAY_ENABLED, false)
            .putString(TranscriptionMode.KEY, TranscriptionMode.CLOUD.name)
            .putString(MainActivity.PREF_ALARMA_URL, server.url("/v1").toString())
            .putString(MainActivity.PREF_API_KEY, "").putString(MainActivity.PREF_MODEL, "test-only").commit())
    }

    @After fun tearDown() {
        if (!capturedState) return
        if (::server.isInitialized) server.shutdown()
        restore(settings, savedSettings)
        restore(privacy, savedPrivacy)
    }

    @Test fun legacyCloudChoiceCannotUploadWithoutConsent() = runBlocking {
        assertEquals(TranscriptionMode.OFFLINE, Transcriptions.effectiveMode(context))
        assertTrue(runCatching { OllamaClient(context).transcribe(byteArrayOf(1, 2)) }.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun approvedCloudCanUploadOnlyToTheApprovedConfiguration() = runBlocking {
        val destination = CloudDestination.parse(server.url("/v1").toString(), allowHttp = true)
        assertTrue(privacy.edit().putString("cloud_origin_v1", destination.origin).commit())
        assertEquals(TranscriptionMode.CLOUD, Transcriptions.effectiveMode(context))
        val client = OllamaClient(context)
        val captured = client.configuration()
        server.enqueue(MockResponse().setBody("{\"text\":\"synthetic test words\"}"))
        assertEquals("synthetic test words", client.transcribe(byteArrayOf(1, 2), captured))
        assertEquals("/v1/audio/transcriptions", server.takeRequest().path)
        assertTrue(settings.edit().putString(MainActivity.PREF_MODEL, "changed-during-recording").commit())
        assertTrue(runCatching { client.transcribe(byteArrayOf(3), captured) }.isFailure)
        assertEquals(1, server.requestCount)
        PrivacyConsent.revokeCloud(context)
        assertTrue(runCatching { client.transcribe(byteArrayOf(4), captured) }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectCannotReplayAudioToAnUnapprovedServer() = runBlocking {
        val destination = CloudDestination.parse(server.url("/v1").toString(), allowHttp = true)
        assertTrue(privacy.edit().putString("cloud_origin_v1", destination.origin).commit())
        MockWebServer().use { other ->
            other.start(java.net.InetAddress.getLoopbackAddress(), 0)
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", other.url("/upload")))
            assertTrue(runCatching { OllamaClient(context).transcribe(byteArrayOf(1, 2)) }.isFailure)
            assertEquals(1, server.requestCount)
            assertEquals(0, other.requestCount)
        }
    }

    @Test fun malformedProviderResponseDoesNotEscapeIntoDiagnostics() = runBlocking {
        val destination = CloudDestination.parse(server.url("/v1").toString(), allowHttp = true)
        assertTrue(privacy.edit().putString("cloud_origin_v1", destination.origin).commit())
        server.enqueue(MockResponse().setBody("private-provider-response"))
        val failure = runCatching { OllamaClient(context).transcribe(byteArrayOf(1, 2)) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("Invalid transcription response", failure?.message)
        assertFalse(requireNotNull(failure).stackTraceToString().contains("private-provider-response"))
    }

    @Test fun invalidApiKeyCannotLeakThroughHttpHeaderErrors() = runBlocking {
        val destination = CloudDestination.parse(server.url("/v1").toString(), allowHttp = true)
        assertTrue(privacy.edit().putString("cloud_origin_v1", destination.origin).commit())
        assertTrue(settings.edit().putString(MainActivity.PREF_API_KEY, "private-key\ninvalid").commit())
        val failure = runCatching { OllamaClient(context).transcribe(byteArrayOf(1, 2)) }.exceptionOrNull()
        assertEquals("The provider API key contains unsupported characters.", failure?.message)
        assertFalse(requireNotNull(failure).stackTraceToString().contains("private-key"))
        assertEquals(0, server.requestCount)
    }

    @Test fun bothScreensOpenTheBundledPrivacyPolicy() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            activity.onActivity { it.findViewById<View>(R.id.privacyPolicyButton).performClick() }
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("Speakeasy privacy policy")), 5000))
            device.pressBack()
        }
        ActivityScenario.launch(MoreSettingsActivity::class.java).use { activity ->
            activity.onActivity { it.findViewById<View>(R.id.privacyPolicyButton).performClick() }
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("Speakeasy privacy policy")), 5000))
            device.pressBack()
        }
    }

    @Test fun decliningAccessibilityDoesNotGrantConsent() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            activity.onActivity { it.findViewById<View>(R.id.enableAccessibilityButton).performClick() }
            val decline = device.wait(Until.findObject(By.text("Not now")), 5000)
            assertNotNull(decline)
            requireNotNull(decline).click()
            assertFalse(PrivacyConsent.hasAccessibility(context))
        }
    }

    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        prefs.edit().apply {
            clear()
            values.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            check(commit())
        }
    }
}
