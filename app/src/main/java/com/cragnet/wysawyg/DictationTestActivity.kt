package com.cragnet.wysawyg

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** A harmless place to try dictation, without sending anything or changing settings. */
class DictationTestActivity : AppCompatActivity() {
    companion object {
        /** Dictation into this screen is styled as if it were going to this app. */
        @Volatile var simulatedPackage: String? = null
    }

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
            text = "Tap a text box, then the floating microphone above your keyboard. Stop transcribes and inserts your words. Cancel discards the recording. If insertion fails, your text stays available under Saved text."
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
        val samples = listOf("Everything else" to null, "WhatsApp" to "com.whatsapp",
            "Slack" to "com.Slack", "Gmail" to "com.google.android.gm")
        val current = samples.indexOfFirst { it.second == simulatedPackage }.coerceAtLeast(0)
        layout.addView(TextView(this).apply {
            text = "Format as if typing in:"
            setPadding(0, padding / 2, 0, 0)
        })
        layout.addView(android.widget.HorizontalScrollView(this).apply { addView(RadioGroup(this@DictationTestActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            samples.forEachIndexed { index, (label, app) ->
                addView(RadioButton(this@DictationTestActivity).apply {
                    id = View.generateViewId()
                    text = label
                    isChecked = index == current
                    setOnClickListener { simulatedPackage = app }
                })
            }
        }) })
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
