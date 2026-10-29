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
import kotlinx.coroutines.delay
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
        val emptyInputEpoch: Long? = null,
        val requiresKeyboard: Boolean = true
    ) {
        val dictationContext: DictationContext get() =
            DictationContext.around(node.packageName?.toString(), node.inputType, text, selectionStart, selectionEnd)
    }
    enum class InsertResult { INSERTED, TARGET_CHANGED, UNSUPPORTED, SENT_UNCONFIRMED }

    companion object {
        var instance: TextInjectorService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var editor: AccessibilityNodeInfo? = null
    private var keyboardVisible = false
    private var generation = 0L
    private var inputEpoch = 0L
    private var selectionRevision = 0L
    private var refreshScheduled = false
    private val refresh = Runnable { refreshScheduled = false; refreshEditor() }

    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        handler.postDelayed(refresh, 50)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        WysawygLogger.init(this)
        if (Build.VERSION.SDK_INT >= 33 && PrivacyConsent.hasAccessibility(this)) {
            serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR }
        }
        OverlayService.ensureRunning(this)
        refreshEditor()
    }

    @RequiresApi(33)
    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            inputEpoch++
            generation++
            scheduleRefresh()
        }
        override fun onFinishInput() {
            // Observe the existing keyboard's editor without changing composing text.
            inputEpoch++
            generation++
            scheduleRefresh()
        }
        override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
            candidatesStart: Int, candidatesEnd: Int) {
            selectionRevision++
            scheduleRefresh()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!PrivacyConsent.hasAccessibility(this)) return
        // Window events include the keyboard being dismissed without losing editor focus.
        scheduleRefresh()
    }

    fun refreshEditor() {
        if (!PrivacyConsent.hasAccessibility(this)) {
            editor = null
            keyboardVisible = false
            generation++
            OverlayService.updateEditor(false, Rect())
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && (serviceInfo.flags and AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR) == 0) {
            serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR }
        }
        try {
            OverlayService.ensureRunning(this)
            val visibleWindows = windows
            val keyboard = visibleWindows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val keyboardBounds = Rect().also { keyboard?.getBoundsInScreen(it) }
            val application = visibleWindows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            } ?: visibleWindows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive &&
                    visibleWindows.none { other -> other.isFocused && other.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }
            val candidate = findFocusedEditor(application?.root)
            if (candidate != editor || keyboardVisible != (keyboard != null)) generation++
            editor = candidate
            keyboardVisible = keyboard != null
            val inputAvailable = Build.VERSION.SDK_INT >= 33 && currentInputRequest() != null
            OverlayService.updateEditor((candidate != null || inputAvailable) && keyboardVisible, keyboardBounds)
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
            node.isEditable && node.isFocused && node.isVisibleToUser && !node.isPassword &&
                (node.inputType == 0 || EditorInputPolicy.allowsText(node.inputType))
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

    fun captureTarget(requireKeyboard: Boolean = true): Target? {
        refreshEditor()
        val node = editor?.takeIf { (!requireKeyboard || keyboardVisible) && it.refresh() } ?: return null
        return Target(node, editorText(node), node.textSelectionStart, node.textSelectionEnd, generation,
            requiresKeyboard = requireKeyboard)
    }

    private fun editorText(node: AccessibilityNodeInfo): String {
        // Android may expose an empty editor's hint as its text. The flag distinguishes
        // placeholders from actual content, even when someone types the same words.
        return if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
    }

    /** Confirms an empty input buffer when an app exposes an unmarked placeholder. */
    suspend fun captureTargetForDictation(requireKeyboard: Boolean = true): Target? {
        val target = withContext(Dispatchers.Main.immediate) { captureTarget(requireKeyboard) } ?: return null
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
        if (epoch == null) {
            val result = withContext(Dispatchers.Main.immediate) { insert(target, text) }
            return if (result == InsertResult.INSERTED) confirmNodeInsertion(target, text) else result
        }
        if (!withContext(Dispatchers.Main.immediate) { inputEpoch == epoch && currentTarget(target) != null }) {
            return InsertResult.TARGET_CHANGED
        }
        val input = readInputState(target.node)
        val result = withContext(Dispatchers.Main.immediate) {
            val current = currentTarget(target)
            if (current == null || inputEpoch != epoch || input?.epoch != epoch || !input.empty) {
                InsertResult.TARGET_CHANGED
            } else {
                writeText(current, target, text)
            }
        }
        return if (result == InsertResult.INSERTED) confirmNodeInsertion(target, text) else result
    }

    private fun currentTarget(target: Target): AccessibilityNodeInfo? {
        refreshEditor()
        val current = editor ?: return null
        if ((target.requiresKeyboard && !keyboardVisible) || target.generation != generation || current != target.node ||
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
        if (!PrivacyConsent.hasAccessibility(this)) return InsertResult.UNSUPPORTED
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

    @RequiresApi(33)
    private data class InputRequest(val connection: InputMethod.AccessibilityInputConnection,
        val epoch: Long, val revision: Long, val packageName: String, val fieldId: Int)

    @RequiresApi(33)
    private fun currentInputRequest(): InputRequest? {
        if (!PrivacyConsent.hasAccessibility(this)) return null
        val method = inputMethod ?: return null
        val info = method.currentInputEditorInfo ?: return null
        if (!method.currentInputStarted || !EditorInputPolicy.allowsText(info.inputType)) return null
        val foreground = windows.firstOrNull { it.isFocused && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?: windows.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (foreground?.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
            foreground.root?.packageName?.toString() != info.packageName) return null
        if (foreground.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isPassword == true) return null
        val connection = method.currentInputConnection ?: return null
        return InputRequest(connection, inputEpoch, selectionRevision, info.packageName, info.fieldId)
    }

    /** Explicit insertion uses the current Android input, not the recording's old editor token. */
    suspend fun insertHere(text: String): InsertResult {
        if (Build.VERSION.SDK_INT >= 33) {
            val blocked = withContext(Dispatchers.Main.immediate) {
                inputMethod?.let { it.currentInputStarted &&
                    it.currentInputEditorInfo?.let { info -> !EditorInputPolicy.allowsText(info.inputType) } == true } == true
            }
            if (blocked) return InsertResult.TARGET_CHANGED
            for (attempt in 0..2) {
                val request = withContext(Dispatchers.Main.immediate) { currentInputRequest() }
                if (request != null) {
                    val result = insertIntoCurrentInput(request, text)
                    if (result != InsertResult.UNSUPPORTED) return result
                    break
                }
                if (attempt < 2) delay(75)
            }
        }
        val target = captureTargetForDictation(requireKeyboard = false) ?: run {
            WysawygLogger.i("Insert here: no eligible current input or focused editor")
            return InsertResult.TARGET_CHANGED
        }
        return insertForDictation(target, text)
    }

    private suspend fun confirmNodeInsertion(target: Target, text: String): InsertResult {
        val expected = DictationText.insert(target.text, target.selectionStart, target.selectionEnd, text).text
        repeat(3) {
            if (withContext(Dispatchers.Main.immediate) { target.node.refresh() && editorText(target.node) == expected }) {
                return InsertResult.INSERTED
            }
            delay(75)
        }
        return InsertResult.SENT_UNCONFIRMED
    }

    @RequiresApi(33)
    private suspend fun insertIntoCurrentInput(request: InputRequest, text: String): InsertResult {
        val before = withContext(Dispatchers.IO) { request.connection.getSurroundingText(1, 1, 0) }
            ?: return InsertResult.UNSUPPORTED
        val edit = DirectTextInsertion.plan(before.text.toString(), before.selectionStart, before.selectionEnd, before.offset, text)
            ?: return InsertResult.UNSUPPORTED
        val sent = withContext(Dispatchers.Main.immediate) {
            val current = currentInputRequest()
            if (current == null || current.epoch != request.epoch || current.revision != request.revision ||
                current.packageName != request.packageName || current.fieldId != request.fieldId) false
            else {
                request.connection.commitText(edit.text, 1, null)
                true
            }
        }
        if (!sent) return InsertResult.TARGET_CHANGED
        // Never try another write path after dispatch: a missing acknowledgement may still mean it landed.
        if (edit.text.length > 8192) return InsertResult.SENT_UNCONFIRMED
        repeat(3) {
            val after = withContext(Dispatchers.IO) {
                runCatching { request.connection.getSurroundingText(edit.text.length, 0, 0) }.getOrNull()
            }
            if (after != null && edit.confirmed(after.text.toString(), after.selectionStart, after.selectionEnd, after.offset)) {
                return InsertResult.INSERTED
            }
            delay(75)
        }
        return InsertResult.SENT_UNCONFIRMED
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
