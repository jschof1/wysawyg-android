package com.cragnet.wysawyg

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.InputType
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation).also {
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }
    private val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    private val keys = listOf(TranscriptionMode.KEY, MainActivity.PREF_OVERLAY_ENABLED, MainActivity.PREF_ALARMA_URL,
        MainActivity.PREF_API_KEY, MainActivity.PREF_MODEL)
    private var oldPrefs = emptyMap<String, Any?>()
    private var originalClipboard: ClipData? = null
    private var setup = false
    private lateinit var server: LocalTranscriptionServer
    private val start = By.pkg("com.cragnet.wysawyg").desc("Start dictation")
    private val finish = By.pkg("com.cragnet.wysawyg").desc("Finish dictation. Hold to cancel")
    // A finished sentence, so the destination writing style leaves it unchanged.
    private val words = "WYSAWYG recovery test words."
    private val retainRecoveryForRestart = InstrumentationRegistry.getArguments()
        .getString("retainRecoveryForRestart") == "true"
    private fun res(name: String) = By.res(context.packageName, name)

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.wait(Until.gone(By.textStartsWith("Transcript kept.")), 5000)
        val directory = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue(message, condition())
    }

    private fun awaitAccessibility() {
        if (TextInjectorService.instance == null) {
            val enabled = shell("settings get secure enabled_accessibility_services")
            require(enabled.matches(Regex("[A-Za-z0-9_.:/$]+")))
            val components = enabled.split(':')
            assertTrue("Enable WYSAWYG Accessibility first", components.any { it.startsWith("com.cragnet.wysawyg/") })
            val remaining = components.filterNot { it.startsWith("com.cragnet.wysawyg/") }.joinToString(":")
            try {
                if (remaining.isEmpty()) shell("settings delete secure enabled_accessibility_services")
                else shell("settings put secure enabled_accessibility_services $remaining")
                SystemClock.sleep(500)
            } finally {
                shell("settings put secure enabled_accessibility_services $enabled")
            }
            assertEquals(components.toSet(), shell("settings get secure enabled_accessibility_services").split(':').toSet())
        }
        await("Accessibility must reconnect") { TextInjectorService.instance != null }
    }

    @Before fun prepare() {
        val snapshot = Recoveries.get(context).snapshot()
        assertTrue("Resolve existing recoveries before this targeted test run", snapshot.items.isEmpty())
        assertFalse("Recovery storage must be readable", snapshot.unreadable)
        oldPrefs = keys.associateWith { prefs.all[it] }
        server = LocalTranscriptionServer(words)
        prefs.edit().putBoolean(MainActivity.PREF_OVERLAY_ENABLED, true)
            .putString(TranscriptionMode.KEY, TranscriptionMode.CLOUD.name)
            .putString(MainActivity.PREF_ALARMA_URL, "http://127.0.0.1:${server.port}/v1")
            .putString(MainActivity.PREF_API_KEY, "").putString(MainActivity.PREF_MODEL, "local-test").commit()
        setup = true
        awaitAccessibility()
    }

    @After fun restore() {
        if (!setup) return
        server.release.countDown()
        server.close()
        instrumentation.runOnMainSync { OverlayService.pause(context) }
        await("Overlay must stop") { !OverlayService.isRunning() }
        val store = Recoveries.get(context)
        if (!retainRecoveryForRestart) {
            store.snapshot().items.filter { it.text == words }.forEach { assertTrue(store.remove(it.id)) }
        }
        val edit = prefs.edit()
        keys.forEach { key ->
            when (val value = oldPrefs[key]) {
                is Boolean -> edit.putBoolean(key, value)
                is String -> edit.putString(key, value)
                null -> edit.remove(key)
            }
        }
        edit.commit()
        if (oldPrefs[MainActivity.PREF_OVERLAY_ENABLED] == true) OverlayService.ensureRunning(context)
    }

    private fun withEditor(block: (ActivityScenario<DictationTestActivity>) -> Unit) {
        ActivityScenario.launch(DictationTestActivity::class.java).use { scenario ->
            scenario.onActivity {
                ContextCompat.startForegroundService(it, Intent(it, OverlayService::class.java))
                originalClipboard = it.getSystemService(ClipboardManager::class.java).primaryClip
            }
            requireNotNull(device.wait(Until.findObject(res("dictationTestEditor")), 5000)).click()
            assertTrue(device.wait(Until.hasObject(start), 8000))
            try {
                block(scenario)
            } finally {
                scenario.onActivity { activity ->
                    val clipboard = activity.getSystemService(ClipboardManager::class.java)
                    originalClipboard?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
                }
            }
        }
    }

    private fun beginRecording() {
        requireNotNull(device.wait(Until.findObject(start), 8000)).click()
        assertTrue("The real recording controls must appear", device.wait(Until.hasObject(finish), 8000))
    }

    private fun stopWithHeldResponse() {
        requireNotNull(device.findObject(finish)).click()
        assertTrue("Stop must contact only the local server", server.received.await(8, TimeUnit.SECONDS))
        assertFalse("Stop must release microphone type before HTTP", microphoneActive())
    }

    private fun microphoneActive(): Boolean {
        val record = shell("dumpsys activity services ${context.packageName}")
        val types = Regex("types=0x([0-9a-fA-F]+)").findAll(record).map { it.groupValues[1].toLong(16) }.toList()
        return types.any { it and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.toLong() != 0L }
    }

    private fun clipboardText(): String? {
        var result: String? = null
        instrumentation.runOnMainSync {
            result = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
        }
        return result
    }

    @Test fun visibleTimerAndCancelReleaseMicrophoneWithoutAnyRequest() = withEditor {
        beginRecording()
        assertTrue(microphoneActive())
        assertNotNull(device.findObject(res("recordingLevel")))
        val firstTime = requireNotNull(device.findObject(res("recordingTimer"))).text
        await("Timer must visibly advance using elapsed time") {
            device.findObject(res("recordingTimer"))?.text?.let { it != firstTime } == true
        }
        val cancel = requireNotNull(device.findObject(res("cancelRecording")))
        assertTrue(cancel.text.equals("Cancel", ignoreCase = true))
        screenshot("recording")
        cancel.click()
        assertTrue(device.wait(Until.hasObject(start), 8000))
        assertFalse(microphoneActive())
        SystemClock.sleep(400)
        assertEquals(0, server.requests.get())
        assertTrue(Recoveries.get(context).snapshot().items.isEmpty())
    }

    @Test fun changedTargetRecoversThenCopyAndInsertUseFreshDestination() = withEditor { scenario ->
        scenario.onActivity { it.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("sentinel", "clipboard sentinel")) }
        beginRecording()
        stopWithHeldResponse()
        requireNotNull(device.findObject(res("dictationTestSecondEditor"))).click()
        scenario.onActivity { it.secondEditor.setText("Fresh destination"); it.secondEditor.setSelection(it.secondEditor.length()) }
        server.release.countDown()
        assertTrue("Failed original insertion must expose full recovery", device.wait(Until.hasObject(res("recoveryText").text(words)), 10000))
        scenario.onActivity {
            assertEquals("", it.editor.text.toString())
            assertEquals("Fresh destination", it.secondEditor.text.toString())
            assertTrue("Nonfocusable card must leave the editor focused", it.secondEditor.hasFocus())
        }
        assertEquals("clipboard sentinel", clipboardText())
        screenshot("recovery")
        requireNotNull(device.findObject(res("collapseRecovery"))).click()
        assertFalse(device.hasObject(res("recoveryPanel")))
        assertEquals(words, Recoveries.get(context).snapshot().items.single().text)
        assertTrue(device.hasObject(start))
        screenshot("recovery-collapsed")
        requireNotNull(device.findObject(res("showRecovery"))).click()
        assertTrue(device.wait(Until.hasObject(res("recoveryText").text(words)), 3000))
        requireNotNull(device.findObject(res("copyRecovery"))).click()
        assertEquals(words, clipboardText())
        assertEquals(words, Recoveries.get(context).snapshot().items.single().text)
        requireNotNull(device.findObject(res("insertRecovery"))).click()
        await("Successful fresh insertion must remove recovery") { Recoveries.get(context).snapshot().items.isEmpty() }
        scenario.onActivity {
            assertEquals("", it.editor.text.toString())
            assertEquals("Fresh destination $words", it.secondEditor.text.toString())
        }
        assertEquals("Copy must be the only clipboard change", words, clipboardText())
        assertEquals(1, server.requests.get())
    }

    @Test fun passwordHidesRecoveryAndPauseRestartRestoresSavedTranscript() = withEditor { scenario ->
        beginRecording()
        stopWithHeldResponse()
        scenario.onActivity { it.secondEditor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        requireNotNull(device.findObject(res("dictationTestSecondEditor"))).click()
        await("Password editor must hide all dictation UI") { !device.hasObject(start) && !device.hasObject(res("recoveryPanel")) }
        server.release.countDown()
        await("Successful response must be durably recovered") { Recoveries.get(context).snapshot().items.any { it.text == words } }
        SystemClock.sleep(300)
        assertFalse(device.hasObject(res("recoveryPanel")))
        scenario.onActivity { assertTrue(it.editor.text.isEmpty()); assertTrue(it.secondEditor.text.isEmpty()) }
        val file = File(context.noBackupFilesDir, "recovered-transcripts.bin")
        assertEquals(words, RecoveryStore(file).snapshot().items.single().text)
        instrumentation.runOnMainSync { OverlayService.pause(context) }
        await("Pause must stop service") { !OverlayService.isRunning() }
        assertFalse(OverlayService.isEnabled(context))
        assertEquals(words, RecoveryStore(file).snapshot().items.single().text)
        SystemClock.sleep(400)
        assertFalse("Pause must remain in effect", OverlayService.isRunning())
        prefs.edit().putBoolean(MainActivity.PREF_OVERLAY_ENABLED, true).commit()
        scenario.onActivity { ContextCompat.startForegroundService(it, Intent(it, OverlayService::class.java)) }
        requireNotNull(device.findObject(res("dictationTestEditor"))).click()
        assertTrue(device.wait(Until.hasObject(res("recoveryText").text(words)), 8000))
        scenario.onActivity { assertTrue(it.editor.text.isEmpty()); assertTrue(it.secondEditor.text.isEmpty()) }
        if (retainRecoveryForRestart) return@withEditor
        requireNotNull(device.findObject(res("discardRecovery"))).click()
        await("Explicit discard removes only this recovery") { Recoveries.get(context).snapshot().items.isEmpty() }
        assertTrue(RecoveryStore(file).snapshot().items.isEmpty())
    }

    @Test fun responseArrivingDuringPauseIsSavedWithoutInsertion() = withEditor { scenario ->
        beginRecording()
        stopWithHeldResponse()
        scenario.onActivity { OverlayService.pause(it) }
        await("Pause must stop the in-flight service") { !OverlayService.isRunning() }
        server.release.countDown()
        await("A completed response during Pause must still be staged") {
            Recoveries.get(context).snapshot().items.any { it.text == words }
        }
        assertEquals(words, RecoveryStore(File(context.noBackupFilesDir, "recovered-transcripts.bin")).snapshot().items.single().text)
        scenario.onActivity { assertTrue(it.editor.text.isEmpty()); assertTrue(it.secondEditor.text.isEmpty()) }
        assertFalse(OverlayService.isEnabled(context))
        assertFalse(device.hasObject(res("recoveryPanel")))
    }

    @Test fun keyboardHideAndPauseCancelCaptureWithoutRequests() = withEditor { scenario ->
        beginRecording()
        device.pressBack()
        await("Hiding keyboard must release microphone") { !microphoneActive() }
        assertFalse(device.hasObject(finish))
        requireNotNull(device.findObject(res("dictationTestEditor"))).click()
        assertTrue(device.wait(Until.hasObject(start), 8000))
        beginRecording()
        scenario.onActivity { OverlayService.pause(it) }
        await("Pause during recording must destroy service") { !OverlayService.isRunning() }
        assertFalse(microphoneActive())
        assertEquals(0, server.requests.get())
        assertTrue(Recoveries.get(context).snapshot().items.isEmpty())
    }

    private class LocalTranscriptionServer(private val text: String) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val requests = AtomicInteger()
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile private var active: Socket? = null
        private val worker = Thread {
            try {
                while (!socket.isClosed) {
                    socket.accept().use { client ->
                        active = client
                        client.soTimeout = 8000
                        val input = client.getInputStream().buffered()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val byte = input.read()
                            check(byte >= 0 && header.length < 32768)
                            header.append(byte.toChar())
                        }
                        val size = Regex("Content-Length: ([0-9]+)", RegexOption.IGNORE_CASE)
                            .find(header)?.groupValues?.get(1)?.toInt() ?: 0
                        var remaining = size
                        val buffer = ByteArray(8192)
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                            check(read > 0)
                            remaining -= read
                        }
                        requests.incrementAndGet()
                        received.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                        val body = "{\"text\":\"$text\"}".toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                    }
                }
            } catch (_: Exception) {
                // Closing the test server interrupts accept/read; request expectations fail in the test.
            }
        }.apply { name = "LocalTranscriptionTest"; start() }

        override fun close() {
            release.countDown()
            socket.close()
            active?.close()
            worker.join(2000)
        }
    }
}
