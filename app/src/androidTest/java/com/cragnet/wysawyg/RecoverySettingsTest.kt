package com.cragnet.wysawyg

import android.app.UiAutomation
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoverySettingsTest {
    @Test fun pausedSettingsCanSaveMemoryOnlyTextAfterStorageRecovers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation).also {
            Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        }
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val previousEnabled = prefs.all[MainActivity.PREF_OVERLAY_ENABLED] as Boolean?
        val original = Recoveries.get(context)
        val field = Recoveries::class.java.getDeclaredField("store").apply { isAccessible = true }
        val directory = File.createTempFile("recovery-settings", ".test", context.cacheDir).apply { delete(); mkdirs() }
        val blocked = File(directory, "blocked").apply { writeText("not a directory") }
        val file = File(blocked, "recovery.bin")
        val store = RecoveryStore(file)
        val item = store.stage("WYSAWYG settings recovery test")
        assertTrue(item.id in store.snapshot().unsavedIds)
        try {
            instrumentation.runOnMainSync { OverlayService.pause(context) }
            val deadline = SystemClock.uptimeMillis() + 10000
            while (OverlayService.isRunning() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertFalse(OverlayService.isRunning())
            field.set(null, store)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // The row sits below the fold on the home screen; scroll it into view like a person would.
                scenario.onActivity { activity ->
                    val row = activity.findViewById<android.view.View>(R.id.recoveredTranscriptsButton)
                    row.requestRectangleOnScreen(android.graphics.Rect(0, 0, row.width, row.height), true)
                }
                requireNotNull(device.wait(Until.findObject(By.res(context.packageName, "recoveredTranscriptsButton")), 5000)).click()
                assertNotNull(device.wait(Until.findObject(By.textContains("Not saved:")), 5000))
                val retry = requireNotNull(device.findObject(By.text(Pattern.compile("Retry saving", Pattern.CASE_INSENSITIVE))))
                if (InstrumentationRegistry.getArguments().getString("screenshots") == "true") {
                    val images = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
                    assertTrue(device.takeScreenshot(File(images, "settings-retry.png")))
                }
                assertTrue(blocked.delete())
                assertTrue(blocked.mkdir())
                retry.click()
                assertTrue(store.snapshot().unsavedIds.isEmpty())
                assertEquals(item.text, RecoveryStore(file).snapshot().items.single().text)
                assertFalse(OverlayService.isRunning())
            }
        } finally {
            field.set(null, original)
            val edit = prefs.edit()
            if (previousEnabled == null) edit.remove(MainActivity.PREF_OVERLAY_ENABLED)
            else edit.putBoolean(MainActivity.PREF_OVERLAY_ENABLED, previousEnabled)
            edit.commit()
            if (previousEnabled == true) OverlayService.ensureRunning(context)
            directory.deleteRecursively()
        }
    }
}
