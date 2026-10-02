package com.cragnet.wysawyg

import android.graphics.Rect
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.EditText

/** Mimics an editor exposed as a virtual child whose focus lookup returns its host. */
class VirtualEditorAccessibility(private val editor: EditText) : View.AccessibilityDelegate() {
    private val provider = object : AccessibilityNodeProvider() {
        @Suppress("DEPRECATION")
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId != HOST_VIEW_ID && virtualViewId != EDITOR_ID) return null
            val bounds = Rect()
            val visible = editor.getGlobalVisibleRect(bounds)
            return AccessibilityNodeInfo.obtain(editor).apply {
                packageName = editor.context.packageName
                isEnabled = true
                isVisibleToUser = visible
                setBoundsInScreen(bounds)
                setBoundsInParent(Rect(0, 0, editor.width, editor.height))
                if (virtualViewId == HOST_VIEW_ID) {
                    className = "android.view.View"
                    viewIdResourceName = editor.resources.getResourceName(editor.id)
                    (editor.parent as? View)?.let { setParent(it) }
                    addChild(editor, EDITOR_ID)
                } else {
                    setSource(editor, EDITOR_ID)
                    setParent(editor)
                    className = "android.widget.EditText"
                    isEditable = true
                    isFocusable = true
                    isFocused = editor.hasFocus()
                    isPassword = editor.transformationMethod is PasswordTransformationMethod
                    val entered = editor.text.toString()
                    isShowingHintText = entered.isEmpty() && !editor.hint.isNullOrEmpty()
                    text = if (isShowingHintText) editor.hint else entered
                    hintText = editor.hint
                    setTextSelection(editor.selectionStart, editor.selectionEnd)
                    addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
                    addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
                }
            }
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? {
            return if (focus == AccessibilityNodeInfo.FOCUS_INPUT && editor.hasFocus()) {
                createAccessibilityNodeInfo(HOST_VIEW_ID)
            } else null
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualViewId != EDITOR_ID) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                    editor.setText(arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))
                    true
                }
                AccessibilityNodeInfo.ACTION_SET_SELECTION -> {
                    if (arguments == null) return false
                    editor.setSelection(
                        arguments.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT),
                        arguments.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT)
                    )
                    true
                }
                else -> false
            }
        }
    }

    override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProvider = provider

    companion object {
        private const val EDITOR_ID = 1
    }
}
