package com.cragnet.wysawyg

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = File(temporary.root, "recoveries.bin")

    @Test fun completeTextSurvivesRestartAndExplicitRemoval() {
        val file = file()
        val text = "A complete transcript 🗣\n".repeat(4000)
        val item = RecoveryStore(file).stage(text)
        val restored = RecoveryStore(file)
        assertEquals(listOf(item), restored.snapshot().items)
        assertTrue(restored.snapshot().unsavedIds.isEmpty())
        assertTrue(restored.remove(item.id))
        assertTrue(RecoveryStore(file).snapshot().items.isEmpty())
    }

    @Test fun capacityPausesCaptureWithoutReplacingAnything() {
        val file = file()
        val store = RecoveryStore(file)
        val items = List(RecoveryStore.CAPACITY) { store.stage("Transcript $it") }
        assertFalse(store.snapshot().canCapture)
        assertEquals(items, RecoveryStore(file).snapshot().items)
        val overflow = store.stage("Already completed concurrent recording")
        assertEquals(items + overflow, store.snapshot().items)
        assertEquals(setOf(overflow.id), store.snapshot().unsavedIds)
        assertEquals(items, RecoveryStore(file).snapshot().items)
        assertTrue(store.remove(items.first().id))
        assertTrue(store.snapshot().unsavedIds.isEmpty())
    }

    @Test fun expiresSavedItemsAfterTwentyFourHours() {
        var clock = 100L
        val file = file()
        val store = RecoveryStore(file) { clock }
        store.stage("Old words")
        clock += RecoveryStore.EXPIRY_MS - 1
        assertEquals(1, store.snapshot().items.size)
        clock++
        assertTrue(store.snapshot().items.isEmpty())
        assertEquals(1, store.snapshot().expiredCount)
        assertTrue(RecoveryStore(file) { clock }.snapshot().items.isEmpty())
    }

    @Test fun corruptFileIsNotOverwrittenWithoutExplicitDiscard() {
        val file = file()
        file.writeText("broken storage")
        val original = file.readBytes()
        val store = RecoveryStore(file)
        assertTrue(store.snapshot().unreadable)
        assertFalse(store.snapshot().canCapture)
        val item = store.stage("Keep in memory")
        assertTrue(original.contentEquals(file.readBytes()))
        assertEquals(setOf(item.id), store.snapshot().unsavedIds)
        assertTrue(store.discardUnreadable())
        assertEquals(listOf(item), RecoveryStore(file).snapshot().items)
    }

    @Test fun interruptedTemporaryWriteCannotReplaceLastGoodCollection() {
        val file = file()
        val item = RecoveryStore(file).stage("Durable words")
        File(temporary.root, "recoveries-interrupted.tmp").writeText("partial write")
        assertEquals(listOf(item), RecoveryStore(file).snapshot().items)
        file.writeBytes(file.readBytes().dropLast(2).toByteArray())
        assertTrue(RecoveryStore(file).snapshot().unreadable)
    }

    @Test fun diskFailureRetainsWholeTextAndBlocksNewCapture() {
        val blocked = temporary.newFile("not-a-directory")
        val store = RecoveryStore(File(blocked, "recoveries.bin"))
        val text = "Never truncate this transcript".repeat(100)
        val item = store.stage(text)
        assertEquals(text, store.snapshot().items.single().text)
        assertEquals(setOf(item.id), store.snapshot().unsavedIds)
        assertTrue(store.snapshot().writeFailed)
        assertFalse(store.snapshot().canCapture)
    }

    @Test fun failedRemovalKeepsRecoveryAndOversizeTextIsNotTruncated() {
        val file = file()
        val store = RecoveryStore(file)
        val item = store.stage("Saved text")
        file.delete()
        file.mkdir()
        assertFalse(store.remove(item.id))
        assertEquals(listOf(item), store.snapshot().items)
        val big = store.stage("x".repeat(1024 * 1024 + 1))
        assertEquals(1024 * 1024 + 1, store.snapshot().items.last().text.length)
        assertTrue(big.id in store.snapshot().unsavedIds)
    }
}
