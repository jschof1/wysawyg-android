package com.cragnet.wysawyg

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Observes the visible editor and inserts directly, without touching the clipboard. */
class TextInjectorService : AccessibilityService() {
    data class Target(
        val node: AccessibilityNodeInfo,
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val generation: Long,
        val reportedText: String = text,
        val emptyInputEpoch: Long? = null
    )
    enum class InsertResult { INSERTED, TARGET_CHANGED, UNSUPPORTED }

    companion object {
        var instance: TextInjectorService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var editor: AccessibilityNodeInfo? = null
    private var keyboardVisible = false
    private var generation = 0L
    private var inputEpoch = 0L
    private val refresh = Runnable { refreshEditor() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        WysawygLogger.init(this)
        if (Build.VERSION.SDK_INT >= 33) {
            serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR }
        }
        refreshEditor()
    }

    @RequiresApi(33)
    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) { inputEpoch++ }
        override fun onFinishInput() {
            // Observe the existing keyboard's editor without changing composing text.
            inputEpoch++
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window events include the keyboard being dismissed without losing editor focus.
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 50)
    }

    fun refreshEditor() {
        try {
            val visibleWindows = windows
            val keyboard = visibleWindows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val keyboardBounds = Rect().also { keyboard?.getBoundsInScreen(it) }
            val application = visibleWindows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            }
            val candidate = findFocusedEditor(application?.root)
            if (candidate != editor || keyboardVisible != (keyboard != null)) generation++
            editor = candidate
            keyboardVisible = keyboard != null
            OverlayService.updateEditor(candidate != null && keyboardVisible, keyboardBounds)
        } catch (e: Exception) {
            editor = null
            keyboardVisible = false
            generation++
            OverlayService.updateEditor(false, Rect())
            WysawygLogger.e("Unable to inspect focused editor", e)
        }
    }

    private fun findFocusedEditor(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        fun usable(node: AccessibilityNodeInfo): Boolean =
            node.isEditable && node.isFocused && node.isVisibleToUser && !node.isPassword
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf(::usable)?.let { return it }

        // Some virtual editor providers return the surrounding container for input
        // focus. Its descendants still expose the actual focused editable node.
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (usable(node)) return node
            for (index in node.childCount - 1 downTo 0) {
                node.getChild(index)?.let { pending.add(it) }
            }
        }
        return null
    }

    fun captureTarget(): Target? {
        refreshEditor()
        val node = editor?.takeIf { keyboardVisible && it.refresh() } ?: return null
        return Target(node, editorText(node), node.textSelectionStart, node.textSelectionEnd, generation)
    }

    private fun editorText(node: AccessibilityNodeInfo): String {
        // Android may expose an empty editor's hint as its text. The flag distinguishes
        // placeholders from actual content, even when someone types the same words.
        return if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
    }

    /** Confirms an empty input buffer when an app exposes an unmarked placeholder. */
    suspend fun captureTargetForDictation(): Target? {
        val target = withContext(Dispatchers.Main.immediate) { captureTarget() } ?: return null
        val input = readInputState(target.node)
        return withContext(Dispatchers.Main.immediate) {
            if (currentTarget(target) == null) return@withContext null
            when {
                input?.empty == true -> target.copy(text = "", emptyInputEpoch = input.epoch)
                input != null && target.node.isShowingHintText -> null // Conflicting editor metadata.
                input == null && target.text.isNotEmpty() && target.selectionStart < 0 && target.selectionEnd < 0 -> null
                else -> target
            }
        }
    }

    private data class InputState(val empty: Boolean, val epoch: Long)

    private suspend fun readInputState(node: AccessibilityNodeInfo): InputState? {
        if (Build.VERSION.SDK_INT < 33) return null
        val request = withContext(Dispatchers.Main.immediate) {
            val method = inputMethod ?: return@withContext null
            if (!method.currentInputStarted || method.currentInputEditorInfo?.packageName != node.packageName?.toString()) {
                return@withContext null
            }
            method.currentInputConnection?.let { it to inputEpoch }
        } ?: return null
        // Only one character on either side is needed to distinguish empty from entered
        // content. Do not block the UI thread or read/truncate an entire long message.
        val surrounding = withContext(Dispatchers.IO) { request.first.getSurroundingText(1, 1, 0) } ?: return null
        return withContext(Dispatchers.Main.immediate) {
            if (inputEpoch != request.second || inputMethod?.currentInputEditorInfo?.packageName != node.packageName?.toString()) {
                null
            } else {
                InputState(surrounding.text.isEmpty() && surrounding.offset == 0 &&
                    surrounding.selectionStart == 0 && surrounding.selectionEnd == 0, request.second)
            }
        }
    }

    suspend fun insertForDictation(target: Target, text: String): InsertResult {
        val epoch = target.emptyInputEpoch
            ?: return withContext(Dispatchers.Main.immediate) { insert(target, text) }
        if (!withContext(Dispatchers.Main.immediate) { inputEpoch == epoch && currentTarget(target) != null }) {
            return InsertResult.TARGET_CHANGED
        }
        val input = readInputState(target.node)
        return withContext(Dispatchers.Main.immediate) {
            val current = currentTarget(target)
            if (current == null || inputEpoch != epoch || input?.epoch != epoch || !input.empty) {
                InsertResult.TARGET_CHANGED
            } else {
                writeText(current, target, text)
            }
        }
    }

    private fun currentTarget(target: Target): AccessibilityNodeInfo? {
        refreshEditor()
        val current = editor ?: return null
        if (!keyboardVisible || target.generation != generation || current != target.node ||
            !current.refresh() || editorText(current) != target.reportedText ||
            current.textSelectionStart != target.selectionStart || current.textSelectionEnd != target.selectionEnd) return null
        return current
    }

    fun insert(target: Target, text: String): InsertResult {
        // Targets based on input-buffer confirmation need the asynchronous recheck.
        if (target.emptyInputEpoch != null) return InsertResult.UNSUPPORTED
        val current = currentTarget(target) ?: return InsertResult.TARGET_CHANGED
        return writeText(current, target, text)
    }

    private fun writeText(current: AccessibilityNodeInfo, target: Target, text: String): InsertResult {
        val edit = DictationText.insert(target.text, target.selectionStart, target.selectionEnd, text)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edit.text)
        }
        if (!current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return InsertResult.UNSUPPORTED
        current.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, edit.cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, edit.cursor)
        })
        return InsertResult.INSERTED
    }

    override fun onInterrupt() {
        editor = null
        generation++
        OverlayService.updateEditor(false, Rect())
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        instance = null
        OverlayService.updateEditor(false, Rect())
        super.onDestroy()
    }
}
