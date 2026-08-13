package com.tcrrry.desktoplyrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val shouldStart = context
            .getSharedPreferences(LyricsOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(LyricsOverlayService.PREF_AUTO_START, false)
        if (!shouldStart) return

        ContextCompat.startForegroundService(
            context,
            Intent(context, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_START
            }
        )
    }
}
