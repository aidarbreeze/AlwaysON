package com.aidarbreeze.alwayson

import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import com.aidarbreeze.alwayson.service.OverlayService
import com.aidarbreeze.alwayson.ui.StandbyUiState

/**
 * The StandBy host for the LOCKED screen.
 *
 * MIUI/HyperOS draws its own keyguard ABOVE third-party TYPE_APPLICATION_OVERLAY
 * windows, so the overlay service's clock window added while the keyguard is up
 * is never visible there: the screen lights, but the user still sees the lock
 * screen (the reported "экран загорается, но заставка не запускается").
 * Activities are the one mechanism guaranteed to display above the keyguard on
 * every vendor — that is how alarm and incoming-call screens work. This
 * activity therefore HOSTS the very same StandBy view while the device is
 * locked and charging, and finishes when the user unlocks, unplugs, taps the
 * clock, or the feature is switched off.
 *
 * It is launched by OverlayService (background activity starts are allowed for
 * apps holding SYSTEM_ALERT_WINDOW) and by the wake notification's
 * full-screen intent. singleTop plus the dismiss latch in the service keep a
 * "tap to dismiss" sticky for a minute, mirroring the overlay's behavior.
 */
class WakeActivity : Activity() {

    private var controller: StandbyController? = null

    private val finisher = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kill the OPEN transition by every available means. An animated
        // window enter FADES this (opaque black) window in over the keyguard,
        // and during the fade the keyguard shows through the semi-transparent
        // window — the user saw "the clock and the lock screen blended".
        // With a snap-in window the black curtain covers the keyguard in a
        // single frame, and only then does the content reveal begin.
        // windowAnimationStyle=@null in the theme is NOT enough: MIUI/HyperOS
        // runs its own task-enter animation, so override both the classic
        // (pre-34) and the API 34+ transition APIs.
        window.setWindowAnimations(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        // Show above the keyguard and light the screen on launch. The manifest
        // attributes only exist since API 27: on the minimum SDK 26 the
        // equivalent (deprecated) window flags are required.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // The desk clock must HOLD the screen for as long as it is shown —
        // an ACTIVITY's keep-screen-on flag is honoured by every OEM, unlike
        // the overlay window's flag on MIUI.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Launched from a stale wake notification after the feature was
        // switched off: clean up and leave.
        if (!Prefs.autoStandby(this)) {
            cancelWakeNotification()
            finish()
            return
        }

        setContentView(R.layout.standby_view)
        controller = StandbyController(this, findViewById(R.id.standbyRoot)).apply {
            // Black-curtain entry: ~1.2 s of pure black BEFORE the clock
            // starts fading in (the full sequence is over 2 s, per the user
            // request). The lock screen disappears under the black window
            // first; only then is the content revealed — the keyguard and
            // the clock are never visible at the same moment.
            entryDelayMs = 1200L
            // A tap on the clock hands the screen back to the keyguard; the
            // dismiss latch keeps it dismissed for a minute (the service must
            // not instantly re-host on its next evaluation).
            onTapExit = {
                OverlayService.notifyHostDismissed()
                finish()
            }
        }
        controller?.start()

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)        // unlocked -> hand over
            addAction(Intent.ACTION_POWER_DISCONNECTED)  // charge over -> done
            addAction(OverlayService.ACTION_STOP_HOST)   // service asks to stop
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finisher, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(finisher, f)
        }
        cancelWakeNotification()
    }

    override fun onResume() {
        super.onResume()
        // The preview flag keeps the overlay service from stacking its own
        // window on top of this screen, and stops the wake-notification
        // machinery while the clock is visibly up.
        StandbyUiState.previewVisible = true
        OverlayService.hostActive = true
    }

    override fun onPause() {
        super.onPause()
        StandbyUiState.previewVisible = false
        OverlayService.hostActive = false
    }

    override fun onDestroy() {
        StandbyUiState.previewVisible = false
        OverlayService.hostActive = false
        try {
            unregisterReceiver(finisher)
        } catch (_: Exception) {
        }
        controller?.stop()
        controller = null
        // The host is gone: let the service re-sync (it may fall back to the
        // overlay window on builds where that IS visible above the keyguard).
        OverlayService.requestReevaluate(this)
        super.onDestroy()
    }

    private fun cancelWakeNotification() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(OverlayService.NOTIF_ID_WAKE)
        } catch (_: Exception) {
        }
    }
}
