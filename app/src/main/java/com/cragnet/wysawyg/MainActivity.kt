package com.cragnet.wysawyg

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import android.widget.ScrollView
import android.widget.RadioButton
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val OVERLAY_REQUEST_CODE = 1002
        private const val NOTIFICATION_REQUEST_CODE = 1003
        const val PREF_OVERLAY_ENABLED = "overlay_enabled"
        const val PREFS_NAME = "wysawyg"
        const val PREF_ALARMA_URL = "alarma_url"
        const val PREF_API_KEY = "api_key"
        const val PREF_MODEL = "model"
        const val PREF_SYSTEM_PROMPT = "system_prompt"
    }

    private lateinit var alarmaUrlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var promptInput: EditText

    private var transcriptionMode = TranscriptionMode.OFFLINE
    private lateinit var settingsExporter: SettingsExporter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WysawygLogger.init(this)
        settingsExporter = SettingsExporter(this) { loadSettings() }

        alarmaUrlInput = findViewById(R.id.alarmaUrl)
        apiKeyInput = findViewById(R.id.apiKey)
        modelInput = findViewById(R.id.modelName)
        promptInput = findViewById(R.id.systemPrompt)

        findViewById<RadioButton>(R.id.onDeviceMode).setOnClickListener {
            transcriptionMode = TranscriptionMode.OFFLINE
            renderMode()
            saveSettings()
        }
        findViewById<RadioButton>(R.id.cloudMode).setOnClickListener {
            transcriptionMode = TranscriptionMode.CLOUD
            renderMode()
            saveSettings()
        }
        findViewById<Button>(R.id.advancedToggle).setOnClickListener {
            val panel = findViewById<View>(R.id.advancedSettings)
            val expanded = panel.visibility != View.VISIBLE
            panel.visibility = if (expanded) View.VISIBLE else View.GONE
            findViewById<Button>(R.id.advancedToggle).setText(if (expanded) R.string.advanced_hide else R.string.advanced_show)
        }
        findViewById<Button>(R.id.speechNotices).setOnClickListener {
            val notices = listOf("PHONON-NOTICE.txt", "PHONON-UPSTREAM-NOTICE.txt", "phonon2-MIT.txt", "CC-BY-4.0.txt")
                .joinToString("\n\n") { name -> assets.open("notices/$name").bufferedReader().use { it.readText() } }
            AlertDialog.Builder(this).setTitle("Speech model licences")
                .setView(ScrollView(this).apply { addView(TextView(this@MainActivity).apply {
                    text = notices; setPadding(24, 16, 24, 16)
                }) }).setPositiveButton("Close", null).show()
        }
        WysawygLogger.i("MainActivity started")

        findViewById<Button>(R.id.startOverlayButton).setOnClickListener {
            saveSettings()
            if (hasPermissions()) {
                startOverlayService()
            } else {
                requestPermissions()
            }
        }

        findViewById<Button>(R.id.stopOverlayButton).setOnClickListener {
            OverlayService.pause(this)
            updateBackgroundStatus()
        }

        findViewById<Button>(R.id.allowBackgroundButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }

        findViewById<Button>(R.id.enableAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.enableKeyboardButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.viewLogButton).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        findViewById<Button>(R.id.shareLogButton).setOnClickListener {
            shareLog()
        }

        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            WysawygLogger.clear(this)
        }

        findViewById<Button>(R.id.exportSettingsButton).setOnClickListener {
            saveSettings()
            settingsExporter.promptExport()
        }

        findViewById<Button>(R.id.importSettingsButton).setOnClickListener {
            settingsExporter.promptImport()
        }

        alarmaUrlInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val currentModel = modelInput.text.toString()
                if (currentModel.isBlank()) {
                    modelInput.setText(defaultModel(alarmaUrlInput.text.toString()))
                }
            }
        }

        findViewById<Button>(R.id.tryDictationButton).setOnClickListener {
            saveSettings()
            startActivity(Intent(this, DictationTestActivity::class.java))
        }
        findViewById<Button>(R.id.recoveredTranscriptsButton).setOnClickListener { showRecoveries() }
        findViewById<Button>(R.id.stylePreviewButton).setOnClickListener { showStylePreview() }
        styleSwitch(R.id.matchAppSwitch) { copy(matchApp = it) }
        styleSwitch(R.id.fillerSwitch) { copy(removeFillers = it) }
        styleSwitch(R.id.commandsSwitch) { copy(voiceCommands = it) }
        findViewById<MaterialSwitch>(R.id.smartCleanupSwitch).setOnCheckedChangeListener { _, checked ->
            if (!renderingStyle) setSmartCleanup(checked)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SmartCleanup.get(this@MainActivity).downloadState.collect { renderSmartCleanup() }
            }
        }
        loadSettings()
    }

    override fun onResume() {
        super.onResume()
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_OVERLAY_ENABLED, false) &&
            hasPermissions() && !OverlayService.isRunning()) startOverlayService()
        updateBackgroundStatus()
        updateRecoveryCount()
    }

    private fun updateRecoveryCount() {
        val count = Recoveries.get(this).snapshot().items.size
        findViewById<Button>(R.id.recoveredTranscriptsButton).text = "${getString(R.string.recovered_transcripts)} ($count)"
    }

    private fun showRecoveries() {
        val store = Recoveries.get(this)
        val snapshot = store.snapshot()
        val policy = "Saved transcripts expire after 24 hours. Up to 5 are kept; new recording pauses when full. To insert, select a non-password text box with your keyboard open, then tap Insert here on the floating card. Pause keeps saved text."
        if (snapshot.items.isEmpty()) {
            val dialog = AlertDialog.Builder(this).setTitle("Recovered transcripts").setMessage(
                when {
                    snapshot.unreadable -> "Recovery file is unreadable. Discard unreadable data to resume. $policy"
                    snapshot.writeFailed -> "Recovery storage could not be updated. Retry saving before recording again. $policy"
                    else -> "No recovered transcripts. $policy"
                }
            ).setPositiveButton("Close", null)
            if (snapshot.unreadable) dialog.setNegativeButton("Discard unreadable data") { _, _ ->
                if (!store.discardUnreadable()) Toast.makeText(this, "Could not clear recovery storage.", Toast.LENGTH_LONG).show()
                updateRecoveryCount()
            }
            if (snapshot.writeFailed && !snapshot.unreadable) dialog.setNeutralButton("Retry saving") { _, _ ->
                if (!store.retrySave()) Toast.makeText(this, "Could not update recovery storage.", Toast.LENGTH_LONG).show()
                updateRecoveryCount()
            }
            dialog.show()
            return
        }
        val dialog = AlertDialog.Builder(this).setTitle("Recovered transcripts")
            .setItems(snapshot.items.mapIndexed { i, item ->
                "${i + 1}. ${if (item.id in snapshot.unsavedIds) "Not saved: " else ""}${item.text.take(60)}"
            }.toTypedArray()) { _, index ->
                val item = snapshot.items[index]
                val text = TextView(this).apply {
                    text = item.text
                    textSize = 16f
                    val padding = (20 * resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding)
                }
                val title = if (item.id in snapshot.unsavedIds) "Not saved; memory only" else "Saved for 24 hours"
                AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(text) })
                    .setPositiveButton("Copy") { _, _ ->
                        if (!Recoveries.copy(this, item)) Toast.makeText(this, "Could not copy the full transcript. Text retained.", Toast.LENGTH_LONG).show()
                    }.setNegativeButton("Discard") { _, _ ->
                        if (!store.remove(item.id)) Toast.makeText(this, "Could not save removal. Transcript retained.", Toast.LENGTH_LONG).show()
                        updateRecoveryCount()
                    }.setNeutralButton("Close", null).show()
            }.setNegativeButton("Close", null)
        if (snapshot.writeFailed && !snapshot.unreadable) dialog.setPositiveButton("Retry saving") { _, _ ->
            if (!store.retrySave()) Toast.makeText(this, "Could not save recovery text. Copy before closing the app.", Toast.LENGTH_LONG).show()
            updateRecoveryCount()
            showRecoveries()
        }
        dialog.show()
    }

    override fun onPause() {
        saveSettings()
        super.onPause()
    }

    private fun hasPermissions(): Boolean {
        return OverlayService.hasPermissions(this)
    }

    private fun updateBackgroundStatus() {
        val unrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        findViewById<TextView>(R.id.backgroundStatus).text = when {
            !OverlayService.isEnabled(this) -> getString(R.string.background_paused)
            !hasPermissions() -> getString(R.string.background_permissions_needed)
            TextInjectorService.instance == null -> getString(R.string.background_accessibility_needed)
            !unrestricted -> getString(R.string.background_battery_limited)
            else -> getString(R.string.background_ready)
        }
        findViewById<Button>(R.id.allowBackgroundButton).apply {
            isEnabled = !unrestricted
            setText(if (unrestricted) R.string.background_allowed else R.string.allow_background)
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, OVERLAY_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED } && Settings.canDrawOverlays(this)) {
                startOverlayService()
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQUEST_CODE && Settings.canDrawOverlays(this) && hasPermissions()) {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_OVERLAY_ENABLED, true).apply()
        if (TextInjectorService.instance == null) {
            Toast.makeText(this, "Enable WYSAWYG Accessibility to show dictation when you type", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (OverlayService.ensureRunning(this)) {
            WysawygLogger.i("Overlay service start requested")
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST_CODE)
            }
        } else {
            Toast.makeText(this, "Could not resume dictation. Check microphone and overlay permissions.", Toast.LENGTH_LONG).show()
        }
        updateBackgroundStatus()
    }

    private fun shareLog() {
        val logText = WysawygLogger.getLogText(this)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WYSAWYG log")
            putExtra(Intent.EXTRA_TEXT, logText)
        }
        startActivity(Intent.createChooser(shareIntent, "Share log"))
    }

    private var loadedCredentials = emptyMap<String, String>()

    private fun saveSettings() {
        val fields = mapOf(PREF_ALARMA_URL to alarmaUrlInput.text.toString(), PREF_API_KEY to apiKeyInput.text.toString(),
            PREF_MODEL to modelInput.text.toString(), PREF_SYSTEM_PROMPT to promptInput.text.toString())
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putString(TranscriptionMode.KEY, transcriptionMode.name)
            fields.forEach { (key, value) -> if (value != loadedCredentials[key]) putString(key, value) }
            apply()
        }
        loadedCredentials = fields
        WysawygLogger.i("Settings saved")
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        transcriptionMode = TranscriptionMode.fromStored(prefs.getString(TranscriptionMode.KEY, null))
        styleSettings = StyleSettings.load(prefs)
        renderStyle()
        renderMode()
        val url = prefs.getString(PREF_ALARMA_URL, "https://ollama.com/api/chat") ?: "https://ollama.com/api/chat"
        alarmaUrlInput.setText(url)
        apiKeyInput.setText(prefs.getString(PREF_API_KEY, ""))
        modelInput.setText(prefs.getString(PREF_MODEL, defaultModel(url)))
        promptInput.setText(prefs.getString(PREF_SYSTEM_PROMPT, "Transcribe the audio exactly. Output only the spoken words, no commentary."))
        loadedCredentials = mapOf(PREF_ALARMA_URL to alarmaUrlInput.text.toString(), PREF_API_KEY to apiKeyInput.text.toString(),
            PREF_MODEL to modelInput.text.toString(), PREF_SYSTEM_PROMPT to promptInput.text.toString())
    }

    private var styleSettings = StyleSettings()
    /** Set while the screen shows saved values, so only people's own changes are saved. */
    private var renderingStyle = false

    private fun styleSwitch(id: Int, change: StyleSettings.(Boolean) -> StyleSettings) {
        findViewById<MaterialSwitch>(id).setOnCheckedChangeListener { _, checked ->
            if (!renderingStyle) updateStyle(styleSettings.change(checked))
        }
    }

    private fun updateStyle(settings: StyleSettings) {
        styleSettings = settings
        settings.save(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()).apply()
        renderStyle()
    }

    private fun renderStyle() {
        val settings = styleSettings
        renderingStyle = true
        findViewById<MaterialSwitch>(R.id.matchAppSwitch).isChecked = settings.matchApp
        findViewById<MaterialSwitch>(R.id.fillerSwitch).isChecked = settings.removeFillers
        findViewById<MaterialSwitch>(R.id.commandsSwitch).isChecked = settings.voiceCommands
        findViewById<MaterialSwitch>(R.id.smartCleanupSwitch).isChecked = settings.smartCleanup
        renderingStyle = false
        renderSmartCleanup()
        val rows = findViewById<LinearLayout>(R.id.styleRows)
        rows.removeAllViews()
        AppCategory.values().forEach { category ->
            val style = settings.styleFor(category)
            rows.addView(Button(this, null, 0, com.google.android.material.R.style.Widget_Material3_Button_TextButton).apply {
                text = getString(R.string.style_row, category.label, style.label)
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                isEnabled = settings.matchApp
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener { chooseStyle(category) }
            })
        }
    }

    private fun setSmartCleanup(enabled: Boolean) {
        val model = SmartCleanup.get(this)
        if (!enabled || model.installed) {
            updateStyle(styleSettings.copy(smartCleanup = enabled))
            return
        }
        renderStyle()
        AlertDialog.Builder(this).setTitle(R.string.style_smart)
            .setMessage(getString(R.string.style_smart_download))
            .setPositiveButton("Download") { _, _ ->
                updateStyle(styleSettings.copy(smartCleanup = true))
                model.startDownload()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun renderSmartCleanup() {
        val model = SmartCleanup.get(this)
        val state = model.downloadState.value
        val megabytes = { bytes: Long -> bytes / 1_000_000 }
        findViewById<TextView>(R.id.smartCleanupStatus).text = when {
            !styleSettings.smartCleanup -> getString(R.string.style_smart_hint)
            model.installed -> getString(R.string.style_smart_ready)
            state.running -> getString(R.string.style_smart_downloading, megabytes(state.done), megabytes(SmartCleanup.MODEL_BYTES))
            state.error != null -> getString(R.string.style_smart_failed, state.error)
            else -> getString(R.string.style_smart_paused)
        }
        findViewById<LinearProgressIndicator>(R.id.smartCleanupProgress).apply {
            visibility = if (styleSettings.smartCleanup && state.running) View.VISIBLE else View.GONE
            max = 1000
            progress = (state.done * 1000 / SmartCleanup.MODEL_BYTES).toInt()
        }
        findViewById<Button>(R.id.smartCleanupAction).apply {
            visibility = if (styleSettings.smartCleanup && !model.installed && !state.running) View.VISIBLE else View.GONE
            setOnClickListener { model.startDownload() }
        }
    }

    private fun chooseStyle(category: AppCategory) {
        val styles = WritingStyle.values()
        val labels = styles.map { "${it.label}\n${it.description}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(category.label)
            .setSingleChoiceItems(labels, styles.indexOf(styleSettings.styleFor(category))) { dialog, index ->
                updateStyle(styleSettings.copy(styles = styleSettings.styles + (category to styles[index])))
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    /** Lets people see each style without recording, and doubles as a quick check on emulators. */
    private fun showStylePreview() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            id = R.id.stylePreviewInput
            hint = "Type or paste a transcript"
            setText("Um, yeah, sounds good, see you at 6. Scratch that, see you at 7.")
            minLines = 2
        }
        val output = TextView(this).apply {
            id = R.id.stylePreviewOutput
            textSize = 15f
            setPadding(0, padding / 2, 0, 0)
            setTextIsSelectable(true)
        }
        val samples = listOf(AppCategory.PERSONAL to "com.whatsapp", AppCategory.WORK to "com.Slack",
            AppCategory.EMAIL to "com.google.android.gm", AppCategory.OTHER to "com.example")
        fun render() {
            output.text = samples.joinToString("\n\n") { (category, app) ->
                val formatted = DictationFormatter.format(input.text.toString(), DictationContext(app), styleSettings)
                "${category.label} (${styleSettings.styleFor(category).label}):\n$formatted"
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = render()
        })
        render()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
            addView(output)
        }
        AlertDialog.Builder(this).setTitle(R.string.style_preview)
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Close", null).show()
    }

    private fun renderMode() {
        findViewById<RadioButton>(R.id.onDeviceMode).isChecked = transcriptionMode == TranscriptionMode.OFFLINE
        findViewById<RadioButton>(R.id.cloudMode).isChecked = transcriptionMode == TranscriptionMode.CLOUD
        findViewById<TextView>(R.id.transcriptionStatus).setText(
            if (transcriptionMode == TranscriptionMode.OFFLINE) R.string.works_offline else R.string.cloud_selected)
    }

    private fun defaultModel(url: String): String {
        return if (url.contains("ollama.com")) "gemma4:e4b" else "gemma4:12b"
    }
}
