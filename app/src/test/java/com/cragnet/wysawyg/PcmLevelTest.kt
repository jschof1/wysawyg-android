package com.cragnet.wysawyg

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
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
}
