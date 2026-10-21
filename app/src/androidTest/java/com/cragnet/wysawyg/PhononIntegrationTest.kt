package com.cragnet.wysawyg

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in real JNI + Binder tests. Never change preferences, clipboard or production recoveries.
 * Push a 16 kHz WAV into the target app's files directory and pass -e phononFixture <absolute path>.
 * Optional: -e phononExpected "normalized expected words"; -e phononCrashWorker true.
 */
@RunWith(AndroidJUnit4::class)
class PhononIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private fun fixture(): ByteArray {
        val path = args.getString("phononFixture")
        assumeTrue("Pass phononFixture to exercise the bundled native model", path != null)
        val file = File(requireNotNull(path))
        require(file.isFile && file.length() <= PrivateFiles.MAX_WAV_BYTES) { "Fixture absent or too large: $path" }
        return file.readBytes()
    }
    private fun words(text: String) = text.lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun verifyExpected(text: String) {
        assertTrue("Known speech fixture must produce text", text.isNotBlank())
        args.getString("phononExpected")?.let { assertEquals(words(it), words(text)) }
    }
    private fun recoveryBytes(): ByteArray? = File(context.noBackupFilesDir, "recovered-transcripts.bin")
        .takeIf { it.exists() }?.readBytes()

    @Test fun fixtureUsesNativeRouterAndStagesCompleteTextBeforeReturning() = runBlocking {
        val wav = fixture()
        val originalPrefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).all.toMap()
        val originalRecoveries = recoveryBytes()
        val testFile = File.createTempFile("phonon-test-", ".recovery", context.cacheDir).apply { delete() }
        val store = RecoveryStore(testFile)
        val client = TranscriptionClient(CaptureAdmission { store.snapshot().canCapture }, { TranscriptionMode.OFFLINE },
            TranscriptionRouter({ PhononClient(context) }, { error("Offline must not construct HTTP") }))
        try {
            val text = withTimeout(120_000) { client.transcribe(wav) { if (it.isNotBlank()) store.stage(it) } }
            verifyExpected(text)
            assertEquals(text, RecoveryStore(testFile).snapshot().items.single().text)
        } finally {
            testFile.delete()
            assertTrue("Production preferences must be preserved",
                originalPrefs == context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).all)
            assertTrue("Production recoveries must be preserved",
                originalRecoveries.contentEquals(recoveryBytes()))
        }
    }

    @Test fun cancellationStillStagesNativeResultExactlyOnceWithoutInsertion() = runBlocking {
        val wav = fixture()
        val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        val staged = CompletableDeferred<String>()
        val count = AtomicInteger()
        val admission = CaptureAdmission { true }
        val native = PhononClient(context)
        val client = TranscriptionClient(admission, { TranscriptionMode.OFFLINE }, TranscriptionRouter({
            TranscriptionBackend { entered.complete(Unit); native.transcribe(it) }
        }, { error("HTTP") }), app)
        var inserted = false
        try {
            val waiter = async {
                client.transcribe(wav) { count.incrementAndGet(); staged.complete(it) }
                inserted = true
            }
            withTimeout(5000) { entered.await() }
            waiter.cancelAndJoin()
            verifyExpected(withTimeout(120_000) { staged.await() })
            assertEquals(1, count.get())
            assertFalse(inserted)
        } finally { app.cancel() }
    }

    @Test fun workerCrashFailsRequestAndNextDeliberateRequestRebinds() = runBlocking {
        assumeTrue("Opt in to killing only the private speech worker", args.getString("phononCrashWorker") == "true")
        val wav = fixture()
        var killed = false
        val crashing = PhononClient(context) {
            val worker = context.getSystemService(ActivityManager::class.java).runningAppProcesses
                .single { it.processName == "${context.packageName}:phonon" }
            check(worker.uid == Process.myUid() && worker.pid != Process.myPid())
            Process.killProcess(worker.pid)
            killed = true
        }
        val result = runCatching { withTimeout(120_000) { crashing.transcribe(wav) } }
        assertTrue(killed)
        assertTrue("Worker death must be explicit, with no retry", result.isFailure)
        val text = withTimeout(120_000) { PhononClient(context).transcribe(wav) }
        verifyExpected(text)
    }

    @Test fun pfdUsesUnlinkedPrivateFilesAndPreservesUnicode() {
        val before = context.cacheDir.list()?.toSet().orEmpty()
        val text = "café Ελληνικά 𐐀 😀"
        PrivateFiles.descriptor(context, text.toByteArray(Charsets.UTF_8)).use { fd ->
            assertEquals(before, context.cacheDir.list()?.toSet().orEmpty())
            assertEquals(text, PrivateFiles.readText(fd))
        }
        assertEquals(before, context.cacheDir.list()?.toSet().orEmpty())
    }
}
