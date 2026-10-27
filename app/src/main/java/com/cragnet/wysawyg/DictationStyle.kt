package com.cragnet.wysawyg

import android.content.SharedPreferences
import android.text.InputType

/** Where dictated text is going. Categories mirror how people actually write in each kind of app. */
enum class AppCategory(val key: String, val label: String, val defaultStyle: WritingStyle) {
    PERSONAL("personal", "Personal messages", WritingStyle.CASUAL),
    WORK("work", "Work messages", WritingStyle.CASUAL),
    EMAIL("email", "Email", WritingStyle.FORMAL),
    OTHER("other", "Everything else", WritingStyle.FORMAL);

    companion object {
        private val personal = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.plus",
            "org.thoughtcrime.securesms", "com.facebook.orca", "com.facebook.mlite", "com.instagram.android",
            "com.snapchat.android", "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "com.android.mms", "com.discord", "com.viber.voip", "jp.naver.line.android", "com.kakao.talk",
            "com.tencent.mm", "com.beeper.android", "com.textra", "com.facebook.katana", "com.zhiliaoapp.musically",
            "com.twitter.android", "com.instagram.barcelona", "com.reddit.frontpage", "com.bumble.app", "com.tinder"
        )
        private val work = setOf(
            "com.Slack", "com.microsoft.teams", "com.google.android.apps.dynamite", "com.linkedin.android",
            "com.zoom.videomeetings", "us.zoom.videomeetings", "com.asana.app", "com.clickup.app",
            "com.atlassian.android.jira.core", "com.basecamp.bc3", "com.mattermost.rn", "im.vector.app"
        )
        private val email = setOf(
            "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
            "ch.protonmail.android", "me.proton.android.mail", "com.yahoo.mobile.client.android.mail",
            "com.readdle.spark", "com.fsck.k9", "net.thunderbird.android", "com.easilydo.mail", "com.superhuman.mail",
            "com.android.email", "com.microsoft.office.outlook.dev"
        )

        fun of(packageName: String?, inputType: Int): AppCategory {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val textClass = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
            return when {
                packageName in personal -> PERSONAL
                packageName in work -> WORK
                packageName in email -> EMAIL
                textClass && variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> EMAIL
                textClass && variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> PERSONAL
                else -> OTHER
            }
        }
    }
}

enum class WritingStyle(val key: String, val label: String, val description: String) {
    FORMAL("formal", "Formal", "Capitals and full punctuation"),
    CASUAL("casual", "Casual", "Capitals, fewer commas, no full stop at the end"),
    VERY_CASUAL("very_casual", "Very casual", "lowercase, no commas or full stops");

    companion object {
        fun fromKey(key: String?): WritingStyle? = values().firstOrNull { it.key == key }
    }
}

/** Everything the formatter knows about the destination, captured when recording starts. */
data class DictationContext(
    val packageName: String? = null,
    val inputType: Int = InputType.TYPE_CLASS_TEXT,
    /** Text immediately before the cursor/selection; only the last few characters matter. */
    val textBefore: String = "",
    /** Text immediately after the cursor/selection. */
    val textAfter: String = ""
) {
    val category: AppCategory get() = AppCategory.of(packageName, inputType)

    /** Search boxes, URLs, addresses and names take bare words, never sentences. */
    val isShortField: Boolean get() {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val textClass = inputType and InputType.TYPE_MASK_CLASS
        // Some custom editors report no input type at all; treat those as ordinary text.
        if (textClass != InputType.TYPE_CLASS_TEXT) return textClass != InputType.TYPE_NULL
        return variation in setOf(
            InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS, InputType.TYPE_TEXT_VARIATION_FILTER,
            InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT
        )
    }

    /** Line-break commands only make sense where the editor accepts more than one line. */
    val isMultiLine: Boolean get() = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL ||
        inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

    companion object {
        /** Keeps only the nearby text, so nothing long is held or logged. */
        fun around(packageName: String?, inputType: Int, text: String, selectionStart: Int, selectionEnd: Int): DictationContext {
            val start = if (selectionStart < 0) text.length else selectionStart.coerceAtMost(text.length)
            val end = if (selectionEnd < 0) start else selectionEnd.coerceAtMost(text.length)
            val from = minOf(start, end)
            val to = maxOf(start, end)
            return DictationContext(packageName, inputType, text.substring(maxOf(0, from - 200), from), text.substring(to, minOf(text.length, to + 200)))
        }
    }
}

/** User choices for interpretation. Stored in the app's existing preferences file. */
data class StyleSettings(
    val matchApp: Boolean = true,
    val removeFillers: Boolean = true,
    val voiceCommands: Boolean = true,
    val smartCleanup: Boolean = false,
    val styles: Map<AppCategory, WritingStyle> = AppCategory.values().associateWith { it.defaultStyle }
) {
    fun styleFor(category: AppCategory): WritingStyle = styles[category] ?: category.defaultStyle

    fun save(editor: SharedPreferences.Editor): SharedPreferences.Editor = editor.apply {
        putBoolean(KEY_MATCH_APP, matchApp)
        putBoolean(KEY_FILLERS, removeFillers)
        putBoolean(KEY_COMMANDS, voiceCommands)
        putBoolean(KEY_SMART, smartCleanup)
        styles.forEach { (category, style) -> putString(KEY_STYLE_PREFIX + category.key, style.key) }
    }

    companion object {
        const val KEY_MATCH_APP = "style_match_app"
        const val KEY_FILLERS = "style_remove_fillers"
        const val KEY_COMMANDS = "style_voice_commands"
        const val KEY_SMART = "style_smart_cleanup"
        const val KEY_STYLE_PREFIX = "style_for_"
        val BOOLEAN_KEYS = listOf(KEY_MATCH_APP, KEY_FILLERS, KEY_COMMANDS, KEY_SMART)
        val STYLE_KEYS get() = AppCategory.values().map { KEY_STYLE_PREFIX + it.key }

        /** Keeps only well-typed values, so a hand-edited import can never break loading. */
        fun valid(key: String, value: Any?): Boolean = when (key) {
            in BOOLEAN_KEYS -> value is Boolean
            in STYLE_KEYS -> value is String && WritingStyle.fromKey(value) != null
            else -> false
        }

        fun load(prefs: SharedPreferences): StyleSettings {
            val stored = prefs.all
            fun flag(key: String, default: Boolean) = stored[key] as? Boolean ?: default
            return StyleSettings(
                flag(KEY_MATCH_APP, true),
                flag(KEY_FILLERS, true),
                flag(KEY_COMMANDS, true),
                flag(KEY_SMART, false),
                AppCategory.values().associateWith {
                    WritingStyle.fromKey(stored[KEY_STYLE_PREFIX + it.key] as? String) ?: it.defaultStyle
                }
            )
        }
    }
}
