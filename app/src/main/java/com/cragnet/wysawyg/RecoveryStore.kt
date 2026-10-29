package com.cragnet.wysawyg

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.CRC32

data class RecoveredTranscript(val id: String, val text: String, val createdAt: Long)

/** Stores transcript text only. Failed writes leave both the old file and new in-memory text intact. */
class RecoveryStore(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val CAPACITY = 5
        const val EXPIRY_MS = 24 * 60 * 60 * 1000L
        private const val MAX_BYTES = 1024 * 1024
        private const val FORMAT = 1
    }

    data class Snapshot(
        val items: List<RecoveredTranscript>,
        val unsavedIds: Set<String>,
        val unreadable: Boolean,
        val writeFailed: Boolean,
        val expiredCount: Int
    ) {
        val canCapture: Boolean get() = items.size < CAPACITY && unsavedIds.isEmpty() && !unreadable && !writeFailed
    }

    private val items = mutableListOf<RecoveredTranscript>()
    private var savedIds = emptySet<String>()
    private var unreadable = false
    private var writeFailed = false
    private var expiredCount = 0
    private val inserting = mutableSetOf<String>()

    @Synchronized fun tryBeginInsertion(id: String): Boolean {
        expire()
        return items.any { it.id == id } && inserting.add(id)
    }

    @Synchronized fun finishInsertion(id: String) { inserting.remove(id) }

    init {
        file.absoluteFile.parentFile?.listFiles { _, name ->
            name.startsWith("${file.name}.") && name.endsWith(".tmp")
        }?.forEach { it.delete() }
        if (file.exists()) {
            try {
                require(file.length() <= MAX_BYTES)
                val bytes = file.readBytes()
                require(bytes.size >= 16)
                val payload = bytes.copyOf(bytes.size - 8)
                val checksum = DataInputStream(ByteArrayInputStream(bytes, bytes.size - 8, 8)).readLong()
                require(CRC32().apply { update(payload) }.value == checksum)
                DataInputStream(ByteArrayInputStream(payload)).use { input ->
                    require(input.readInt() == FORMAT)
                    val count = input.readInt()
                    require(count in 0..CAPACITY)
                    val loaded = List(count) {
                        val id = input.readUTF()
                        val created = input.readLong()
                        val size = input.readInt()
                        require(size in 1..MAX_BYTES && size <= input.available())
                        val text = ByteArray(size).also { input.readFully(it) }.toString(Charsets.UTF_8)
                        require(id.isNotBlank() && (text.isNotBlank() || text.contains('\n')))
                        RecoveredTranscript(id, text, created)
                    }
                    require(input.available() == 0 && loaded.map { it.id }.toSet().size == count)
                    items.addAll(loaded)
                }
                savedIds = items.map { it.id }.toSet()
                expire()
            } catch (_: Exception) {
                unreadable = true
            }
        }
    }

    @Synchronized fun snapshot(): Snapshot {
        expire()
        return Snapshot(items.toList(), items.map { it.id }.toSet() - savedIds, unreadable, writeFailed, expiredCount)
    }

    @Synchronized fun stage(text: String, id: String = UUID.randomUUID().toString()): RecoveredTranscript {
        require(text.isNotBlank() || text.contains('\n'))
        expire()
        items.firstOrNull { it.id == id }?.let {
            if (id !in savedIds) persist()
            return it
        }
        val item = RecoveredTranscript(id, text, now())
        items.add(item)
        persist()
        return item
    }

    /** Audio is disposable only after the entire transcript has been committed to durable storage. */
    fun stageRecording(text: String, audio: DurablePcmStore.Capture, audioStore: DurablePcmStore): RecoveredTranscript {
        check(text.isNotBlank() || text.contains('\n')) { "No speech detected. Audio kept for retry or discard." }
        val item = stage(text, audio.id)
        val saved = snapshot()
        check(item.id !in saved.unsavedIds && !saved.unreadable && !saved.writeFailed) {
            "Text could not be saved. Audio kept; free storage and retry."
        }
        check(audioStore.remove(audio)) { "Text saved, but audio removal failed. Discard audio to finish cleanup." }
        return item
    }

    @Synchronized fun remove(id: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return true
        val removed = items.removeAt(index)
        val wasSaved = id in savedIds
        if (persist() || !wasSaved) return true
        items.add(index, removed)
        return false
    }

    @Synchronized fun discardUnreadable(): Boolean {
        if (!unreadable) return true
        unreadable = false
        if (persist()) return true
        unreadable = true
        return false
    }

    @Synchronized fun retrySave(): Boolean = persist()

    private fun expire() {
        val expired = items.filter { it.id in savedIds && now() - it.createdAt >= EXPIRY_MS }
        if (expired.isEmpty()) return
        items.removeAll(expired.toSet())
        expiredCount += expired.size
        persist()
    }

    private fun persist(): Boolean {
        if (unreadable) return false
        var temporary: File? = null
        try {
            require(items.size <= CAPACITY) { "Recovery capacity reached" }
            val payload = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(FORMAT)
                    out.writeInt(items.size)
                    for (item in items) {
                        val text = item.text.toByteArray(Charsets.UTF_8)
                        require(text.size <= MAX_BYTES)
                        out.writeUTF(item.id)
                        out.writeLong(item.createdAt)
                        out.writeInt(text.size)
                        out.write(text)
                    }
                }
            }.toByteArray()
            require(payload.size + 8 <= MAX_BYTES) { "Recovery storage is full" }
            val directory = requireNotNull(file.absoluteFile.parentFile)
            check(directory.isDirectory || directory.mkdirs())
            temporary = File.createTempFile("${file.name}.", ".tmp", directory)
            FileOutputStream(temporary).use { stream ->
                val out = DataOutputStream(stream)
                out.write(payload)
                out.writeLong(CRC32().apply { update(payload) }.value)
                out.flush()
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            savedIds = items.map { it.id }.toSet()
            writeFailed = false
            return true
        } catch (_: Exception) {
            writeFailed = true
            return false
        } finally {
            temporary?.delete()
        }
    }
}
