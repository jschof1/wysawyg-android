package com.cragnet.wysawyg

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
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
import org.junit.Assume.assumeTrue
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
    private var oldCloudConsent: String? = null
    private var originalClipboard: ClipData? = null
    private var setup = false
    private var testRecordingId: String? = null
    private lateinit var server: LocalTranscriptionServer
    private val start = By.pkg("com.cragnet.wysawyg").desc("Start dictation")
    private val finish = By.pkg("com.cragnet.wysawyg").desc("Stop recording. Hold to stop and keep audio")
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
        assertNull("Resolve existing audio before this suite", RecordingSessions.store(context).pending())
        val snapshot = Recoveries.get(context).snapshot()
        assertTrue("Resolve existing recoveries before this targeted test run", snapshot.items.isEmpty())
        assertFalse("Recovery storage must be readable", snapshot.unreadable)
        oldPrefs = keys.associateWith { prefs.all[it] }
        assertTrue("Accept the in-app Accessibility disclosure before this suite", PrivacyConsent.hasAccessibility(context))
        oldCloudConsent = context.getSharedPreferences("speakeasy_privacy", Context.MODE_PRIVATE)
            .getString("cloud_origin_v1", null)
        server = LocalTranscriptionServer(words)
        context.getSharedPreferences("speakeasy_privacy", Context.MODE_PRIVATE).edit()
            .putString("cloud_origin_v1", "http://127.0.0.1:${server.port}/").commit()
        prefs.edit().putBoolean(MainActivity.PREF_OVERLAY_ENABLED, true)
            .putString(TranscriptionMode.KEY, TranscriptionMode.CLOUD.name)
            .putString(MainActivity.PREF_ALARMA_URL, "http://127.0.0.1:${server.port}/v1")
            .putString(MainActivity.PREF_API_KEY, "").putString(MainActivity.PREF_MODEL, "local-test").commit()
        setup = true
        awaitAccessibility()
    }

    @After fun restore() {
        if (!setup) return
        context.getSharedPreferences("speakeasy_privacy", Context.MODE_PRIVATE).edit()
            .putString("cloud_origin_v1", oldCloudConsent).commit()
        server.release.countDown()
        instrumentation.runOnMainSync { context.stopService(Intent(context, OverlayService::class.java)) }
        await("In-flight request must finish staging") { !Transcriptions.get(context).admission.isHeld }
        server.close()
        RecordingSessions.store(context).pending()?.let { audio ->
            assertEquals("Only discard this test's recording", testRecordingId, audio.id)
            instrumentation.runOnMainSync { RecordingSessions.command(context, OverlayService.DISCARD, audio.id) }
            await("Explicit test cleanup must discard its audio") { RecordingSessions.store(context).pending() == null }
        }
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
        testRecordingId = RecordingSessions.state.value.id
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

    @Test fun visibleTimerAndStopKeepPreserveAudioWithoutAnyRequest() = withEditor {
        beginRecording()
        assertTrue(microphoneActive())
        assertNotNull(device.findObject(res("recordingLevel")))
        val firstTime = requireNotNull(device.findObject(res("recordingTimer"))).text
        await("Timer must visibly advance using elapsed time") {
            device.findObject(res("recordingTimer"))?.text?.let { it != firstTime } == true
        }
        val cancel = requireNotNull(device.findObject(res("cancelRecording")))
        assertTrue(cancel.text.equals("Stop & keep", ignoreCase = true))
        screenshot("recording")
        cancel.click()
        await("Stop and keep must expose recovery") { RecordingSessions.state.value.phase == RecordingPhase.INTERRUPTED }
        assertTrue(device.hasObject(res("retryAudio")))
        assertTrue(requireNotNull(RecordingSessions.store(context).pending()).bytes > 0)
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

    @Test fun passwordRejectsInsertionAndPauseRestartRestoresSavedTranscript() = withEditor { scenario ->
        beginRecording()
        stopWithHeldResponse()
        scenario.onActivity { it.secondEditor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        requireNotNull(device.findObject(res("dictationTestSecondEditor"))).click()
        await("Password editor must hide all dictation UI") { !device.hasObject(start) && !device.hasObject(res("recoveryPanel")) }
        server.release.countDown()
        await("Successful response must be durably recovered") { Recoveries.get(context).snapshot().items.any { it.text == words } }
        SystemClock.sleep(300)
        assertTrue("Private saved text remains available independently of the destination", device.wait(Until.hasObject(res("recoveryPanel")), 5000))
        requireNotNull(device.findObject(res("insertRecovery"))).click()
        device.waitForIdle()
        assertEquals(words, Recoveries.get(context).snapshot().items.single().text)
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

    @Test fun savedTextSurvivesKeyboardCloseAndReopenWithoutClipboard() = withEditor { scenario ->
        scenario.onActivity {
            it.editor.setText("hello world")
            it.editor.setSelection(6)
            it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("sentinel", "keep clipboard"))
            Recoveries.get(it).stage(words)
            TextInjectorService.instance?.refreshEditor()
        }
        assertTrue(device.wait(Until.hasObject(res("recoveryText").text(words)), 5000))
        device.pressBack()
        assertTrue("Saved text must remain reachable with the keyboard closed", device.wait(Until.hasObject(res("insertRecovery")), 5000))
        scenario.onActivity {
            it.editor.requestFocus()
            it.getSystemService(InputMethodManager::class.java).showSoftInput(it.editor, InputMethodManager.SHOW_IMPLICIT)
        }
        device.waitForIdle()
        requireNotNull(device.findObject(res("insertRecovery"))).click()
        await("Confirmed insertion removes only this saved item") { Recoveries.get(context).snapshot().items.isEmpty() }
        scenario.onActivity { assertEquals("hello $words world", it.editor.text.toString()) }
        assertEquals("keep clipboard", clipboardText())
        assertEquals(0, server.requests.get())
    }

    @Test fun currentInputInsertsEvenWhenAccessibilityCannotFindEditableNode() = withEditor { scenario ->
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        scenario.onActivity {
            it.editor.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isEditable = false
                }
            }
            it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("sentinel", "keep clipboard"))
            Recoveries.get(it).stage(words)
            TextInjectorService.instance?.refreshEditor()
        }
        device.waitForIdle()
        instrumentation.runOnMainSync { assertNull(TextInjectorService.instance?.captureTarget()) }
        requireNotNull(device.wait(Until.findObject(res("insertRecovery")), 5000)).click()
        await("Current input can insert without a cached accessibility editor") { Recoveries.get(context).snapshot().items.isEmpty() }
        scenario.onActivity { assertEquals(words, it.editor.text.toString()) }
        assertEquals("keep clipboard", clipboardText())
    }

    @Test fun truncatedInsertionKeepsPrivateTextAndWarnsBeforeRetry() = withEditor { scenario ->
        scenario.onActivity {
            it.editor.filters = arrayOf(InputFilter.LengthFilter(4))
            Recoveries.get(it).stage(words)
            TextInjectorService.instance?.refreshEditor()
        }
        requireNotNull(device.wait(Until.findObject(res("insertRecovery")), 5000)).click()
        assertTrue(device.wait(Until.hasObject(res("insertRecovery").text("Insert again")), 5000))
        scenario.onActivity { assertEquals(words.take(4), it.editor.text.toString()) }
        assertEquals(words, Recoveries.get(context).snapshot().items.single().text)
    }

    @Test fun unconfirmedAutomaticInsertionWarnsBeforeOfferingAnotherWrite() = withEditor { scenario ->
        scenario.onActivity { it.editor.filters = arrayOf(InputFilter.LengthFilter(4)) }
        beginRecording()
        stopWithHeldResponse()
        server.release.countDown()
        assertTrue(device.wait(Until.hasObject(res("insertRecovery").text("Insert again")), 10000))
        scenario.onActivity { assertEquals(words.take(4), it.editor.text.toString()) }
        assertEquals(words, Recoveries.get(context).snapshot().items.single().text)
    }

    @Test fun responseArrivingDuringPauseIsSavedWithoutInsertion() = withEditor { scenario ->
        beginRecording()
        stopWithHeldResponse()
        scenario.onActivity { OverlayService.pause(it) }
        assertTrue("Pause must keep in-flight work owned by the service", OverlayService.isRunning())
        server.release.countDown()
        await("A completed response during Pause must still be staged") {
            Recoveries.get(context).snapshot().items.any { it.text == words }
        }
        assertEquals(words, RecoveryStore(File(context.noBackupFilesDir, "recovered-transcripts.bin")).snapshot().items.single().text)
        scenario.onActivity { assertTrue(it.editor.text.isEmpty()); assertTrue(it.secondEditor.text.isEmpty()) }
        assertFalse(OverlayService.isEnabled(context))
        await("Paused idle service can stop after durable staging") { !OverlayService.isRunning() }
        assertFalse(device.hasObject(res("recoveryPanel")))
    }

    @Test fun lateResponseAfterServiceDestructionClearsInterruptedSessionAndKeepsText() = withEditor { scenario ->
        beginRecording()
        stopWithHeldResponse()
        val id = RecordingSessions.state.value.id
        scenario.onActivity { it.stopService(Intent(it, OverlayService::class.java)) }
        await("Destroyed service must preserve the pending session") {
            RecordingSessions.state.value.id == id && RecordingSessions.state.value.phase == RecordingPhase.INTERRUPTED
        }
        server.release.countDown()
        await("Late completion must clear only the finished audio session") {
            RecordingSessions.state.value.phase == RecordingPhase.IDLE && RecordingSessions.store(context).pending() == null
        }
        assertEquals(words, RecoveryStore(File(context.noBackupFilesDir, "recovered-transcripts.bin")).snapshot().items.single().text)
        scenario.onActivity { assertTrue(it.editor.text.isEmpty()); assertTrue(it.secondEditor.text.isEmpty()) }
    }

    @Test fun keyboardHideNavigationAndPauseKeepSameCaptureUntilExplicitStop() = withEditor { scenario ->
        beginRecording()
        val id = RecordingSessions.state.value.id
        val before = requireNotNull(RecordingSessions.store(context).pending()).bytes
        device.pressBack()
        await("Hidden keyboard must leave Stop visible") { device.hasObject(finish) }
        assertTrue("Keyboard hide must keep microphone foreground type", microphoneActive())
        await("PCM must keep growing while keyboard is hidden") {
            requireNotNull(RecordingSessions.store(context).pending()).bytes > before
        }
        requireNotNull(device.findObject(res("dictationTestSecondEditor"))).click()
        assertEquals(id, RecordingSessions.state.value.id)
        scenario.onActivity { OverlayService.pause(it) }
        assertTrue("Pause affects future starts, not the active recording", OverlayService.isRunning())
        assertTrue(microphoneActive())
        assertEquals(id, RecordingSessions.state.value.id)
        stopWithHeldResponse()
        server.release.countDown()
        await("Navigation must preserve the full transcript without stale insertion") {
            Recoveries.get(context).snapshot().items.any { it.text == words }
        }
        scenario.onActivity { assertTrue(it.editor.text.isEmpty()); assertTrue(it.secondEditor.text.isEmpty()) }
        assertEquals(1, server.requests.get())
        assertNull(RecordingSessions.store(context).pending())
    }

    @Test fun navigatingToAnotherScreenAndReturningKeepsSameSession() = withEditor { scenario ->
        beginRecording()
        val original = RecordingSessions.state.value
        scenario.onActivity { it.startActivity(Intent(it, MoreSettingsActivity::class.java)) }
        assertTrue(device.wait(Until.hasObject(res("moreToolbar")), 5000))
        assertTrue(microphoneActive())
        assertEquals(original.id, RecordingSessions.state.value.id)
        assertEquals(original.startedAt, RecordingSessions.state.value.startedAt)
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(res("dictationTestEditor")), 5000))
        requireNotNull(device.findObject(res("dictationTestEditor"))).click()
        assertEquals(original.id, RecordingSessions.state.value.id)
        stopWithHeldResponse()
        server.release.countDown()
        await("Returning must recover text rather than reuse an old editor token") {
            Recoveries.get(context).snapshot().items.any { it.text == words }
        }
        scenario.onActivity { assertTrue(it.editor.text.isEmpty()) }
    }

    @Test fun serviceInterruptionKeepsPcmAndDoesNotRestartMicOrUpload() = withEditor { scenario ->
        beginRecording()
        await("Capture must have a durable prefix") { RecordingSessions.store(context).pending()?.bytes?.let { it > 0 } == true }
        val id = RecordingSessions.state.value.id
        scenario.onActivity { it.stopService(Intent(it, OverlayService::class.java)) }
        await("Service interruption must release microphone") { !microphoneActive() }
        val reopened = DurablePcmStore(File(context.noBackupFilesDir, "recordings")).pending()
        assertEquals(id, reopened?.id)
        assertTrue(requireNotNull(reopened).bytes > 0)
        scenario.onActivity { ContextCompat.startForegroundService(it, Intent(it, OverlayService::class.java)) }
        await("Restart must expose interrupted state") { RecordingSessions.state.value.phase == RecordingPhase.INTERRUPTED }
        assertFalse(microphoneActive())
        assertEquals(0, server.requests.get())
        assertTrue(device.wait(Until.hasObject(res("retryAudio")), 5000))
    }

    @Test fun optionalImeUsesSameForegroundOwnerAndSurvivesImeDestruction() {
        val oldIme = shell("settings get secure default_input_method")
        val ime = "${context.packageName}/.WysawygKeyboardService"
        val oldOverlayMode = Regex("SYSTEM_ALERT_WINDOW: ([a-z]+)")
            .find(shell("appops get ${context.packageName} SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: "default"
        assertTrue("IME-only recording needs visible notifications", androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
        val wasEnabled = shell("ime list -s").lineSequence().any { it == ime || it == "${context.packageName}/com.cragnet.wysawyg.WysawygKeyboardService" }
        try {
            shell("ime enable $ime")
            shell("ime set $ime")
            withEditor { scenario ->
                // Disable overlay starts; the optional keyboard must still start the shared service.
                instrumentation.runOnMainSync { OverlayService.pause(context) }
                await("Pause must stop the idle overlay service") { !OverlayService.isRunning() }
                shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW deny")
                val imeStart = By.pkg(context.packageName).desc("Start dictation")
                requireNotNull(device.wait(Until.findObject(imeStart), 5000)).click()
                await("IME must use foreground microphone service") { microphoneActive() }
                testRecordingId = RecordingSessions.state.value.id
                assertFalse("IME capture must not need an overlay", device.hasObject(res("recordingPanel")))
                val original = RecordingSessions.state.value
                device.pressBack()
                if (oldIme.isNotBlank() && oldIme != "null" && oldIme != ime) shell("ime set $oldIme")
                // Disabling the optional IME destroys its service; capture belongs to OverlayService.
                shell("ime disable $ime")
                assertTrue(microphoneActive())
                assertEquals(original.id, RecordingSessions.state.value.id)
                assertEquals(original.startedAt, RecordingSessions.state.value.startedAt)
                device.openNotification()
                requireNotNull(device.wait(Until.findObject(By.text("Stop")), 5000)).click()
                assertTrue(server.received.await(8, TimeUnit.SECONDS))
                device.pressBack()
                server.release.countDown()
                await("Destroyed IME must not lose or insert the response") {
                    Recoveries.get(context).snapshot().items.any { it.text == words }
                }
                scenario.onActivity { assertTrue(it.editor.text.isEmpty()) }
            }
        } finally {
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $oldOverlayMode")
            if (wasEnabled) shell("ime enable $ime")
            if (oldIme.isNotBlank() && oldIme != "null") shell("ime set $oldIme")
            if (!wasEnabled) shell("ime disable $ime")
        }
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
