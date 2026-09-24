package com.aidarbreeze.alwayson

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.aidarbreeze.alwayson.service.OverlayService

/**
 * Invisible helper launched by the "wake" notification's fullScreenIntent.
 *
 * Scenario: the phone is charging, resting on the stand, and the user locked
 * it with the Power button (or the on-screen lock button). On some OEM builds
 * the StandBy overlay does not appear on its own in that state. The system
 * delivers this activity full-screen over the keyguard, and its
 * [setTurnScreenOn] turns the screen ON (still locked) — after which
 * [OverlayService] draws the clock over the locked screen as usual.
 *
 * The activity does not unlock the device, does not show any UI and finishes
 * immediately; the wake notification is removed as well so it never lingers
 * in the shade.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No window animation for this invisible helper: an open animation
        // would briefly flash whatever is behind it (the keyguard) on the
        // way to relighting the screen.
        window.setWindowAnimations(0)
        // Keep the keyguard up; we only need the screen lit.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // The overlay service re-evaluates on start and shows the clock now
        // that the screen is on (and still locked). If the service was killed
        // this also revives it.
        if (Prefs.autoStandby(this)) {
            try {
                val i = Intent(this, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(i)
                } else {
                    startService(i)
                }
            } catch (_: Exception) {
                // Service unavailable — the screen is already lit, nothing else to do.
            }
        }
        // Remove the one-shot wake notification (the service cancels it too;
        // either way the shade stays clean).
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(OverlayService.NOTIF_ID_WAKE)
        } catch (_: Exception) {
            // ignore
        }
        finish()
    }
}
