package com.aidarbreeze.alwayson

import android.app.Notification
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager

/**
 * "A phone/messenger call is happening right now" detector, permission-free:
 *
 *  - a call-style notification is active (posted by the phone, Telegram, Max…
 *    — seen through the notification listener; covers VoIP call dialogs too),
 *  - the audio mode says so (MODE_RINGTONE while ringing, MODE_IN_CALL /
 *    MODE_IN_COMMUNICATION while talking) — works even for cellular calls
 *    without any READ_PHONE_STATE grant,
 *  - TelecomManager says the device is in a call (best effort).
 *
 * The StandBy screen (overlay + system screen saver) must step aside while a
 * call is up, so it never covers an incoming-call dialog.
 */
object CallState {

    /** Set/cleared by the notification listener when call notifs appear. */
    @Volatile
    var callNotificationActive = false

    /** True when [n] is a call notification (phone, Telegram, Max, …). */
    fun isCallNotification(n: Notification): Boolean {
        if (n.category == Notification.CATEGORY_CALL) return true
        // CallStyle (API 31+) tags its extras with these stable keys —
        // checked as literals so no new API constant is required.
        val extras = n.extras
        if (extras.containsKey("android.callPerson") ||
            extras.containsKey("android.callIsVideo")
        ) {
            return true
        }
        return false
    }

    /** True while a cellular or messenger call is ringing / in progress. */
    fun inCall(ctx: Context): Boolean {
        if (callNotificationActive) return true
        val am = ctx.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        when (am?.mode) {
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION -> return true
        }
        try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            // Note: TelecomManager has isInCall() only — "ringing" is covered
            // by MODE_RINGTONE above and by call notifications.
            if (tm != null && tm.isInCall) return true
        } catch (_: Exception) {
            // No READ_PHONE_STATE: the audio/notification signals above
            // already cover this case.
        }
        return false
    }

    /**
     * Lightweight poller that reports call-state FLIPS to [onActive]. Started
     * while a StandBy window is on screen (every 500 ms — a single
     * AudioManager.getMode() call), so the clock steps aside quickly even
     * for call apps that post no notification.
     */
    class Monitor(
        private val ctx: Context,
        private val onActive: (Boolean) -> Unit
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var last = false
        private var running = false

        private val task = object : Runnable {
            override fun run() {
                val now = inCall(ctx)
                if (now != last) {
                    last = now
                    onActive(now)
                }
                handler.postDelayed(this, 500L)
            }
        }

        fun start() {
            if (running) return
            running = true
            last = inCall(ctx)
            handler.removeCallbacks(task)
            handler.post(task)
        }

        fun stop() {
            running = false
            handler.removeCallbacks(task)
        }
    }
}
