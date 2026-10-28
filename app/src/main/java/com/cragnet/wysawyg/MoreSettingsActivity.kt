package com.cragnet.wysawyg

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Keyboard, transcription engine, settings backup, licences and logs. */
class MoreSettingsActivity : AppCompatActivity() {
    private lateinit var urlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var promptInput: EditText
    private lateinit var exporter: SettingsExporter
    private var mode = TranscriptionMode.OFFLINE
    private var loadedCredentials = emptyMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_more_settings)
        applySystemInsets()
        findViewById<MaterialToolbar>(R.id.moreToolbar).setNavigationOnClickListener { finish() }
        exporter = SettingsExporter(this) { loadSettings() }
        urlInput = findViewById(R.id.alarmaUrl)
        apiKeyInput = findViewById(R.id.apiKey)
        modelInput = findViewById(R.id.modelName)
        promptInput = findViewById(R.id.systemPrompt)

        findViewById<RadioButton>(R.id.onDeviceMode).setOnClickListener { setMode(TranscriptionMode.OFFLINE) }
        findViewById<RadioButton>(R.id.cloudMode).setOnClickListener { setMode(TranscriptionMode.CLOUD) }
        findViewById<View>(R.id.advancedToggle).setOnClickListener {
            val panel = findViewById<View>(R.id.advancedSettings)
            val expanded = panel.visibility != View.VISIBLE
            panel.visibility = if (expanded) View.VISIBLE else View.GONE
            findViewById<View>(R.id.advancedChevron).rotation = if (expanded) 180f else 0f
        }
        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && modelInput.text.isNullOrBlank()) modelInput.setText(defaultModel(urlInput.text.toString()))
        }
        findViewById<View>(R.id.recordingIndicatorButton).setOnClickListener { chooseIndicator() }
        findViewById<View>(R.id.enableKeyboardButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<View>(R.id.exportSettingsButton).setOnClickListener {
            saveSettings()
            exporter.promptExport()
        }
        findViewById<View>(R.id.importSettingsButton).setOnClickListener { exporter.promptImport() }
        findViewById<View>(R.id.speechNotices).setOnClickListener {
            val notices = listOf("PHONON-NOTICE.txt", "PHONON-UPSTREAM-NOTICE.txt", "phonon2-MIT.txt", "CC-BY-4.0.txt",
                "Manrope-OFL.txt", "Material-Symbols-Apache-2.0.txt")
                .joinToString("\n\n") { name -> assets.open("notices/$name").bufferedReader().use { it.readText() } }
            showLongText(getString(R.string.more_licences), notices)
        }
        findViewById<View>(R.id.viewLogButton).setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        findViewById<View>(R.id.shareLogButton).setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Speakeasy log")
                putExtra(Intent.EXTRA_TEXT, WysawygLogger.getLogText(this@MoreSettingsActivity))
            }
            startActivity(Intent.createChooser(share, getString(R.string.share_log)))
        }
        findViewById<View>(R.id.clearLogButton).setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(R.string.more_logs_clear_confirm)
                .setPositiveButton(R.string.clear_log) { _, _ -> WysawygLogger.clear(this) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        findViewById<View>(R.id.privacyPolicyButton).setOnClickListener {
            saveSettings()
            PrivacyConsent.showPrivacy(this) { loadSettings() }
        }
        loadSettings()
    }

    override fun onResume() {
        super.onResume()
        val enabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
        findViewById<TextView>(R.id.keyboardStatus).setText(if (enabled) R.string.more_keyboard_enabled else R.string.more_keyboard_off)
        renderIndicator()
    }

    private fun renderIndicator() {
        findViewById<TextView>(R.id.recordingIndicatorValue).text =
            RecordingIndicator.load(getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)).label
    }

    /** Each style is shown as a miniature of how it looks over the keyboard; picking one saves it. */
    private fun chooseIndicator() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        val current = RecordingIndicator.load(prefs)
        val sheet = BottomSheetDialog(this)
        val content = LayoutInflater.from(this).inflate(R.layout.sheet_recording_indicator, null)
        val options = content.findViewById<LinearLayout>(R.id.indicatorOptions)
        val previews = mapOf(RecordingIndicator.DETAILED to R.layout.preview_indicator_detailed,
            RecordingIndicator.COMPACT to R.layout.preview_indicator_compact,
            RecordingIndicator.MINIMAL to R.layout.preview_indicator_minimal)
        RecordingIndicator.values().forEach { indicator ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_indicator_option, options, false) as MaterialCardView
            LayoutInflater.from(this).inflate(previews.getValue(indicator), card.findViewById<FrameLayout>(R.id.optionPreview), true)
            card.findViewById<TextView>(R.id.optionTitle).text = indicator.label
            card.findViewById<TextView>(R.id.optionDescription).text = indicator.description
            card.isChecked = indicator == current
            card.findViewById<View>(R.id.optionCheck).visibility = if (indicator == current) View.VISIBLE else View.INVISIBLE
            card.contentDescription = "${indicator.label}. ${indicator.description}"
            card.setOnClickListener {
                prefs.edit().putString(RecordingIndicator.KEY, indicator.key).apply()
                renderIndicator()
                sheet.dismiss()
            }
            options.addView(card)
        }
        sheet.setContentView(content)
        sheet.show()
    }

    override fun onPause() {
        saveSettings()
        super.onPause()
    }

    private fun setMode(next: TranscriptionMode) {
        if (next == TranscriptionMode.OFFLINE) {
            PrivacyConsent.revokeCloud(this)
            mode = next
            renderMode()
            saveSettings()
            return
        }
        renderMode()
        val destination = try { CloudDestination.parse(urlInput.text.toString(), BuildConfig.DEBUG) }
        catch (error: IllegalArgumentException) {
            android.widget.Toast.makeText(this, error.message, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        PrivacyConsent.requestCloud(this, destination, accepted = {
            mode = TranscriptionMode.CLOUD
            renderMode()
            saveSettings()
        }, declined = { renderMode() })
    }

    private fun renderMode() {
        findViewById<RadioButton>(R.id.onDeviceMode).isChecked = mode == TranscriptionMode.OFFLINE
        findViewById<RadioButton>(R.id.cloudMode).isChecked = mode == TranscriptionMode.CLOUD
        findViewById<TextView>(R.id.transcriptionStatus).setText(
            if (mode == TranscriptionMode.OFFLINE) R.string.works_offline else R.string.cloud_selected)
    }

    /** Only fields the person changed are written, so untouched defaults stay absent from exports. */
    private fun saveSettings() {
        val fields = mapOf(MainActivity.PREF_ALARMA_URL to urlInput.text.toString(), MainActivity.PREF_API_KEY to apiKeyInput.text.toString(),
            MainActivity.PREF_MODEL to modelInput.text.toString(), MainActivity.PREF_SYSTEM_PROMPT to promptInput.text.toString())
        if (mode == TranscriptionMode.CLOUD && !cloudAllowed(fields.getValue(MainActivity.PREF_ALARMA_URL))) {
            mode = TranscriptionMode.OFFLINE
            renderMode()
        }
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit().apply {
            putString(TranscriptionMode.KEY, mode.name)
            fields.forEach { (key, value) -> if (value != loadedCredentials[key]) putString(key, value) }
            apply()
        }
        loadedCredentials = fields
        WysawygLogger.i("Settings saved")
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        mode = TranscriptionMode.fromStored(prefs.getString(TranscriptionMode.KEY, null))
        val url = prefs.getString(MainActivity.PREF_ALARMA_URL, "https://ollama.com/api/chat") ?: "https://ollama.com/api/chat"
        if (mode == TranscriptionMode.CLOUD && !cloudAllowed(url)) mode = TranscriptionMode.OFFLINE
        renderMode()
        urlInput.setText(url)
        apiKeyInput.setText(prefs.getString(MainActivity.PREF_API_KEY, ""))
        modelInput.setText(prefs.getString(MainActivity.PREF_MODEL, defaultModel(url)))
        promptInput.setText(prefs.getString(MainActivity.PREF_SYSTEM_PROMPT,
            "Transcribe the audio exactly. Output only the spoken words, no commentary."))
        loadedCredentials = mapOf(MainActivity.PREF_ALARMA_URL to urlInput.text.toString(), MainActivity.PREF_API_KEY to apiKeyInput.text.toString(),
            MainActivity.PREF_MODEL to modelInput.text.toString(), MainActivity.PREF_SYSTEM_PROMPT to promptInput.text.toString())
    }

    private fun defaultModel(url: String) = if (url.contains("ollama.com")) "gemma4:e4b" else "gemma4:12b"

    private fun cloudAllowed(url: String) = runCatching {
        PrivacyConsent.hasCloud(this, CloudDestination.parse(url, BuildConfig.DEBUG))
    }.getOrDefault(false)
}
