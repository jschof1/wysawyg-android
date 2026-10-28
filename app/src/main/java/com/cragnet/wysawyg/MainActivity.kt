package com.cragnet.wysawyg

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Home: is dictation ready, what is left to set up, and how text will be written. */
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
        private const val PREF_SETUP_COLLAPSED = "home_setup_collapsed"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemInsets()
        WysawygLogger.init(this)
        WysawygLogger.i("MainActivity started")

        findViewById<View>(R.id.startOverlayButton).setOnClickListener {
            if (hasPermissions()) startOverlayService() else requestPermissions()
        }
        findViewById<View>(R.id.stopOverlayButton).setOnClickListener {
            OverlayService.pause(this)
            render()
        }
        findViewById<View>(R.id.enableAccessibilityButton).setOnClickListener {
            requestAccessibility()
        }
        findViewById<View>(R.id.allowBackgroundButton).setOnClickListener {
            if (!batteryUnrestricted()) startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<View>(R.id.setupHeader).setOnClickListener {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_SETUP_COLLAPSED, !prefs.getBoolean(PREF_SETUP_COLLAPSED, false)).apply()
            render()
        }
        findViewById<View>(R.id.openWritingStyle).setOnClickListener {
            startActivity(Intent(this, WritingStyleActivity::class.java))
        }
        findViewById<View>(R.id.tryDictationButton).setOnClickListener {
            startActivity(Intent(this, DictationTestActivity::class.java))
        }
        findViewById<View>(R.id.recoveredTranscriptsButton).setOnClickListener {
            RecoveredTranscripts.show(this) { render() }
        }
        findViewById<View>(R.id.moreSettingsButton).setOnClickListener {
            startActivity(Intent(this, MoreSettingsActivity::class.java))
        }
        findViewById<View>(R.id.privacyPolicyButton).setOnClickListener {
            PrivacyConsent.showPrivacy(this) { render() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Quietly bring the service back; missing Accessibility is shown as a setup step, not a redirect.
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_OVERLAY_ENABLED, false) &&
            hasPermissions() && TextInjectorService.instance != null && !OverlayService.isRunning()) startOverlayService()
        render()
    }

    private fun hasPermissions() = OverlayService.hasPermissions(this)

    private fun batteryUnrestricted() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** Everything on this screen reflects the real permission and service state, re-read on every resume. */
    private fun render() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val enabled = OverlayService.isEnabled(this)
        val accessibility = PrivacyConsent.hasAccessibility(this) && TextInjectorService.instance != null
        val battery = batteryUnrestricted()
        val permissions = hasPermissions()
        val cloud = Transcriptions.effectiveMode(this) == TranscriptionMode.CLOUD

        val running = OverlayService.isRunning()
        val ready = enabled && permissions && accessibility && running
        // "On" only once the service is really running, never from the preference alone.
        val (statusRes, statusOk) = when {
            !enabled -> R.string.status_paused to false
            !permissions -> R.string.status_permissions_needed to false
            !accessibility -> R.string.status_setup_needed to false
            !running -> R.string.status_not_running to false
            else -> R.string.status_on to true
        }
        findViewById<TextView>(R.id.statusText).apply {
            setText(statusRes)
            setTextColor(getColor(if (statusOk) R.color.se_success else if (enabled) R.color.se_error else R.color.se_text_secondary))
        }
        findViewById<ImageView>(R.id.statusIcon).apply {
            setImageResource(if (statusOk) R.drawable.se_check_circle else if (enabled) R.drawable.se_warning else R.drawable.se_pause)
            imageTintList = getColorStateList(if (statusOk) R.color.se_success else if (enabled) R.color.se_error else R.color.se_text_secondary)
        }
        findViewById<TextView>(R.id.statusMeta).text = if (cloud) {
            getString(R.string.status_meta_cloud, Uri.parse(prefs.getString(PREF_ALARMA_URL, "") ?: "").host ?: "server")
        } else getString(R.string.status_meta_offline)
        // Turn on stays available whenever something must be granted or retried.
        val canStart = !enabled || !permissions || (accessibility && !running)
        findViewById<View>(R.id.startOverlayButton).visibility = if (canStart) View.VISIBLE else View.GONE
        findViewById<View>(R.id.stopOverlayButton).visibility = if (canStart) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.backgroundStatus).apply {
            text = when {
                enabled && !permissions -> getString(R.string.background_permissions_needed)
                enabled && !accessibility -> getString(R.string.background_accessibility_needed)
                enabled && !running -> getString(R.string.status_not_running_help)
                ready && !battery -> getString(R.string.status_battery_warning)
                else -> ""
            }
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        // Setup: a missing step always shows, whatever the person collapsed earlier.
        val done = listOf(accessibility, battery).count { it }
        val complete = done == 2
        val collapsed = complete && prefs.getBoolean(PREF_SETUP_COLLAPSED, false)
        findViewById<TextView>(R.id.setupTitle).setText(if (complete) R.string.setup_complete else R.string.setup_finish)
        findViewById<TextView>(R.id.setupCount).apply {
            text = getString(R.string.setup_count, done)
            visibility = if (complete) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.setupHeader).isClickable = complete
        findViewById<View>(R.id.setupExpand).apply {
            visibility = if (complete) View.VISIBLE else View.GONE
            rotation = if (collapsed) 0f else 180f
        }
        findViewById<View>(R.id.setupRows).visibility = if (collapsed) View.GONE else View.VISIBLE
        renderSetupRow(R.id.accessibilityIcon, R.id.accessibilityTitle, R.id.accessibilityHelp, accessibility,
            R.string.setup_accessibility_done, R.string.setup_accessibility_todo, required = true)
        renderSetupRow(R.id.batteryIcon, R.id.batteryTitle, R.id.batteryHelp, battery,
            R.string.setup_battery_done, R.string.setup_battery_todo, required = false)
        findViewById<View>(R.id.batteryChevron).visibility = if (battery) View.INVISIBLE else View.VISIBLE

        renderStyleHero(StyleSettings.load(prefs))

        val count = Recoveries.get(this).snapshot().items.size
        findViewById<TextView>(R.id.recoveredCount).text = count.toString()
        findViewById<View>(R.id.recoveredHelp).visibility = if (count > 0) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.footerText).setText(if (cloud) R.string.footer_cloud else R.string.footer_local)
    }

    private fun renderSetupRow(icon: Int, title: Int, help: Int, ok: Boolean, doneText: Int, todoText: Int, required: Boolean) {
        findViewById<ImageView>(icon).apply {
            setImageResource(if (ok) R.drawable.se_check_circle else R.drawable.se_warning)
            imageTintList = getColorStateList(if (ok) R.color.se_success else if (required) R.color.se_error else R.color.se_brass)
        }
        findViewById<TextView>(title).setText(if (ok) doneText else todoText)
        findViewById<View>(help).visibility = if (ok) View.GONE else View.VISIBLE
    }

    /** The two examples are produced by the real formatter with the saved styles. */
    private fun renderStyleHero(settings: StyleSettings) {
        val sample = getString(R.string.style_sample)
        val examples = listOf(
            Triple(R.id.styleExampleOneMeta, R.id.styleExampleOne, AppCategory.PERSONAL to "com.whatsapp"),
            Triple(R.id.styleExampleTwoMeta, R.id.styleExampleTwo, AppCategory.EMAIL to "com.google.android.gm"))
        val shown = if (settings.matchApp) settings else settings.copy(matchApp = true)
        examples.forEach { (metaId, textId, target) ->
            val (category, app) = target
            findViewById<TextView>(metaId).text = getString(R.string.style_example_meta,
                category.label.substringBefore(' '), settings.styleFor(category).label)
            findViewById<TextView>(textId).text = DictationFormatter.format(sample, DictationContext(app), shown)
        }
        findViewById<TextView>(R.id.styleBadge).apply {
            setText(if (settings.matchApp) R.string.status_on else R.string.status_off)
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (settings.matchApp) R.drawable.se_check_circle_small else 0, 0, 0, 0)
        }
        findViewById<View>(R.id.styleOffNote).visibility = if (settings.matchApp) View.GONE else View.VISIBLE
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            @Suppress("DEPRECATION") startActivityForResult(intent, OVERLAY_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED } && Settings.canDrawOverlays(this)) {
                startOverlayService()
            } else {
                Toast.makeText(this, "Speakeasy needs the microphone and permission to show over other apps.", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Overlay permission has no result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQUEST_CODE && Settings.canDrawOverlays(this) && hasPermissions()) startOverlayService()
    }

    private fun startOverlayService() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_OVERLAY_ENABLED, true).apply()
        if (!PrivacyConsent.hasAccessibility(this) || TextInjectorService.instance == null) {
            requestAccessibility()
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
        render()
        // The service starts asynchronously; look again once it has had a moment.
        window.decorView.postDelayed({ if (!isFinishing) render() }, 1000)
    }

    private fun requestAccessibility() {
        PrivacyConsent.requestAccessibility(this) {
            if (TextInjectorService.instance == null) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            else { TextInjectorService.instance?.refreshEditor(); render() }
        }
    }
}

/** Shared scrollable text dialog for licences and similar long text. */
internal fun AppCompatActivity.showLongText(title: String, body: String) {
    val padding = (20 * resources.displayMetrics.density).toInt()
    MaterialAlertDialogBuilder(this).setTitle(title)
        .setView(ScrollView(this).apply {
            addView(TextView(this@showLongText).apply {
                text = body
                setTextIsSelectable(true)
                setPadding(padding, padding / 2, padding, padding)
            })
        }).setPositiveButton("Close", null).show()
}
