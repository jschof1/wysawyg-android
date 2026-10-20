package com.cragnet.wysawyg

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation).also {
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    private fun shell(command: String): String {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        return condition()
    }

    private fun reconnectAccessibility() {
        val enabled = shell("settings get secure enabled_accessibility_services")
        require(enabled.matches(Regex("[A-Za-z0-9_.:/$]+")))
        val components = enabled.split(':')
        assertTrue("WYSAWYG accessibility must already be enabled",
            components.any { it.startsWith("com.cragnet.wysawyg/") })
        val remaining = components.filterNot { it.startsWith("com.cragnet.wysawyg/") }.joinToString(":")
        try {
            if (remaining.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $remaining")
            assertTrue("Accessibility must disconnect", await { TextInjectorService.instance == null })
            SystemClock.sleep(500)
        } finally {
            shell("settings put secure enabled_accessibility_services $enabled")
        }
        assertEquals("Retain every other accessibility service", components.toSet(),
            shell("settings get secure enabled_accessibility_services").split(':').toSet())
        assertTrue("Accessibility must reconnect", await { TextInjectorService.instance != null })
    }

    private fun verifyReconnect(enabled: Boolean) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val previous = prefs.getBoolean(MainActivity.PREF_OVERLAY_ENABLED, false)
        ActivityScenario.launch(DictationTestActivity::class.java).use { scenario ->
            try {
                prefs.edit().putBoolean(MainActivity.PREF_OVERLAY_ENABLED, enabled).commit()
                scenario.onActivity { it.stopService(Intent(it, OverlayService::class.java)) }
                assertTrue("Overlay must stop before reconnect", await { !OverlayService.isRunning() })
                reconnectAccessibility()
                if (enabled) {
                    assertTrue("Enabled dictation must recover without opening WYSAWYG settings",
                        await { OverlayService.isRunning() })
                    requireNotNull(device.wait(Until.findObject(By.res(context.packageName, "dictationTestEditor")), 5000)).click()
                    assertTrue("Recovered dictation must show its button with Gboard",
                        device.wait(Until.hasObject(By.pkg(context.packageName).desc("Start dictation")), 8000))
                } else {
                    SystemClock.sleep(1000)
                    assertFalse("Accessibility reconnect must respect Pause", OverlayService.isRunning())
                    assertFalse(prefs.getBoolean(MainActivity.PREF_OVERLAY_ENABLED, true))
                }
            } finally {
                prefs.edit().putBoolean(MainActivity.PREF_OVERLAY_ENABLED, previous).commit()
                scenario.onActivity {
                    if (previous) ContextCompat.startForegroundService(it, Intent(it, OverlayService::class.java))
                    else it.stopService(Intent(it, OverlayService::class.java))
                }
            }
        }
    }

    @Test fun enabledDictationRecoversAfterAccessibilityReconnect() = verifyReconnect(true)

    @Test fun pausedDictationStaysPausedAfterAccessibilityReconnect() = verifyReconnect(false)
}
