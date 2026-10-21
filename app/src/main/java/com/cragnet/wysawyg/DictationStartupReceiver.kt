package com.cragnet.wysawyg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DictationStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        WysawygLogger.init(context)
        WysawygLogger.i("Restoring floating dictation after ${intent.action}")
        OverlayService.ensureRunning(context)
    }
}
