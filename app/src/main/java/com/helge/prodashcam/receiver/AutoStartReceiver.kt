package com.helge.prodashcam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager
import com.helge.prodashcam.service.RecordingService

class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val shouldStart = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> prefs.getBoolean("boot_autostart", false)
            Intent.ACTION_POWER_CONNECTED -> prefs.getBoolean("charger_autostart", false)
            "android.bluetooth.device.action.ACL_CONNECTED" -> prefs.getBoolean("bluetooth_autostart", false)
            else -> false
        }

        if (shouldStart) {
            val startIntent = Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }
    }
}
