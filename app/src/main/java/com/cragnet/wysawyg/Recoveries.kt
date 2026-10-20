package com.cragnet.wysawyg

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import java.io.File

object Recoveries {
    @Volatile private var store: RecoveryStore? = null

    fun copy(context: Context, item: RecoveredTranscript): Boolean = try {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Dictation", item.text))
        true
    } catch (e: RuntimeException) {
        WysawygLogger.e("Could not copy full recovery transcript", e)
        false
    }

    @Synchronized fun get(context: Context): RecoveryStore = store ?: RecoveryStore(
        File(context.applicationContext.noBackupFilesDir, "recovered-transcripts.bin")
    ).also { store = it }
}
