package com.cragnet.wysawyg

import kotlin.math.sqrt

object PcmLevel {
    /** Linear RMS of little-endian signed PCM16, normalized to 0..1. */
    fun normalized(bytes: ByteArray, length: Int = bytes.size): Float {
        require(length in 0..bytes.size)
        val samples = length / 2
        if (samples == 0) return 0f
        var sum = 0.0
        for (index in 0 until samples) {
            val offset = index * 2
            val sample = ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
            val normalized = sample / 32768.0
            sum += normalized * normalized
        }
        return sqrt(sum / samples).toFloat().coerceIn(0f, 1f)
    }
}
