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
import android.widget.Toast
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

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

    private lateinit var settingsExporter: SettingsExporter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WysawygLogger.init(this)
        settingsExporter = SettingsExporter(this)

        alarmaUrlInput = findViewById(R.id.alarmaUrl)
        apiKeyInput = findViewById(R.id.apiKey)
        modelInput = findViewById(R.id.modelName)
        promptInput = findViewById(R.id.systemPrompt)

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
        findViewById<Button>(R.id.recoveredTranscriptsButton).text = "Recovered transcripts ($count)"
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
        AlertDialog.Builder(this).setTitle("Recovered transcripts • saved for 24h")
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
            }.setNegativeButton("Close", null).show()
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

    private fun saveSettings() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putString(PREF_ALARMA_URL, alarmaUrlInput.text.toString().ifBlank { "https://ollama.com/api/chat" })
            putString(PREF_API_KEY, apiKeyInput.text.toString())
            putString(PREF_MODEL, modelInput.text.toString().ifBlank { defaultModel(alarmaUrlInput.text.toString()) })
            putString(PREF_SYSTEM_PROMPT, promptInput.text.toString())
            apply()
        }
        WysawygLogger.i("Settings saved")
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val url = prefs.getString(PREF_ALARMA_URL, "https://ollama.com/api/chat") ?: "https://ollama.com/api/chat"
        alarmaUrlInput.setText(url)
        apiKeyInput.setText(prefs.getString(PREF_API_KEY, ""))
        modelInput.setText(prefs.getString(PREF_MODEL, defaultModel(url)))
        promptInput.setText(prefs.getString(PREF_SYSTEM_PROMPT, "Transcribe the audio exactly. Output only the spoken words, no commentary."))
    }

    private fun defaultModel(url: String): String {
        return if (url.contains("ollama.com")) "gemma4:e4b" else "gemma4:12b"
    }
}
