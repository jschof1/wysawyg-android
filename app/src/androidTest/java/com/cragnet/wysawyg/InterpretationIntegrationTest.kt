package com.cragnet.wysawyg

import android.text.InputType
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in: real speech through the bundled engine, then shaped for different apps.
 * Push a 16 kHz WAV into the app's files directory and pass -e interpretFixture <absolute path>.
 * Results are logged under the tag WysawygInterpret. Never changes preferences or recoveries.
 */
@RunWith(AndroidJUnit4::class)
class InterpretationIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test fun realSpeechIsShapedForEachApp() = runBlocking {
        val path = args.getString("interpretFixture")
        assumeTrue("Pass interpretFixture to run real speech", path != null)
        val raw = withTimeout(120_000) { PhononClient(context).transcribe(File(requireNotNull(path)).readBytes()) }
        Log.i(TAG, "raw: $raw")
        assertTrue("Speech must produce text", raw.isNotBlank())

        val multiline = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val whatsapp = DictationFormatter.format(raw, DictationContext("com.whatsapp", multiline), StyleSettings())
        val gmail = DictationFormatter.format(raw, DictationContext("com.google.android.gm", multiline), StyleSettings())
        Log.i(TAG, "whatsapp: $whatsapp")
        Log.i(TAG, "gmail: $gmail")
        assertFalse("Casual messages end without a full stop", whatsapp.endsWith("."))
        assertTrue("Casual messages have no more commas than speech", whatsapp.count { it == ',' } <= raw.count { it == ',' })
        assertTrue("Email ends a sentence", gmail.last() in ".?!")
        assertFalse("Fillers are removed", Regex("""\b(um|uh|erm)\b""", RegexOption.IGNORE_CASE).containsMatchIn(whatsapp))

        val model = SmartCleanup.get(context)
        if (model.installed) {
            val prepared = DictationFormatter.prepare(raw, DictationContext("com.google.android.gm", multiline), StyleSettings())
            val started = System.currentTimeMillis()
            val smart = model.punctuate(prepared)
            Log.i(TAG, "smart (${System.currentTimeMillis() - started} ms): $smart")
            if (smart != null) assertTrue(DictationFormatter.keepsWords(prepared, smart))
        }
    }

    private companion object { const val TAG = "WysawygInterpret" }
}
