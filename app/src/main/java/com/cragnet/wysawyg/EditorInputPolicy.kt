package com.cragnet.wysawyg

import android.text.InputType

object EditorInputPolicy {
    fun allowsText(type: Int): Boolean {
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation !in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            InputType.TYPE_CLASS_NUMBER -> variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD
            InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }
    }
}
