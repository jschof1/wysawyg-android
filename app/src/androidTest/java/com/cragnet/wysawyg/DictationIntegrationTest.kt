package com.cragnet.wysawyg

import android.app.UiAutomation
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.content.ClipboardManager
import android.content.Intent
import android.text.InputType
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
import org.junit.Test
import org.junit.runner.RunWith

/** Requires overlay and accessibility permission; exercises real Android windows/editor actions. */
@RunWith(AndroidJUnit4::class)
class DictationIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation).also {
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }
    private val startButton = By.desc("Start dictation")

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
            shell("settings put secure enabled_accessibility_services '$remaining'")
            shell("settings put secure enabled_accessibility_services '$enabled'")
        }
        val deadline = SystemClock.uptimeMillis() + 8000
        while (TextInjectorService.instance == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertNotNull("Enable WYSAWYG Accessibility first", TextInjectorService.instance)
    }

    private fun awaitEditor() {
        assertTrue("Dictation button must appear with the keyboard", device.wait(Until.hasObject(startButton), 8000))
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
