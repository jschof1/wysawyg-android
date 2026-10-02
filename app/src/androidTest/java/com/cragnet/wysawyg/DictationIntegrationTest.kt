package com.cragnet.wysawyg

import android.app.UiAutomation
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.content.ClipboardManager
import android.content.Intent
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires overlay and accessibility permission; exercises real Android windows/editor actions. */
@RunWith(AndroidJUnit4::class)
class DictationIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation).also {
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }
    private val startButton = By.pkg("com.cragnet.wysawyg").desc("Start dictation")

    private fun shell(command: String): String {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
    }

    private fun awaitAccessibility() {
        // Starting instrumentation replaces the app process, which Android marks as a
        // disconnected accessibility service. Rebind only the already-enabled service.
        if (TextInjectorService.instance == null) {
            val enabled = shell("settings get secure enabled_accessibility_services")
            require(enabled.matches(Regex("[A-Za-z0-9_.:/$]+")))
            val components = enabled.split(':')
            assertTrue("Enable WYSAWYG Accessibility first", components.any { it.startsWith("com.cragnet.wysawyg/") })
            val remaining = components.filterNot { it.startsWith("com.cragnet.wysawyg/") }.joinToString(":")
            // UiAutomation executes arguments directly; shell quotes become part of
            // the value and corrupt the component names. Values are validated above.
            if (remaining.isEmpty()) {
                shell("settings delete secure enabled_accessibility_services")
            } else {
                shell("settings put secure enabled_accessibility_services $remaining")
            }
            SystemClock.sleep(500)
            shell("settings put secure enabled_accessibility_services $enabled")
            assertEquals("Rebinding must preserve all enabled services", components.toSet(),
                shell("settings get secure enabled_accessibility_services").split(':').toSet())
        }
        val deadline = SystemClock.uptimeMillis() + 8000
        while (TextInjectorService.instance == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertNotNull("Enable WYSAWYG Accessibility first", TextInjectorService.instance)
    }

    private fun awaitEditor() {
        assertTrue("Dictation button must appear with the keyboard", device.wait(Until.hasObject(startButton), 8000))
    }

    /** Opt-in check: inserts an unsent test draft in the installed ChatGPT app, then clears it. */
    @Test fun chatGptShowsButtonAndAcceptsDirectInsertion() {
        assumeTrue("Run with -e verifyChatGpt true", InstrumentationRegistry.getArguments().getString("verifyChatGpt") == "true")
        device.waitForIdle()
        awaitAccessibility()
        ActivityScenario.launch(DictationTestActivity::class.java).use { scenario ->
            scenario.onActivity { ContextCompat.startForegroundService(it, Intent(it, OverlayService::class.java)) }
        }
        shell("am start -n com.openai.chatgpt/.MainActivity")
        val composer = By.pkg("com.openai.chatgpt").clazz("android.widget.EditText")
        if (device.wait(Until.findObject(composer), 2000) == null &&
            device.hasObject(By.pkg("com.openai.chatgpt").text("Images")) &&
            device.hasObject(By.pkg("com.openai.chatgpt").text("Library"))) {
            device.pressBack() // Close the app's navigation drawer.
        }
        requireNotNull(device.wait(Until.findObject(composer), 8000)).click()
        awaitEditor()
        val spoken = "WYSAWYG compatibility check."
        var inserted = false
        try {
            instrumentation.runOnMainSync {
                val service = requireNotNull(TextInjectorService.instance)
                val target = requireNotNull(service.captureTarget())
                assertEquals("com.openai.chatgpt", target.node.packageName.toString())
                assertTrue("Leave an empty ChatGPT composer for this check", target.text.isEmpty())
                inserted = service.insert(target, spoken) == TextInjectorService.InsertResult.INSERTED
                assertTrue("ChatGPT must accept direct dictation", inserted)
            }
            device.waitForIdle()
            instrumentation.runOnMainSync {
                val target = requireNotNull(TextInjectorService.instance?.captureTarget())
                assertTrue("ChatGPT must contain only the unsent test dictation", target.text == spoken)
            }
        } finally {
            instrumentation.runOnMainSync {
                val target = TextInjectorService.instance?.captureTarget()
                if (inserted && target?.node?.packageName?.toString() == "com.openai.chatgpt" && target.text == spoken) {
                    target.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    })
                }
            }
        }
        device.waitForIdle()
        instrumentation.runOnMainSync {
            assertTrue("The unsent test draft must be cleared", requireNotNull(TextInjectorService.instance?.captureTarget()).text.isEmpty())
        }
        device.pressBack()
        assertTrue("ChatGPT's button must disappear when the keyboard closes", device.wait(Until.gone(startButton), 5000))
    }

    @Test fun findsVirtualEditorWhenFocusLookupReturnsItsContainer() {
        device.waitForIdle()
        awaitAccessibility()
        ActivityScenario.launch(DictationTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.editor.accessibilityDelegate = VirtualEditorAccessibility(activity.editor)
                activity.editor.setText("Hello world.")
                ContextCompat.startForegroundService(activity, Intent(activity, OverlayService::class.java))
            }
            requireNotNull(device.wait(Until.findObject(By.res("com.cragnet.wysawyg", "dictationTestEditor")), 5000)).click()
            awaitEditor()
            scenario.onActivity { it.editor.setSelection(6) }
            scenario.onActivity {
                val service = requireNotNull(TextInjectorService.instance)
                val target = requireNotNull(service.captureTarget())
                assertEquals(TextInjectorService.InsertResult.INSERTED, service.insert(target, "brave new"))
            }
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("Hello brave new world.", activity.editor.text.toString())
                assertEquals(16, activity.editor.selectionStart)
                activity.editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                TextInjectorService.instance?.refreshEditor()
            }
            assertTrue("Virtual password editors must stay hidden", device.wait(Until.gone(startButton), 5000))
        }
    }

    @Test fun excludesPlaceholderAndCanAppendToFirstDictation() {
        device.waitForIdle()
        awaitAccessibility()
        ActivityScenario.launch(DictationTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity, Intent(activity, OverlayService::class.java))
                activity.editor.hint = "message"
                activity.editor.setText("")
            }
            requireNotNull(device.wait(Until.findObject(By.res("com.cragnet.wysawyg", "dictationTestEditor")), 5000)).click()
            awaitEditor()
            scenario.onActivity { activity ->
                val service = requireNotNull(TextInjectorService.instance)
                val target = requireNotNull(service.captureTarget())
                assertTrue("The empty editor exposes its placeholder through accessibility", target.node.isShowingHintText)
                assertEquals("message", target.node.text.toString())
                assertEquals(TextInjectorService.InsertResult.INSERTED, service.insert(target, "Hello there"))
            }
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("Hello there", activity.editor.text.toString())
                assertEquals(11, activity.editor.selectionStart)
                val service = requireNotNull(TextInjectorService.instance)
                val target = requireNotNull(service.captureTarget())
                assertFalse(target.node.isShowingHintText)
                assertEquals(TextInjectorService.InsertResult.INSERTED, service.insert(target, "again"))
            }
            device.waitForIdle()
            scenario.onActivity { assertEquals("Hello there again", it.editor.text.toString()) }
        }
    }

    @Test fun preservesTypedTextEvenWhenItMatchesThePlaceholder() {
        device.waitForIdle()
        awaitAccessibility()
        ActivityScenario.launch(DictationTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity, Intent(activity, OverlayService::class.java))
                activity.editor.hint = "message"
                activity.editor.setText("message")
            }
            requireNotNull(device.wait(Until.findObject(By.res("com.cragnet.wysawyg", "dictationTestEditor")), 5000)).click()
            awaitEditor()
            scenario.onActivity { it.editor.setSelection(7) }
            scenario.onActivity {
                val service = requireNotNull(TextInjectorService.instance)
                val target = requireNotNull(service.captureTarget())
                assertFalse(target.node.isShowingHintText)
                assertEquals(TextInjectorService.InsertResult.INSERTED, service.insert(target, "received"))
            }
            device.waitForIdle()
            scenario.onActivity { assertEquals("message received", it.editor.text.toString()) }
        }
    }

    @Test fun preservesTextTypedIntoEmptyFieldWhileTranscriptionIsPending() {
        device.waitForIdle()
        awaitAccessibility()
        ActivityScenario.launch(DictationTestActivity::class.java).use { scenario ->
            lateinit var target: TextInjectorService.Target
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity, Intent(activity, OverlayService::class.java))
                activity.editor.hint = "message"
                activity.editor.setText("")
            }
            requireNotNull(device.wait(Until.findObject(By.res("com.cragnet.wysawyg", "dictationTestEditor")), 5000)).click()
            awaitEditor()
            scenario.onActivity { target = requireNotNull(TextInjectorService.instance?.captureTarget()) }
            scenario.onActivity {
                it.editor.setText("message")
                it.editor.setSelection(7)
            }
            device.waitForIdle()
            scenario.onActivity {
                assertEquals(TextInjectorService.InsertResult.TARGET_CHANGED, TextInjectorService.instance?.insert(target, "late dictation"))
                assertEquals("message", it.editor.text.toString())
            }
        }
    }

    @Test fun appearsOnlyWithKeyboardAndInsertsAtCursorWithoutClipboard() {
        // Connect automation without disabling the accessibility service under test.
        device.waitForIdle()
        awaitAccessibility()
        ActivityScenario.launch(DictationTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Enable WYSAWYG Accessibility first", TextInjectorService.instance)
                ContextCompat.startForegroundService(activity, Intent(activity, OverlayService::class.java))
            }
            assertTrue(device.wait(Until.gone(startButton), 5000))
            scenario.onActivity { activity ->
                activity.editor.setText("Hello world.")
            }
            requireNotNull(device.wait(Until.findObject(By.res("com.cragnet.wysawyg", "dictationTestEditor")), 5000)).click()
            awaitEditor()
            scenario.onActivity { it.editor.setSelection(6) }
            scenario.onActivity { activity ->
                val service = requireNotNull(TextInjectorService.instance)
                val target = requireNotNull(service.captureTarget())
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                val before = clipboard.primaryClipDescription?.timestamp
                assertEquals(TextInjectorService.InsertResult.INSERTED, service.insert(target, "brave new"))
                assertEquals(before, clipboard.primaryClipDescription?.timestamp)
            }
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("Hello brave new world.", activity.editor.text.toString())
                assertEquals(16, activity.editor.selectionStart)
            }
            device.pressBack()
            assertTrue("Button must disappear when keyboard closes", device.wait(Until.gone(startButton), 5000))
        }
    }

    @Test fun doesNotInsertIntoADifferentFieldOrAPassword() {
        // Connect automation without disabling the accessibility service under test.
        device.waitForIdle()
        awaitAccessibility()
        ActivityScenario.launch(DictationTestActivity::class.java).use { scenario ->
            lateinit var target: TextInjectorService.Target
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity, Intent(activity, OverlayService::class.java))
                activity.editor.setText("Keep this")
            }
            requireNotNull(device.wait(Until.findObject(By.res("com.cragnet.wysawyg", "dictationTestEditor")), 5000)).click()
            awaitEditor()
            scenario.onActivity { it.editor.setSelection(9) }
            scenario.onActivity { target = requireNotNull(TextInjectorService.instance?.captureTarget()) }
            scenario.onActivity { activity -> activity.secondEditor.requestFocus() }
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals(TextInjectorService.InsertResult.TARGET_CHANGED, TextInjectorService.instance?.insert(target, "wrong field"))
                assertEquals("Keep this", activity.editor.text.toString())
                assertEquals("", activity.secondEditor.text.toString())
                activity.secondEditor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                TextInjectorService.instance?.refreshEditor()
            }
            assertTrue("Button must stay hidden in password fields", device.wait(Until.gone(startButton), 5000))
        }
    }
    @Test fun microphoneProducesValidWavAndCanRestartAfterClosing() {
        ActivityScenario.launch(DictationTestActivity::class.java).use { scenario ->
            lateinit var recorder: AudioRecorder
            scenario.onActivity { activity ->
                recorder = AudioRecorder(activity)
                ContextCompat.startForegroundService(activity, Intent(activity, OverlayService::class.java))
            }
            try {
                repeat(2) {
                    scenario.onActivity { recorder.start() }
                    SystemClock.sleep(300)
                    val wav = recorder.stop()
                    assertTrue("Microphone must produce PCM samples", wav.size > 44)
                    assertEquals("RIFF", String(wav, 0, 4))
                    assertEquals("WAVE", String(wav, 8, 4))
                    val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
                    assertEquals(16000, header.getInt(24))
                    assertEquals(1, header.getShort(22).toInt())
                    assertEquals(16, header.getShort(34).toInt())
                    assertEquals(wav.size - 44, header.getInt(40))
                }
            } finally { recorder.close() }
        }
    }

}
