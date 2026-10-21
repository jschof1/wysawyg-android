package com.cragnet.wysawyg

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

/** Regular files avoid pipe backpressure. The open FD is the only owner after unlink. */
internal object PrivateFiles {
    const val MAX_WAV_BYTES = 16000 * 2 * 300 + 4096
    const val MAX_TEXT_BYTES = 1024 * 1024 - 4096
    fun descriptor(context: Context, bytes: ByteArray): ParcelFileDescriptor {
        val file = File.createTempFile("phonon-", ".tmp", context.cacheDir)
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        try {
            check(file.delete()) { "Could not unlink private transcription file" }
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)).use { it.write(bytes) }
            android.system.Os.lseek(fd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
            return fd
        } catch (e: Exception) {
            fd.close()
            throw e
        } finally { file.delete() }
    }

    fun readText(fd: ParcelFileDescriptor): String = ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
        val size = fd.statSize
        require(size in 0..MAX_TEXT_BYTES.toLong()) { "Invalid transcription result size" }
        val bytes = ByteArray(size.toInt())
        java.io.DataInputStream(input).readFully(bytes)
        check(input.read() == -1) { "Transcription result grew unexpectedly" }
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}
