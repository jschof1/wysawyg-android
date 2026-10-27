package com.cragnet.wysawyg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TranscriptionClientTest {
    @Test fun missingUnknownAndLegacyModeAlwaysStayOffline() {
        listOf(null, "", "cloud", "unexpected", "OFFLINE").forEach {
            assertEquals(TranscriptionMode.OFFLINE, TranscriptionMode.fromStored(it))
        }
        assertEquals(TranscriptionMode.CLOUD, TranscriptionMode.fromStored("CLOUD"))
        assertEquals(TranscriptionMode.OFFLINE, TranscriptionMode.fromImport(1, "CLOUD"))
    }

    @Test fun settingsV2RoundTripsCloudAndLegacyCredentialsDoNotOptIn() {
        val values = mapOf("api_key" to "existing private key", "alarma_url" to "https://example.test", "model" to "speech")
        val legacy = SettingsFormat.import(values)
        assertEquals("OFFLINE", legacy[TranscriptionMode.KEY])
        assertEquals(values["api_key"], legacy["api_key"])
        val cloud = SettingsFormat.export(values + (TranscriptionMode.KEY to "CLOUD"))
        assertEquals("CLOUD", SettingsFormat.import(cloud)[TranscriptionMode.KEY])
        assertEquals(values["api_key"], SettingsFormat.import(cloud)["api_key"])
        assertEquals("OFFLINE", SettingsFormat.import(cloud + (TranscriptionMode.KEY to "future"))[TranscriptionMode.KEY])
        assertFalse(SettingsFormat.import(emptyMap<String, Any>()).containsKey("api_key"))
    }

    @Test fun offlineFactoryNeverConstructsCloudAndFailureNeverFallsBack() = runBlocking {
        var cloudCalls = 0
        val router = TranscriptionRouter({ TranscriptionBackend { error("worker died") } }, {
            cloudCalls++
            TranscriptionBackend { "cloud" }
        })
        val failure = runCatching { router.select(TranscriptionMode.OFFLINE).transcribe(byteArrayOf()) }
        assertTrue(failure.isFailure)
        assertEquals(0, cloudCalls)
        assertEquals("cloud", router.select(TranscriptionMode.CLOUD).transcribe(byteArrayOf()))
        assertEquals(1, cloudCalls)
    }

    @Test fun reservationPreventsBothEditorsClaimingFifthSlot() {
        val directory = Files.createTempDirectory("admission").toFile()
        try {
            val store = RecoveryStore(File(directory, "recoveries"))
            repeat(4) { store.stage("saved $it") }
            val admission = CaptureAdmission { store.snapshot().canCapture }
            val overlay = requireNotNull(admission.acquire())
            assertNull("IME cannot race capture", admission.acquire())
            overlay.accept()
            overlay.cancelCapture() // UI destruction cannot release an accepted request
            assertNull(admission.acquire())
            store.stage("fifth complete result")
            overlay.complete()
            assertNull("No sixth item", admission.acquire())
            assertEquals(5, store.snapshot().items.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancelledCaptureReleasesReservationAndOldTokenCannotReleaseNewOne() {
        val admission = CaptureAdmission { true }
        val old = requireNotNull(admission.acquire())
        old.cancelCapture()
        val current = requireNotNull(admission.acquire())
        old.complete()
        assertNull(admission.acquire())
        current.cancelCapture()
        assertNotNull(admission.acquire())
    }

    @Test fun cancelledWaiterStillStagesFullTextOnceBeforeAdmissionReopens() = runBlocking {
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<String>()
        val staged = CompletableDeferred<String>()
        val calls = AtomicInteger()
        val admission = CaptureAdmission { true }
        val backend = TranscriptionBackend { started.complete(Unit); finish.await() }
        val client = TranscriptionClient(admission, { TranscriptionMode.OFFLINE }, TranscriptionRouter({ backend }, { error("HTTP") }), app)
        var inserted = false
        try {
            val waiter = async {
                client.transcribe(byteArrayOf()) {
                    assertNull("Slot stays reserved while staging", admission.acquire())
                    calls.incrementAndGet()
                    staged.complete(it)
                }
                coroutineContext.ensureActive()
                inserted = true
            }
            withTimeout(3000) { started.await() }
            waiter.cancelAndJoin()
            assertNull(admission.acquire())
            val text = "Whole transcript: café, Ελληνικά, 𐐀 and 😀"
            finish.complete(text)
            assertEquals(text, withTimeout(3000) { staged.await() })
            assertEquals(1, calls.get())
            assertFalse(inserted)
            withTimeout(3000) { while (admission.acquire()?.also { it.cancelCapture() } == null) kotlinx.coroutines.yield() }
        } finally { app.cancel() }
    }

    @Test fun activeWaiterResumesOnlyAfterStagingAndFailureReleasesSlot() = runBlocking {
        val admission = CaptureAdmission { true }
        var fail = false
        var staged = false
        val backend = TranscriptionBackend { if (fail) error("worker stopped") else "complete" }
        val client = TranscriptionClient(admission, { TranscriptionMode.OFFLINE }, TranscriptionRouter({ backend }, { error("HTTP") }))
        assertEquals("complete", client.transcribe(byteArrayOf()) { staged = true })
        assertTrue(staged)
        fail = true
        assertTrue(runCatching { client.transcribe(byteArrayOf()) { error("must not stage failure") } }.isFailure)
        requireNotNull(admission.acquire()).cancelCapture()
    }

    @Test fun interpretationShapesStagedTextAndFailureKeepsRawTranscript() = runBlocking {
        val admission = CaptureAdmission { true }
        val client = TranscriptionClient(admission, { TranscriptionMode.OFFLINE },
            TranscriptionRouter({ TranscriptionBackend { "Yeah, sure." } }, { error("HTTP") }))
        var staged = ""
        assertEquals("yeah sure", client.transcribe(byteArrayOf(), interpret = { "yeah sure" }) { staged = it })
        assertEquals("yeah sure", staged)
        assertEquals("Yeah, sure.", client.transcribe(byteArrayOf(), interpret = { error("model crashed") }) { staged = it })
        assertEquals("Yeah, sure.", staged)
        requireNotNull(admission.acquire()).cancelCapture()
    }

    @Test fun callbackFailureStillReleasesAdmission() = runBlocking {
        val admission = CaptureAdmission { true }
        val client = TranscriptionClient(admission, { TranscriptionMode.OFFLINE },
            TranscriptionRouter({ TranscriptionBackend { "text" } }, { error("HTTP") }))
        assertTrue(runCatching { client.transcribe(byteArrayOf()) { error("stage failed") } }.isFailure)
        requireNotNull(admission.acquire()).cancelCapture()
    }

    @Test fun stoppedApplicationScopeReleasesAcceptedReservation() = runBlocking {
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { it.cancel() }
        val admission = CaptureAdmission { true }
        val client = TranscriptionClient(admission, { TranscriptionMode.OFFLINE },
            TranscriptionRouter({ error("Stopped scope cannot enter native") }, { error("HTTP") }), app)
        assertTrue(runCatching { withTimeout(3000) { client.transcribe(byteArrayOf()) } }.isFailure)
        requireNotNull(admission.acquire()).cancelCapture()
    }

    @Test fun reusedReservationCannotReleaseOutstandingRequest() = runBlocking {
        val admission = CaptureAdmission { true }
        val reservation = requireNotNull(admission.acquire())
        reservation.accept()
        val client = TranscriptionClient(admission, { TranscriptionMode.OFFLINE },
            TranscriptionRouter({ error("Duplicate must not reach native") }, { error("HTTP") }))
        assertTrue(runCatching { client.transcribe(byteArrayOf(), reservation) }.isFailure)
        assertNull(admission.acquire())
        reservation.complete()
        requireNotNull(admission.acquire()).cancelCapture()
    }

    @Test fun acceptedCompleteResultWinsDeathAndDuplicatesAreIgnored() {
        val delivered = mutableListOf<Result<String>>()
        val gate = RequestCompletion<String>(42) { delivered.add(it) }
        assertFalse(gate.result(41) { error("Wrong request must not be read") })
        assertTrue(gate.result(42) { "complete 😀" })
        gate.failed(IllegalStateException("binder died"))
        assertFalse(gate.result(42) { "duplicate" })
        assertEquals(listOf("complete 😀"), delivered.map { it.getOrThrow() })
    }

    @Test fun deathDuringFullResultReadWaitsForAcceptedResult() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val complete = CountDownLatch(1)
        var value: Result<String>? = null
        val gate = RequestCompletion<String>(7) { value = it; complete.countDown() }
        val reader = Thread { gate.result(7) { reading.countDown(); check(release.await(3, TimeUnit.SECONDS)); "full" } }
        reader.start()
        assertTrue(reading.await(3, TimeUnit.SECONDS))
        val death = Thread { gate.failed(IllegalStateException("death")) }.apply { start() }
        release.countDown()
        assertTrue(complete.await(3, TimeUnit.SECONDS))
        reader.join(3000); death.join(3000)
        assertEquals("full", value?.getOrThrow())
    }

    @Test fun deadWorkerFailsOnceAndLateCallbackCannotReplaceFailure() {
        val delivered = mutableListOf<Result<String>>()
        val gate = RequestCompletion<String>(3) { delivered.add(it) }
        gate.failed(IllegalStateException("worker died"))
        gate.failed(IllegalStateException("disconnected"))
        assertFalse(gate.result(3) { "late" })
        assertEquals(1, delivered.size)
        assertTrue(delivered.single().isFailure)
    }
}
