package com.cragnet.wysawyg

import java.io.File
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

    /** About -48 dBFS: below even quiet, distant speech. */
    const val SILENCE = 0.004f
    /** Speech rises well above a room's own hum; about 8 dB over the quiet floor. */
    const val SPEECH_OVER_FLOOR = 2.5f

    /** 100 ms RMS levels across a PCM16 file. */
    fun windows(file: File, windowBytes: Int = 3200): List<Float> {
        if (!file.isFile) return emptyList()
        val buffer = ByteArray(windowBytes)
        val levels = ArrayList<Float>()
        file.inputStream().buffered().use { input ->
            while (true) {
                val read = input.readNBytesCompat(buffer)
                if (read <= 0) break
                levels.add(normalized(buffer, read))
            }
        }
        return levels
    }

    fun loudestWindow(file: File): Float = windows(file).maxOrNull() ?: 0f

    /** True when nothing stands out: near-digital silence, or a steady hum with no louder moment. */
    fun holdsNoSpeech(file: File): Boolean {
        val levels = windows(file).sorted()
        if (levels.isEmpty()) return true
        val loudest = levels.last()
        val floor = levels[levels.size / 10]
        return loudest < SILENCE || loudest < floor * SPEECH_OVER_FLOOR
    }

    private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}
