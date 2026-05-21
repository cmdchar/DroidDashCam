package com.helge.droiddashcam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_POWER_CONNECTED,
            "android.bluetooth.device.action.ACL_CONNECTED" -> {
                // In a real app, check shared prefs here
                Toast.makeText(context, "Dashcam Auto-Start Triggered", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
