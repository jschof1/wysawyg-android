package com.cragnet.wysawyg

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Per-app writing styles, a live preview using the real formatter, and cleanup options. */
class WritingStyleActivity : AppCompatActivity() {
    private companion object {
        const val STATE_SHEET = "style_sheet_category"
        const val STATE_DRAFT = "style_sheet_draft"
    }

    private val samples = listOf(AppCategory.PERSONAL to "com.whatsapp", AppCategory.WORK to "com.Slack",
        AppCategory.EMAIL to "com.google.android.gm", AppCategory.OTHER to "com.example")
    private val icons = mapOf(AppCategory.PERSONAL to R.drawable.se_chat, AppCategory.WORK to R.drawable.se_work,
        AppCategory.EMAIL to R.drawable.se_mail, AppCategory.OTHER to R.drawable.se_more)

    private val examples = mapOf(AppCategory.PERSONAL to "WhatsApp, Messages, Signal, Instagram and similar",
        AppCategory.WORK to "Slack, Teams, Google Chat and LinkedIn", AppCategory.EMAIL to "Gmail, Outlook and other email apps",
        AppCategory.OTHER to "Notes, browsers, AI chats and every other app")

    private var settings = StyleSettings()
    /** Set while showing saved values, so only people's own changes are saved. */
    private var rendering = false
    private var smartPass: Job? = null
    /** The open style sheet and its unsaved choice, kept across rotation. */
    private var sheetCategory: AppCategory? = null
    private var sheetDraft: WritingStyle? = null
    private var sheet: BottomSheetDialog? = null
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_writing_style)
        findViewById<MaterialToolbar>(R.id.styleToolbar).setNavigationOnClickListener { finish() }
        settings = StyleSettings.load(prefs())

        input = findViewById(R.id.stylePreviewInput)
        if (savedInstanceState == null) input.setText(R.string.style_try_default)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = renderPreview()
        })

        switch(R.id.matchAppSwitch) { copy(matchApp = it) }
        switch(R.id.fillerSwitch) { copy(removeFillers = it) }
        switch(R.id.commandsSwitch) { copy(voiceCommands = it) }
        findViewById<MaterialSwitch>(R.id.smartCleanupSwitch).setOnCheckedChangeListener { _, checked ->
            if (!rendering) setSmartCleanup(checked)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SmartCleanup.get(this@WritingStyleActivity).downloadState.collect { renderSmartCleanup() }
            }
        }
        render()
        val restoredCategory = savedInstanceState?.getString(STATE_SHEET)?.let { key -> AppCategory.values().firstOrNull { it.key == key } }
        restoredCategory?.let { chooseStyle(it, WritingStyle.fromKey(savedInstanceState.getString(STATE_DRAFT))) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sheetCategory?.let { outState.putString(STATE_SHEET, it.key) }
        sheetDraft?.let { outState.putString(STATE_DRAFT, it.key) }
    }

    override fun onDestroy() {
        smartPass?.cancel()
        sheet?.dismiss()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)

    private fun switch(id: Int, change: StyleSettings.(Boolean) -> StyleSettings) {
        findViewById<MaterialSwitch>(id).setOnCheckedChangeListener { _, checked ->
            if (!rendering) update(settings.change(checked))
        }
    }

    private fun update(next: StyleSettings) {
        settings = next
        next.save(prefs().edit()).apply()
        render()
    }

    private fun render() {
        rendering = true
        findViewById<MaterialSwitch>(R.id.matchAppSwitch).isChecked = settings.matchApp
        findViewById<MaterialSwitch>(R.id.fillerSwitch).isChecked = settings.removeFillers
        findViewById<MaterialSwitch>(R.id.commandsSwitch).isChecked = settings.voiceCommands
        findViewById<MaterialSwitch>(R.id.smartCleanupSwitch).isChecked = settings.smartCleanup
        rendering = false
        findViewById<TextView>(R.id.matchHelp).setText(
            if (settings.matchApp) R.string.style_match_help else R.string.style_match_off_note)

        val rows = findViewById<LinearLayout>(R.id.styleRows)
        rows.removeAllViews()
        AppCategory.values().forEachIndexed { index, category ->
            if (index > 0) rows.addView(View(this).apply {
                setBackgroundResource(R.color.se_divider)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.displayMetrics.density.toInt())
            })
            val row = LayoutInflater.from(this).inflate(R.layout.item_style_row, rows, false)
            row.findViewById<ImageView>(R.id.styleRowIcon).setImageResource(icons.getValue(category))
            row.findViewById<TextView>(R.id.styleRowTitle).text = category.label
            row.findViewById<TextView>(R.id.styleRowValue).text = settings.styleFor(category).label
            row.contentDescription = "${category.label}, ${settings.styleFor(category).label}"
            row.setOnClickListener { chooseStyle(category) }
            rows.addView(row)
        }
        renderSmartCleanup()
        renderPreview()
    }

    /** A sheet with the three styles; the choice is a draft until Save. */
    private fun chooseStyle(category: AppCategory, restoredDraft: WritingStyle? = null) {
        val sheet = BottomSheetDialog(this).also { this.sheet = it }
        val view = LayoutInflater.from(this).inflate(R.layout.sheet_style_choice, null)
        val app = samples.first { it.first == category }.second
        val raw = "Yeah, sounds good, see you at six."
        val saved = settings.styleFor(category)
        var draft = restoredDraft ?: saved
        sheetCategory = category
        sheetDraft = draft
        val chips = view.findViewById<ChipGroup>(R.id.sheetChips)
        val save = view.findViewById<View>(R.id.sheetSave)
        fun show() {
            view.findViewById<TextView>(R.id.sheetDescription).text = draft.description
            view.findViewById<TextView>(R.id.sheetSample).text = DictationFormatter.format(raw, DictationContext(app),
                settings.copy(matchApp = true, styles = settings.styles + (category to draft)))
            save.isEnabled = draft != saved
        }
        view.findViewById<TextView>(R.id.sheetTitle).text = category.label
        view.findViewById<TextView>(R.id.sheetSubtitle).text = examples.getValue(category)
        WritingStyle.values().forEach { style ->
            chips.addView((LayoutInflater.from(this).inflate(R.layout.item_style_chip, chips, false) as Chip).apply {
                id = View.generateViewId()
                text = style.label
                isCheckable = true
                isCheckedIconVisible = true
                isChecked = style == draft
                setOnCheckedChangeListener { _, checked -> if (checked) { draft = style; sheetDraft = style; show() } }
            })
        }
        show()
        view.findViewById<View>(R.id.sheetCancel).setOnClickListener { sheet.dismiss() }
        save.setOnClickListener {
            update(settings.copy(styles = settings.styles + (category to draft)))
            sheet.dismiss()
        }
        sheet.setOnDismissListener {
            // Rotation dismisses the sheet too; keep the draft for the recreated screen.
            if (!isChangingConfigurations) { sheetCategory = null; sheetDraft = null }
        }
        sheet.setContentView(view)
        sheet.show()
    }

    /** Shows each category's result as you type; Smart cleanup follows after a short pause. */
    private fun renderPreview() {
        val text = input.text.toString()
        val output = findViewById<LinearLayout>(R.id.stylePreviewOutput)
        output.removeAllViews()
        fun add(meta: String, body: String): TextView {
            val card = LayoutInflater.from(this).inflate(R.layout.item_style_preview, output, false)
            card.findViewById<TextView>(R.id.previewMeta).text = meta
            val bodyView = card.findViewById<TextView>(R.id.previewText)
            bodyView.text = body
            output.addView(card)
            return bodyView
        }
        if (text.isBlank()) {
            add("", "Type a sentence to compare.")
            return
        }
        samples.forEach { (category, app) ->
            val style = if (settings.matchApp) settings.styleFor(category).label else "Standard"
            add(getString(R.string.style_example_meta, category.label, style),
                DictationFormatter.format(text, DictationContext(app), settings))
        }
        smartPass?.cancel()
        val model = SmartCleanup.get(this)
        if (!settings.smartCleanup || !model.installed) return
        val context = DictationContext(samples.last().second)
        val body = add(getString(R.string.style_preview_smart, "…"), "")
        val meta = (body.parent as View).findViewById<TextView>(R.id.previewMeta)
        smartPass = lifecycleScope.launch {
            delay(600)
            val prepared = DictationFormatter.prepare(text, context, settings)
            val started = System.currentTimeMillis()
            val smart = model.punctuate(prepared)
            meta.text = getString(R.string.style_preview_smart,
                if (smart == null) "kept the rules result" else "${System.currentTimeMillis() - started} ms")
            body.text = DictationFormatter.finish(smart ?: prepared, context, settings)
        }
    }

    private fun setSmartCleanup(enabled: Boolean) {
        val model = SmartCleanup.get(this)
        if (!enabled || model.installed) {
            if (!enabled) model.cancelDownload()
            update(settings.copy(smartCleanup = enabled))
            return
        }
        render() // Stays off until the person agrees to the download.
        MaterialAlertDialogBuilder(this).setTitle("Download Smart cleanup?")
            .setMessage(getString(R.string.style_smart_download))
            .setPositiveButton("Download 345 MB") { _, _ ->
                update(settings.copy(smartCleanup = true))
                model.startDownload()
            }.setNegativeButton("Not now", null).show()
    }

    private fun renderSmartCleanup() {
        val model = SmartCleanup.get(this)
        val state = model.downloadState.value
        val megabytes = { bytes: Long -> bytes / 1_000_000 }
        findViewById<TextView>(R.id.smartCleanupStatus).text = when {
            !settings.smartCleanup && model.installed -> getString(R.string.style_smart_hint)
            !settings.smartCleanup -> getString(R.string.style_smart_hint) + " " + getString(R.string.style_smart_download_short)
            model.installed -> getString(R.string.style_smart_ready)
            state.running -> getString(R.string.style_smart_downloading, megabytes(state.done), megabytes(SmartCleanup.MODEL_BYTES))
            state.error != null -> getString(R.string.style_smart_failed, state.error)
            else -> getString(R.string.style_smart_paused)
        }
        findViewById<LinearProgressIndicator>(R.id.smartCleanupProgress).apply {
            visibility = if (settings.smartCleanup && state.running) View.VISIBLE else View.GONE
            max = 1000
            progress = (state.done * 1000 / SmartCleanup.MODEL_BYTES).toInt()
        }
        findViewById<View>(R.id.smartCleanupAction).apply {
            visibility = if (settings.smartCleanup && !model.installed && !state.running) View.VISIBLE else View.GONE
            setOnClickListener { model.startDownload() }
        }
    }
}
