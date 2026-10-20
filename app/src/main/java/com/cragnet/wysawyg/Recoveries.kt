package com.cragnet.wysawyg

import android.content.Context
import java.io.File

object Recoveries {
    @Volatile private var store: RecoveryStore? = null

    @Synchronized fun get(context: Context): RecoveryStore = store ?: RecoveryStore(
        File(context.applicationContext.noBackupFilesDir, "recovered-transcripts.bin")
    ).also { store = it }
}
