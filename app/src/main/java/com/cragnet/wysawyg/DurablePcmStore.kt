package com.cragnet.wysawyg

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One pending capture, in noBackupFilesDir. No expiry and no implicit deletion. */
class DurablePcmStore(private val directory: File) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        // Fits Phonon's limit and bounds WAV/base64 working memory independently of capture length.
        const val SEGMENT_BYTES = BYTES_PER_SECOND * 60
    }

    data class Capture(val id: String, val directory: File) {
        val pcm: File get() = File(directory, "audio.pcm")
        val bytes: Long get() = pcm.length() / 2 * 2 // a torn final sample is not a complete PCM frame
        val segments: Int get() = ((bytes + SEGMENT_BYTES - 1) / SEGMENT_BYTES).toInt()
        fun savedSegment(index: Int): String? = File(directory, "$index.txt").takeIf { it.isFile }?.readText()

        fun saveSegment(index: Int, text: String) {
            require(index in 0 until segments)
            atomicWrite(File(directory, "$index.txt"), text.toByteArray(Charsets.UTF_8))
        }

        fun wav(index: Int): ByteArray {
            val offset = index.toLong() * SEGMENT_BYTES
            require(index >= 0 && offset < bytes)
            val count = minOf(SEGMENT_BYTES.toLong(), bytes - offset).toInt()
            val wav = ByteArray(count + 44)
            ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(count + 36); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(BYTES_PER_SECOND)
                putShort(2); putShort(16); put("data".toByteArray()); putInt(count)
            }
            RandomAccessFile(pcm, "r").use { it.seek(offset); it.readFully(wav, 44, count) }
            return wav
        }
    }

    @Synchronized fun pending(): Capture? = directory.listFiles()?.filter { it.isDirectory }
        ?.sortedBy { it.name }?.firstOrNull()?.let { Capture(it.name, it) }

    @Synchronized fun create(): Capture {
        check(pending() == null) { "Recover or discard the previous recording first" }
        check(directory.isDirectory || directory.mkdirs()) { "Could not create recording storage" }
        val id = UUID.randomUUID().toString()
        val folder = File(directory, id)
        check(folder.mkdir()) { "Could not create recording" }
        // A crash at any point leaves a recoverable directory, never permission to overwrite it.
        return Capture(id, folder).also { FileOutputStream(it.pcm).use { stream -> stream.fd.sync() } }
    }

    fun openWriter(capture: Capture): Writer {
        requireOwned(capture)
        return Writer(capture.pcm)
    }

    /** Call only for explicit discard, or after checking durable final text staging. */
    @Synchronized fun remove(capture: Capture): Boolean {
        requireOwned(capture)
        return !capture.directory.exists() || capture.directory.deleteRecursively()
    }

    private fun requireOwned(capture: Capture) {
        require(capture.directory.parentFile?.canonicalFile == directory.canonicalFile &&
            capture.directory.name == capture.id && capture.id.matches(Regex("[a-f0-9-]{36}")))
    }

    class Writer(file: File) : AutoCloseable {
        private val output = FileOutputStream(file, true)
        private var bytesSinceSync = 0
        fun append(bytes: ByteArray, count: Int) {
            require(count in 0..bytes.size)
            output.write(bytes, 0, count)
            bytesSinceSync += count
            // Writes survive process death; batch power-loss syncs to leave time for microphone reads.
            if (bytesSinceSync >= BYTES_PER_SECOND) {
                output.fd.sync()
                bytesSinceSync = 0
            }
        }
        override fun close() {
            try { output.fd.sync() } finally { output.close() }
        }
    }
}

private fun atomicWrite(file: File, bytes: ByteArray) {
    val temporary = File.createTempFile("segment-", ".tmp", file.parentFile)
    try {
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
}
