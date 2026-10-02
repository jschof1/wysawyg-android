package com.cragnet.wysawyg

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Observes the visible editor and inserts directly, without touching the clipboard. */
class TextInjectorService : AccessibilityService() {
    data class Target(
        val node: AccessibilityNodeInfo,
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val generation: Long
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
    private val refresh = Runnable { refreshEditor() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        WysawygLogger.init(this)
        refreshEditor()
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
            val focused = application?.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val candidate = focused?.takeIf { it.isEditable && it.isFocused && it.isVisibleToUser && !it.isPassword }
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

    fun insert(target: Target, text: String): InsertResult {
        refreshEditor()
        val current = editor ?: return InsertResult.TARGET_CHANGED
        if (!keyboardVisible || target.generation != generation || current != target.node ||
            !current.refresh() || editorText(current) != target.text ||
            current.textSelectionStart != target.selectionStart || current.textSelectionEnd != target.selectionEnd) {
            return InsertResult.TARGET_CHANGED
        }
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
