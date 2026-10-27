package com.cragnet.wysawyg

import android.content.Context

/** Main-process entry point that turns a raw transcript into text that suits its destination. */
object Interpretation {
    suspend fun apply(context: Context, raw: String, destination: DictationContext): String {
        val target = DictationTestActivity.simulatedPackage
            ?.takeIf { destination.packageName == context.packageName }
            ?.let { destination.copy(packageName = it) } ?: destination
        val settings = StyleSettings.load(context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE))
        val prepared = DictationFormatter.prepare(raw, target, settings)
        // Very casual text loses its punctuation anyway, and short fields take bare words.
        val wantsModel = settings.smartCleanup && !target.isShortField && prepared.count { it == ' ' } >= 3 &&
            !(settings.matchApp && settings.styleFor(target.category) == WritingStyle.VERY_CASUAL)
        val punctuated = if (wantsModel) SmartCleanup.get(context).punctuate(prepared) ?: prepared else prepared
        val formatted = DictationFormatter.finish(punctuated, target, settings)
        WysawygLogger.i("Interpreted for ${target.category.key}${if (target.isShortField) " short field" else ""}: " +
            "${raw.length} -> ${formatted.length} chars")
        return formatted
    }
}
