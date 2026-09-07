package com.aidarbreeze.alwayson.recv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.aidarbreeze.alwayson.Prefs
import com.aidarbreeze.alwayson.service.OverlayService

/** Re-enables the overlay after a reboot if the user left the switch on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.isOverlayActive(context)) return

        val i = Intent(context, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i)
        } else {
            context.startService(i)
        }
    }
}
