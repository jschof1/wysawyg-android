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
            setPadding(padding, padding / 2, padding, padding)
            isFocusableInTouchMode = true
        }
        layout.addView(com.google.android.material.appbar.MaterialToolbar(this).apply {
            title = "Try dictation"
            setTitleTextAppearance(this@DictationTestActivity, R.style.SE_Text_Hero)
            setNavigationIcon(R.drawable.se_arrow_back)
            navigationIcon?.setTint(getColor(R.color.se_text_primary))
            setNavigationContentDescription("Back")
            setNavigationOnClickListener { finish() }
            setContentInsetsRelative(0, 0)
        })
        layout.addView(TextView(this).apply {
            text = "Tap a text box, then the floating microphone above your keyboard. Stop inserts your words; Cancel discards the recording. If insertion fails, your text is kept in Recovered transcripts."
            setTextAppearance(R.style.SE_Text_Body)
            setTextColor(getColor(R.color.se_text_secondary))
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
            text = "Format as if typing in"
            setTextAppearance(R.style.SE_Text_RowStrong)
            setPadding(0, padding, 0, 0)
        })
        layout.addView(android.widget.HorizontalScrollView(this).apply { addView(RadioGroup(this@DictationTestActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            samples.forEachIndexed { index, (label, app) ->
                addView(RadioButton(this@DictationTestActivity).apply {
                    id = View.generateViewId()
                    text = label
                    buttonTintList = getColorStateList(R.color.se_accent)
                    minHeight = (48 * resources.displayMetrics.density).toInt()
                    isChecked = index == current
                    setOnClickListener { simulatedPackage = app }
                })
            }
        }) })
        layout.addView(editor)
        layout.addView(secondEditor)
        layout.addView(com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Clear text"
            setOnClickListener { editor.text.clear(); secondEditor.text.clear() }
        })
        setContentView(layout)
        layout.requestFocus()
    }
}
