package com.aidarbreeze.alwayson.recv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.aidarbreeze.alwayson.Prefs
import com.aidarbreeze.alwayson.service.OverlayService

/**
 * Auto-starts the StandBy overlay when charging begins (or after a reboot
 * while a charge is connected), if the user left the feature on.
 */
class ChargingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_POWER_CONNECTED && action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        if (!Prefs.autoStandby(context)) return
        try {
            val i = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        } catch (_: Exception) {
            // Starting a foreground service from a background broadcast can be
            // blocked on some Android/OEM builds; opening the app once fixes it.
        }
    }
}
