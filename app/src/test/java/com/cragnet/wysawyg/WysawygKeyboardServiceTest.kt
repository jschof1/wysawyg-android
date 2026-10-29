package com.cragnet.wysawyg

import android.text.InputType
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WysawygKeyboardServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = File(temporary.root, "keyboard-recovery.bin")

    @Test fun rejectsNullAndEveryPasswordEditor() {
        listOf(
            InputType.TYPE_NULL,
            InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ).forEach { assertFalse(EditorInputPolicy.allowsText(it)) }
        assertTrue(EditorInputPolicy.allowsText(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertTrue(EditorInputPolicy.allowsText(InputType.TYPE_CLASS_NUMBER))
    }

    @Test fun confirmedInsertionUsesCompleteSavedTextAndDurablyRemovesOnlyThatItem() = runBlocking {
        val file = file()
        val store = RecoveryStore(file)
        val previous = store.stage("Keep this earlier transcript")
        val item = store.stage("Complete transcript 🗣\n".repeat(100))
        var inserted: String? = null
        val result = insertKeyboardSavedText(store, item.id, { true }) {
            inserted = it
            KeyboardCommitResult.CONFIRMED
        }
        assertEquals(item.text, inserted)
        assertEquals(KeyboardSavedTextResult.INSERTED, result)
        assertEquals(listOf(previous), RecoveryStore(file).snapshot().items)
    }

    @Test fun rejectedOrUnconfirmedInsertionKeepsDurableTextAndReleasesGuard() = runBlocking {
        val file = file()
        val store = RecoveryStore(file)
        val item = store.stage("Keep until the entire insertion is confirmed")
        for ((receipt, expected) in listOf(
            KeyboardCommitResult.FAILED to KeyboardSavedTextResult.RETAINED,
            KeyboardCommitResult.UNCONFIRMED to KeyboardSavedTextResult.SENT_UNCONFIRMED
        )) {
            assertEquals(expected, insertKeyboardSavedText(store, item.id, { true }) { receipt })
            assertEquals(listOf(item), RecoveryStore(file).snapshot().items)
            assertTrue(store.tryBeginInsertion(item.id))
            store.finishInsertion(item.id)
        }
    }

    @Test fun editorChangeDuringPreparationPreventsSend() = runBlocking {
        val store = RecoveryStore(file())
        val item = store.stage("Keep when the editor generation changes")
        var checks = 0
        var sent = false
        assertEquals(KeyboardSavedTextResult.RETAINED, insertKeyboardSavedText(store, item.id, {
            ++checks == 1
        }) {
            sent = true
            KeyboardCommitResult.CONFIRMED
        })
        assertFalse(sent)
        assertEquals(listOf(item), store.snapshot().items)
    }

    @Test fun anotherSurfaceHoldingTheGuardPreventsSendWithoutReleasingItsGuard() = runBlocking {
        val store = RecoveryStore(file())
        val item = store.stage("Another surface is inserting this")
        assertTrue(store.tryBeginInsertion(item.id))
        assertEquals(KeyboardSavedTextResult.BUSY, insertKeyboardSavedText(store, item.id, { true }) {
            fail("Must not send the same transcript twice")
            KeyboardCommitResult.CONFIRMED
        })
        assertFalse(store.tryBeginInsertion(item.id))
        store.finishInsertion(item.id)
        assertTrue(store.tryBeginInsertion(item.id))
        store.finishInsertion(item.id)
    }

    @Test fun confirmedCommitMayChangeSelectionGeneration() = runBlocking {
        val store = RecoveryStore(file())
        val item = store.stage("The successful commit moves the cursor")
        var originalGeneration = true
        assertEquals(KeyboardSavedTextResult.INSERTED, insertKeyboardSavedText(store, item.id, { originalGeneration }) {
            originalGeneration = false
            KeyboardCommitResult.CONFIRMED
        })
        assertTrue(store.snapshot().items.isEmpty())
    }

    @Test fun cancellationDuringReadbackRetainsTextAndReleasesGuard() = runBlocking {
        val store = RecoveryStore(file())
        val item = store.stage("Sent but not yet confirmed")
        val reading = CompletableDeferred<Unit>()
        val readback = CompletableDeferred<KeyboardCommitResult>()
        val operation = launch {
            insertKeyboardSavedText(store, item.id, { true }) {
                reading.complete(Unit)
                readback.await()
            }
        }
        reading.await()
        assertFalse(store.tryBeginInsertion(item.id))
        operation.cancelAndJoin()
        assertEquals(listOf(item), store.snapshot().items)
        assertTrue(store.tryBeginInsertion(item.id))
        store.finishInsertion(item.id)
    }

    @Test fun unsavedTextIsNotSentOrRemovedWhenStorageStillFails() = runBlocking {
        val blocked = temporary.newFile("not-a-directory")
        val store = RecoveryStore(File(blocked, "recovery.bin"))
        val item = store.stage("Keep in memory")
        assertEquals(KeyboardSavedTextResult.STORAGE_FAILED, insertKeyboardSavedText(store, item.id, { true }) {
            fail("Must make text durable before sending")
            KeyboardCommitResult.CONFIRMED
        })
        assertEquals(listOf(item), store.snapshot().items)
        assertTrue(item.id in store.snapshot().unsavedIds)
        assertTrue(store.tryBeginInsertion(item.id))
        store.finishInsertion(item.id)
    }

    @Test fun recoveredStorageIsRetriedBeforeSend() = runBlocking {
        val directory = temporary.newFile("temporarily-blocked")
        val file = File(directory, "recovery.bin")
        val store = RecoveryStore(file)
        val item = store.stage("Previously unsaved")
        assertTrue(directory.delete())
        assertTrue(directory.mkdir())
        assertEquals(KeyboardSavedTextResult.INSERTED, insertKeyboardSavedText(store, item.id, { true }) {
            assertEquals(listOf(item), RecoveryStore(file).snapshot().items)
            KeyboardCommitResult.CONFIRMED
        })
        assertTrue(RecoveryStore(file).snapshot().items.isEmpty())
    }

    @Test fun confirmedInsertionWithFailedDurableRemovalRetainsText() = runBlocking {
        val file = file()
        val store = RecoveryStore(file)
        val item = store.stage("Inserted, but still retained")
        assertEquals(KeyboardSavedTextResult.INSERTED_RETAINED, insertKeyboardSavedText(store, item.id, { true }) {
            assertTrue(file.delete())
            assertTrue(file.mkdir())
            KeyboardCommitResult.CONFIRMED
        })
        assertEquals(listOf(item), store.snapshot().items)
        assertTrue(store.tryBeginInsertion(item.id))
        store.finishInsertion(item.id)
    }

    @Test fun unexpectedFailureAlwaysReleasesGuardAndRetainsText() = runBlocking {
        val store = RecoveryStore(file())
        val item = store.stage("Keep on unexpected failure")
        try {
            insertKeyboardSavedText(store, item.id, { true }) { throw IllegalStateException("Test failure") }
            fail("Expected failure")
        } catch (_: IllegalStateException) {
            assertEquals(listOf(item), store.snapshot().items)
            assertTrue(store.tryBeginInsertion(item.id))
            store.finishInsertion(item.id)
        }
    }
}
