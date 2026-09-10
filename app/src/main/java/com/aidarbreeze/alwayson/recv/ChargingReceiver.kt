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
 *
 * POWER_CONNECTED / BOOT_COMPLETED -> start the service in the foreground
 * (startForegroundService on O+, so the OS does not silently drop it while
 * the phone is locked / in the background).
 * POWER_DISCONNECTED -> the charge is over: drop the overlay and let the
 * service release the phone's resources.
 */
class ChargingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_BOOT_COMPLETED -> {
                if (!Prefs.autoStandby(context)) return
                try {
                    val i = Intent(context, OverlayService::class.java)
                    // The service decides from the live battery state whether
                    // the overlay is due right now (screen off + allowed time).
                    i.action = OverlayService.ACTION_REFRESH
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(i)
                    } else {
                        context.startService(i)
                    }
                } catch (_: Exception) {
                    // Starting a foreground service from a background broadcast
                    // can be blocked on some Android/OEM builds; opening the
                    // app once fixes it.
                }
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                try {
                    context.stopService(Intent(context, OverlayService::class.java))
                } catch (_: Exception) {
                }
            }
        }
    }
}
