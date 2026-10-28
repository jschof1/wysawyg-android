package com.cragnet.wysawyg

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object PrivacyConsent {
    private const val STORE = "speakeasy_privacy"
    private const val ACCESSIBILITY = "accessibility_v1"
    private const val CLOUD = "cloud_origin_v1"
    private fun prefs(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun hasAccessibility(context: Context) = prefs(context).getBoolean(ACCESSIBILITY, false)
    fun hasCloud(context: Context, destination: CloudDestination) =
        prefs(context).getString(CLOUD, null) == destination.origin

    fun requestAccessibility(activity: AppCompatActivity, accepted: () -> Unit) {
        if (hasAccessibility(activity)) { accepted(); return }
        MaterialAlertDialogBuilder(activity).setTitle(R.string.accessibility_consent_title)
            .setMessage(R.string.accessibility_consent_body)
            .setPositiveButton(R.string.agree_continue) { _, _ ->
                if (prefs(activity).edit().putBoolean(ACCESSIBILITY, true).commit()) accepted()
                else Toast.makeText(activity, R.string.consent_save_failed, Toast.LENGTH_LONG).show()
            }.setNegativeButton(R.string.not_now, null).show()
    }

    fun requestCloud(activity: AppCompatActivity, destination: CloudDestination, accepted: () -> Unit, declined: () -> Unit) {
        if (hasCloud(activity, destination)) { accepted(); return }
        MaterialAlertDialogBuilder(activity).setTitle(R.string.cloud_consent_title)
            .setMessage(activity.getString(R.string.cloud_consent_body, destination.origin))
            .setPositiveButton(R.string.agree_continue) { _, _ ->
                if (prefs(activity).edit().putString(CLOUD, destination.origin).commit()) accepted()
                else { declined(); Toast.makeText(activity, R.string.consent_save_failed, Toast.LENGTH_LONG).show() }
            }.setNegativeButton(R.string.not_now) { _, _ -> declined() }
            .setOnCancelListener { declined() }.show()
    }

    fun revokeAccessibility(context: Context) {
        prefs(context).edit().remove(ACCESSIBILITY).commit()
        OverlayService.pause(context)
        TextInjectorService.instance?.disableSelf()
    }

    fun revokeCloud(context: Context) {
        prefs(context).edit().remove(CLOUD).commit()
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(TranscriptionMode.KEY, TranscriptionMode.OFFLINE.name).commit()
    }

    fun showPrivacy(activity: AppCompatActivity, changed: () -> Unit = {}) {
        val body = activity.assets.open("privacy-policy.txt").bufferedReader().use { it.readText() }
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(activity).setTitle(R.string.privacy_permissions)
            .setView(android.widget.ScrollView(activity).apply {
                addView(android.widget.TextView(activity).apply {
                    text = body
                    setTextIsSelectable(true)
                    setPadding(padding, padding / 2, padding, padding)
                })
            }).setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.revoke_accessibility) { _, _ -> revokeAccessibility(activity); changed() }
            .setNeutralButton(R.string.revoke_cloud) { _, _ -> revokeCloud(activity); changed() }.show()
    }
}
