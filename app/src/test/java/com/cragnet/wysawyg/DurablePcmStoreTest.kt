package com.cragnet.wysawyg

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class DurablePcmStoreTest {
    private fun withStore(block: (File, DurablePcmStore) -> Unit) {
        val root = Files.createTempDirectory("durable-pcm").toFile()
        try { block(root, DurablePcmStore(root)) } finally { root.deleteRecursively() }
    }

    @Test fun writtenPrefixIsRecoverableBeforeWriterClosesAndAfterStoreRecreation() = withStore { root, store ->
        val capture = store.create()
        store.openWriter(capture).use { writer ->
            writer.append(byteArrayOf(1, 2, 3, 4), 4)
            val recovered = requireNotNull(DurablePcmStore(root).pending())
            assertEquals(capture.id, recovered.id)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), recovered.wav(0).copyOfRange(44, 48))
        }
        assertEquals(4L, requireNotNull(DurablePcmStore(root).pending()).bytes)
    }

    @Test fun failedAppendDoesNotDiscardThePrefixAlreadyWritten() = withStore { root, store ->
        val capture = store.create()
        val writer = store.openWriter(capture)
        writer.append(byteArrayOf(1, 2, 3, 4), 4)
        writer.close()
        assertTrue(runCatching { writer.append(byteArrayOf(5, 6), 2) }.isFailure)
        val reopened = requireNotNull(DurablePcmStore(root).pending())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), reopened.wav(0).copyOfRange(44, 48))
    }

    @Test fun partialFinalSampleDoesNotLoseEarlierSamples() = withStore { _, store ->
        val capture = store.create()
        store.openWriter(capture).use { it.append(byteArrayOf(1, 2, 3), 3) }
        assertEquals(2L, capture.bytes)
        assertEquals(46, capture.wav(0).size)
        assertEquals(2, ByteBuffer.wrap(capture.wav(0)).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }

    @Test fun pendingCaptureCannotBeOverwrittenAndStaleCleanupCannotDeleteNewCapture() = withStore { _, store ->
        val first = store.create()
        assertTrue(runCatching { store.create() }.isFailure)
        assertTrue(store.remove(first))
        val second = store.create()
        assertTrue(store.remove(first))
        assertEquals(second.id, store.pending()?.id)
    }

    @Test fun emptyCaptureAfterInterruptedStartStillNeedsExplicitDiscard() = withStore { root, store ->
        val capture = store.create()
        assertEquals(0, capture.segments)
        assertTrue(runCatching { DurablePcmStore(root).create() }.isFailure)
        assertTrue(store.remove(capture))
        assertNull(store.pending())
    }

    @Test fun recordingsBeyondFiveMinutesUseBoundedSegmentsWithoutDroppingTail() = withStore { root, store ->
        val capture = store.create()
        val minute = ByteArray(DurablePcmStore.SEGMENT_BYTES) { 7 }
        store.openWriter(capture).use { writer ->
            repeat(6) { writer.append(minute, minute.size) }
            writer.append(byteArrayOf(9, 10), 2)
        }
        val reopened = requireNotNull(DurablePcmStore(root).pending())
        assertEquals(7, reopened.segments)
        assertEquals(6L * minute.size + 2, reopened.bytes)
        assertEquals(DurablePcmStore.SEGMENT_BYTES + 44, reopened.wav(5).size)
        assertArrayEquals(byteArrayOf(9, 10), reopened.wav(6).copyOfRange(44, 46))
    }

    @Test fun partialTextCheckpointSurvivesRestartAndDoesNotDeleteAudio() = withStore { root, store ->
        val capture = store.create()
        store.openWriter(capture).use { it.append(byteArrayOf(1, 2), 2) }
        capture.saveSegment(0, "Complete segment café 😀")
        val recovered = requireNotNull(DurablePcmStore(root).pending())
        assertEquals("Complete segment café 😀", recovered.savedSegment(0))
        assertEquals(2L, recovered.bytes)
    }
}
