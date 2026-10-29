package com.cragnet.wysawyg

import android.content.SharedPreferences

/** How much the floating microphone shows while recording. */
enum class RecordingIndicator(val key: String, val label: String, val description: String) {
    DETAILED("detailed", "Detailed", "A panel with the timer, a sound level meter, Stop and Stop & keep."),
    COMPACT("compact", "Compact", "A small pill with a recording dot, the timer and a stop button."),
    MINIMAL("minimal", "Minimal", "Just the microphone: it turns into a stop button with a pulsing ring. Tap to stop, hold to stop and keep audio.");

    companion object {
        const val KEY = "recording_indicator"
        fun fromKey(key: String?): RecordingIndicator? = values().firstOrNull { it.key == key }
        fun load(prefs: SharedPreferences): RecordingIndicator =
            fromKey(prefs.all[KEY] as? String) ?: DETAILED
    }
}
