package com.cragnet.wysawyg

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test

class EditorInputPolicyTest {
    @Test fun rejectsPasswordsAndMissingEditorMetadata() {
        listOf(InputType.TYPE_NULL, InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ).forEach { assertFalse(EditorInputPolicy.allowsText(it)) }
    }

    @Test fun acceptsNormalMultilineAndNumericInput() {
        assertTrue(EditorInputPolicy.allowsText(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertTrue(EditorInputPolicy.allowsText(InputType.TYPE_CLASS_NUMBER))
    }
}
