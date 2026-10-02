package com.cragnet.wysawyg

import android.app.UiAutomation
import android.os.SystemClock
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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

    private fun awaitAccessibility() {
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
                activity.editor.requestFocus()
                activity.editor.setSelection(6)
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(activity.editor, InputMethodManager.SHOW_IMPLICIT)
            }
            awaitEditor()
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
                activity.editor.requestFocus()
                activity.editor.setSelection(9)
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(activity.editor, InputMethodManager.SHOW_IMPLICIT)
            }
            awaitEditor()
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
}
