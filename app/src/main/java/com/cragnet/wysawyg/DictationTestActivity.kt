package com.cragnet.wysawyg

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** A harmless place to try dictation, without sending anything or changing settings. */
class DictationTestActivity : AppCompatActivity() {
    lateinit var editor: EditText
        private set
    lateinit var secondEditor: EditText
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Try dictation"
        val padding = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            isFocusableInTouchMode = true
        }
        layout.addView(TextView(this).apply {
            text = "Tap a text box. The floating microphone appears above your keyboard. Tap to record, tap again to insert. Hold while recording to cancel."
            textSize = 16f
        })
        editor = EditText(this).apply {
            id = R.id.dictationTestEditor
            hint = "Try dictation here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        secondEditor = EditText(this).apply {
            id = R.id.dictationTestSecondEditor
            hint = "Or move to this text box"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        layout.addView(editor)
        layout.addView(secondEditor)
        layout.addView(Button(this).apply {
            text = "Clear text"
            setOnClickListener { editor.text.clear(); secondEditor.text.clear() }
        })
        setContentView(layout)
        layout.requestFocus()
    }
}
