package com.gstlauncher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            val prefs = context.getSharedPreferences("GstLauncherPrefs", Context.MODE_PRIVATE)
            val autoStartEnabled = prefs.getBoolean("pref_auto_start_on_boot", true)

            if (autoStartEnabled) {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("EXTRA_AUTO_PLAY", true)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
