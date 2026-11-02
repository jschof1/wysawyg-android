package com.cragnet.wysawyg

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmLevelTest {
    private fun pcm(vararg samples: Short) = ByteBuffer.allocate(samples.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()

    @Test fun silenceAndEmptyReadsHaveZeroLevel() {
        assertEquals(0f, PcmLevel.normalized(ByteArray(128)), 0f)
        assertEquals(0f, PcmLevel.normalized(byteArrayOf(127)), 0f)
        assertEquals(0f, PcmLevel.normalized(byteArrayOf()), 0f)
    }

    @Test fun knownPositiveAndNegativeSamplesProduceNormalizedRms() {
        assertEquals(0.5f, PcmLevel.normalized(pcm(16384, -16384)), 0.00001f)
        assertEquals(1f, PcmLevel.normalized(pcm(Short.MIN_VALUE)), 0f)
        assertEquals(32767f / 32768, PcmLevel.normalized(pcm(Short.MAX_VALUE)), 0.00001f)
        assertEquals(0.353553f, PcmLevel.normalized(pcm(0, 16384)), 0.00001f)
    }

    @Test fun onlyCompleteSamplesInActualReadAreMeasured() {
        assertEquals(0f, PcmLevel.normalized(pcm(0, Short.MAX_VALUE), 2), 0f)
        assertEquals(0.5f, PcmLevel.normalized(pcm(-16384, Short.MAX_VALUE), 3), 0f)
    }

    @Test fun loudestWindowSeparatesRoomHissFromSpeech() {
        val file = java.io.File.createTempFile("level", ".pcm").apply { deleteOnExit() }
        val hiss = pcm(*ShortArray(16000) { if (it % 2 == 0) 40 else -40 })
        val quietSpeech = pcm(*ShortArray(1600) { if (it % 2 == 0) 200 else -200 })
        file.writeBytes(hiss)
        assertTrue(PcmLevel.loudestWindow(file) < PcmLevel.SILENCE)
        file.appendBytes(quietSpeech) // about -44 dBFS: kept, never auto-discarded
        assertTrue(PcmLevel.loudestWindow(file) > PcmLevel.SILENCE)
        file.writeBytes(hiss)
        file.appendBytes(pcm(*ShortArray(1600) { if (it % 2 == 0) 3000 else -3000 }))
        assertTrue(PcmLevel.loudestWindow(file) > PcmLevel.SILENCE)
        assertEquals(0f, PcmLevel.loudestWindow(java.io.File(file.path + ".missing")), 0f)
    }

    @Test fun steadyRoomHumHoldsNoSpeechButAnyClearRiseIsKept() {
        val file = java.io.File.createTempFile("hum", ".pcm").apply { deleteOnExit() }
        // Measured on a Galaxy A55 with nobody speaking: every window between 0.012 and 0.015.
        val hum = pcm(*ShortArray(16000 * 5) { ((if (it % 2 == 0) 1 else -1) * (400 + (it / 1600 % 3) * 40)).toShort() })
        file.writeBytes(hum)
        assertTrue(PcmLevel.loudestWindow(file) > PcmLevel.SILENCE)
        assertTrue(PcmLevel.holdsNoSpeech(file))
        file.appendBytes(pcm(*ShortArray(3200) { if (it % 2 == 0) 1500 else -1500 }))
        assertTrue(!PcmLevel.holdsNoSpeech(file))
        file.writeBytes(ByteArray(0))
        assertTrue(PcmLevel.holdsNoSpeech(file))
    }
}
