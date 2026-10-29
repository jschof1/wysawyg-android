package com.cragnet.wysawyg

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
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecordingTranscriptionTest {
    @Test fun spokenLineBreakSurvivesDurableStagingBeforeAudioRemoval() = runBlocking {
        val directory = Files.createTempDirectory("line-break-recording").toFile()
        try {
            val store = DurablePcmStore(File(directory, "audio"))
            val audio = store.create()
            store.openWriter(audio).use { it.append(byteArrayOf(1, 2), 2) }
            val recovery = RecoveryStore(File(directory, "text"))
            val admission = CaptureAdmission({ TranscriptionBackend { "new line" } }) { true }
            val client = TranscriptionClient(admission)
            val text = client.transcribeRecording(audio, requireNotNull(admission.acquire()), { "\n" }) {
                recovery.stageRecording(it, audio, store)
            }
            assertEquals("\n", text)
            assertNull(store.pending())
            assertEquals("\n", RecoveryStore(File(directory, "text")).snapshot().items.single().text)
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedSegmentKeepsAudioAndRetryReusesDurableCompletedSegments() = runBlocking {
        val directory = Files.createTempDirectory("segments").toFile()
        try {
            val store = DurablePcmStore(directory)
            val audio = store.create()
            store.openWriter(audio).use {
                val bytes = ByteArray(DurablePcmStore.SEGMENT_BYTES + 2)
                it.append(bytes, bytes.size)
            }
            var calls = 0
            val admission = CaptureAdmission({ TranscriptionBackend {
                calls++
                if (calls == 2) error("worker interrupted")
                "first"
            } }) { true }
            val client = TranscriptionClient(admission)
            assertTrue(runCatching {
                client.transcribeRecording(audio, requireNotNull(admission.acquire())) { error("Incomplete text cannot stage") }
            }.isFailure)
            assertEquals("first", audio.savedSegment(0))
            assertNull(audio.savedSegment(1))
            assertEquals(audio.id, store.pending()?.id)
            var retryCalls = 0
            val retry = requireNotNull(admission.acquire { TranscriptionBackend { retryCalls++; "second" } })
            var staged = ""
            assertEquals("first second", client.transcribeRecording(audio, retry) { staged = it })
            assertEquals("first second", staged)
            assertEquals(1, retryCalls)
            assertEquals(2, calls)
        } finally { directory.deleteRecursively() }
    }

    @Test fun durableStagingFailureNeverAuthorizesAudioRemovalAndRetryIsIdempotent() = runBlocking {
        val directory = Files.createTempDirectory("staging").toFile()
        try {
            val store = DurablePcmStore(File(directory, "audio"))
            val audio = store.create()
            store.openWriter(audio).use { it.append(byteArrayOf(1, 2), 2) }
            val blockedParent = File(directory, "blocked").apply { writeText("not a directory") }
            val recoveries = RecoveryStore(File(blockedParent, "text"))
            val admission = CaptureAdmission({ TranscriptionBackend { "all captured words" } }) { true }
            val client = TranscriptionClient(admission)
            assertTrue(runCatching {
                client.transcribeRecording(audio, requireNotNull(admission.acquire())) { text ->
                    recoveries.stageRecording(text, audio, store)
                }
            }.isFailure)
            assertEquals(audio.id, store.pending()?.id)
            assertTrue(blockedParent.delete())
            assertTrue(blockedParent.mkdir())
            client.transcribeRecording(audio, requireNotNull(admission.acquire())) { text ->
                recoveries.stageRecording(text, audio, store)
            }
            assertNull(store.pending())
            assertEquals("all captured words", RecoveryStore(File(blockedParent, "text")).snapshot().items.single().text)
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancelledServiceWaiterStillSavesFullTranscriptBeforeDeletingItsAudio() = runBlocking {
        val directory = Files.createTempDirectory("cancelled-session").toFile()
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = DurablePcmStore(File(directory, "audio"))
            val audio = store.create()
            store.openWriter(audio).use { it.append(byteArrayOf(1, 2), 2) }
            val recovery = RecoveryStore(File(directory, "text"))
            val begun = CompletableDeferred<Unit>()
            val result = CompletableDeferred<String>()
            val saved = CompletableDeferred<Unit>()
            val admission = CaptureAdmission({ TranscriptionBackend { begun.complete(Unit); result.await() } }) { true }
            val client = TranscriptionClient(admission, app)
            val waiter = async {
                client.transcribeRecording(audio, requireNotNull(admission.acquire())) { text ->
                    recovery.stageRecording(text, audio, store)
                    saved.complete(Unit)
                }
            }
            withTimeout(3000) { begun.await() }
            waiter.cancelAndJoin()
            assertEquals(audio.id, store.pending()?.id)
            result.complete("complete response after UI destruction")
            withTimeout(3000) { saved.await() }
            assertNull(store.pending())
            assertEquals("complete response after UI destruction", RecoveryStore(File(directory, "text")).snapshot().items.single().text)
        } finally { app.cancel(); directory.deleteRecursively() }
    }
}
