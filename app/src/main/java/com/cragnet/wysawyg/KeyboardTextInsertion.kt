package com.cragnet.wysawyg

import android.os.Build
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal enum class KeyboardSavedTextResult {
    INSERTED, RETAINED, UNAVAILABLE, BUSY, STORAGE_FAILED, SENT_UNCONFIRMED, INSERTED_RETAINED
}

internal enum class KeyboardCommitResult { CONFIRMED, UNCONFIRMED, FAILED }

private data class KeyboardEditorText(val text: String, val start: Int, val end: Int, val offset: Int)

private const val KEYBOARD_READBACK_LIMIT = 8192

private fun readKeyboardEditor(connection: InputConnection): KeyboardEditorText? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val surrounding = connection.getSurroundingText(KEYBOARD_READBACK_LIMIT - 1, 1, 0) ?: return null
        if (surrounding.offset < 0 || surrounding.text.length > KEYBOARD_READBACK_LIMIT) return null
        return KeyboardEditorText(surrounding.text.toString(), surrounding.selectionStart,
            surrounding.selectionEnd, surrounding.offset)
    }
    val extracted = connection.getExtractedText(ExtractedTextRequest().apply {
        hintMaxChars = KEYBOARD_READBACK_LIMIT
    }, 0) ?: return null
    val text = extracted.text ?: return null
    // A partial update is not a complete readback, and cannot prove insertion.
    if (text.length > KEYBOARD_READBACK_LIMIT || extracted.startOffset < 0 ||
        extracted.partialStartOffset != -1 || extracted.partialEndOffset != -1) return null
    return KeyboardEditorText(text.toString(), extracted.selectionStart, extracted.selectionEnd, extracted.startOffset)
}

internal suspend fun commitKeyboardText(
    connection: InputConnection,
    spoken: String,
    isCurrentEditor: () -> Boolean
): KeyboardCommitResult {
    var dispatched = false
    return try {
        val prepared = withContext(Dispatchers.Main.immediate) {
            isCurrentEditor() && connection.finishComposingText() && isCurrentEditor()
        }
        if (!prepared) {
            return KeyboardCommitResult.FAILED
        }
        val before = withContext(Dispatchers.IO) { readKeyboardEditor(connection) }
            ?: return KeyboardCommitResult.FAILED
        val plan = DirectTextInsertion.plan(before.text, before.start, before.end, before.offset, spoken)
            ?: return KeyboardCommitResult.FAILED
        val accepted = withContext(Dispatchers.Main.immediate) {
            if (!isCurrentEditor()) false else {
                dispatched = true
                connection.commitText(plan.text, 1)
            }
        }
        if (!accepted) return if (dispatched) KeyboardCommitResult.UNCONFIRMED else KeyboardCommitResult.FAILED
        if (plan.text.length >= KEYBOARD_READBACK_LIMIT) return KeyboardCommitResult.UNCONFIRMED
        // Our commit changes selection generation. Confirm only against the original bound connection.
        val after = withContext(Dispatchers.IO) { readKeyboardEditor(connection) }
        if (after != null && plan.confirmed(after.text, after.start, after.end, after.offset)) {
            KeyboardCommitResult.CONFIRMED
        } else KeyboardCommitResult.UNCONFIRMED
    } catch (e: CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        if (dispatched) KeyboardCommitResult.UNCONFIRMED else KeyboardCommitResult.FAILED
    }
}

/** The IME binds its editor at a tap; the shared store guard spans preparation, send and readback. */
internal suspend fun insertKeyboardSavedText(
    store: RecoveryStore,
    id: String,
    isCurrentEditor: () -> Boolean,
    commit: suspend (String) -> KeyboardCommitResult
): KeyboardSavedTextResult {
    if (!store.tryBeginInsertion(id)) {
        return if (store.snapshot().items.any { it.id == id }) KeyboardSavedTextResult.BUSY
        else KeyboardSavedTextResult.UNAVAILABLE
    }
    try {
        val snapshot = store.snapshot()
        val item = snapshot.items.firstOrNull { it.id == id }
            ?: return KeyboardSavedTextResult.UNAVAILABLE
        if (!isCurrentEditor()) return KeyboardSavedTextResult.RETAINED
        if (snapshot.unreadable) return KeyboardSavedTextResult.STORAGE_FAILED
        // remove() may drop an unsaved item even if persistence fails. Make it durable first.
        if ((snapshot.unsavedIds.isNotEmpty() || snapshot.writeFailed) && !withContext(Dispatchers.IO) { store.retrySave() }) {
            return KeyboardSavedTextResult.STORAGE_FAILED
        }
        val ready = store.snapshot()
        if (ready.items.none { it.id == id }) return KeyboardSavedTextResult.UNAVAILABLE
        if (ready.unreadable || ready.writeFailed || id in ready.unsavedIds) {
            return KeyboardSavedTextResult.STORAGE_FAILED
        }
        if (!isCurrentEditor()) return KeyboardSavedTextResult.RETAINED
        when (commit(item.text)) {
            KeyboardCommitResult.FAILED -> return KeyboardSavedTextResult.RETAINED
            KeyboardCommitResult.UNCONFIRMED -> return KeyboardSavedTextResult.SENT_UNCONFIRMED
            KeyboardCommitResult.CONFIRMED -> Unit
        }
        val removed = withContext(NonCancellable + Dispatchers.IO) { store.remove(id) }
        return if (removed) KeyboardSavedTextResult.INSERTED else KeyboardSavedTextResult.INSERTED_RETAINED
    } finally {
        store.finishInsertion(id)
    }
}
