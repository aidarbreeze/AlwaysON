package com.aidarbreeze.alwayson.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import com.aidarbreeze.alwayson.CallState
import com.aidarbreeze.alwayson.Prefs
import com.aidarbreeze.alwayson.R
import com.aidarbreeze.alwayson.StandbyController
import com.aidarbreeze.alwayson.ui.StandbyUiState
import kotlin.math.sqrt

/**
 * "StandBy while charging". Shows a dim, orientation-aware clock overlay
 * while the device is charging and resting. The rule: while charging, the
 * clock is shown whenever the screen is off (or locked); it reliably gives
 * the phone back (removes the overlay, revealing the normal lock/home
 * screen) the moment the user is actually using it:
 *
 *  - tapping the screen anywhere (a horizontal swipe instead flips between
 *    the clock page and the messenger notifications page),
 *  - unlocking / waking the screen,
 *  - picking the phone up (detected with the accelerometer),
 *  - or while a call is ringing / in progress (never cover a call dialog).
 *
 * Pressing the power button behaves exactly like a screen timeout: the
 * screen goes dark and the clock shows again. The overlay window carries
 * FLAG_KEEP_SCREEN_ON, so it relights the screen by itself; the one-shot
 * full-screen-intent notification is only a fallback for OEM builds that
 * ignore that. The window is opaque black from the very first frame (no
 * fade-in), so the lock screen can never flash through at transitions.
 */
class OverlayService : Service(), SensorEventListener {

    companion object {
        const val ACTION_HIDE = "com.aidarbreeze.alwayson.HIDE"
        const val ACTION_REFRESH = "com.aidarbreeze.alwayson.REFRESH"
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "alwayson_standby"
        // One-shot "wake the screen" notification (see tryWakeForStandby).
        const val NOTIF_ID_WAKE = 1002
        const val CHANNEL_WAKE = "alwayson_wake"
        // If the OEM ignores the wake notification, do not leave it in the
        // shade forever.
        const val WAKE_TIMEOUT_MS = 20_000L
        // How long the overlay window gets to relight the screen by itself
        // (via FLAG_KEEP_SCREEN_ON) before the full-screen-intent
        // notification is tried as a fallback.
        const val RELIGHT_CHECK_MS = 3_000L
        // Period of the "is the clock supposed to be up right now?" re-check.
        const val WAKE_GUARD_PERIOD_MS = 60_000L
        // How long our own relight counts as "no user activity" (see
        // relightGraceUntil).
        const val RELIGHT_GRACE_MS = 15_000L
        // How long a dismissed host stays dismissed: a tap on the clock must
        // not be undone by the very next evaluation.
        const val HOST_DISMISS_LATCH_MS = 60_000L
        // The service asks a running WakeActivity host to finish (feature
        // off, or active use on a keyguard-less device where no USER_PRESENT
        // will ever arrive).
        const val ACTION_STOP_HOST = "com.aidarbreeze.alwayson.STOP_HOST"
        // Process-wide host state; WakeActivity updates both (same process).
        @Volatile
        var hostActive = false
        @Volatile
        var hostDismissedUntil = 0L

        /** The user tapped the clock away: do not re-host for a minute. */
        fun notifyHostDismissed() {
            hostDismissedUntil = System.currentTimeMillis() + HOST_DISMISS_LATCH_MS
        }
        const val WAKE_MAX_ATTEMPTS = 3

        fun requestReevaluate(context: Context) {
            try {
                val i = Intent(context, OverlayService::class.java)
                i.action = ACTION_REFRESH
                context.startService(i)
            } catch (_: Exception) {
                // service unavailable; ignore
            }
        }

        /** True while the phone is on a working charger: it must be PLUGGED
         *  (AC / USB / wireless) AND not in a draining/unknown state. A FULL
         *  status alone does not prove the device is still on power.
         *
         *  Single source of truth: BatteryInfo.isCharging. The two copies
         *  of this check had drifted apart on the odd edge statuses (e.g. a
         *  missing sticky broadcast), so the overlay and the diagnostics
         *  could disagree about "charging" on the same device. */
        fun isCharging(context: Context): Boolean =
            com.aidarbreeze.alwayson.BatteryInfo.isCharging(context)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var controller: StandbyController? = null
    private var orientationListener: OrientationEventListener? = null

    // While a call (cellular or messenger) is up, the clock must step aside
    // so it never covers the incoming-call dialog; it comes back when the
    // call ends (if the StandBy rules still allow it).
    private val callMonitor = CallState.Monitor(this) { active ->
        if (active) removeOverlay() else evaluateAndSync()
    }

    private var shownOrientationType = -1

    // While true, a one-shot "wake" notification is in the shade (see
    // tryWakeForStandby); it is removed as soon as the screen comes back or
    // the overlay is shown.
    private var wakeArmed = false
    // Bright wake lock used to RELIGHT a dark display when the overlay is
    // added: FLAG_KEEP_SCREEN_ON only KEEPS an already-lit screen on, it
    // never wakes one (see showOverlay).
    private var screenWake: PowerManager.WakeLock? = null
    // Until this moment the "screen on + keyguard NOT locked" state must not
    // be read as active use: after OUR OWN relight nobody touched the phone,
    // yet without the grace the SCREEN_ON evaluation removed the clock at
    // once, the screen timed out, SCREEN_OFF re-added it, and the display
    // looped dark/lit forever (observed on MIUI). A REAL user still exits
    // immediately (tap on the clock / USER_PRESENT).
    private var relightGraceUntil = 0L
    // Bounded retries: if the OEM ignores the notification (screen never
    // comes on), do not flash a new one forever — three attempts per
    // charge/session, then wait for a real event (plug, boot, screen).
    private var wakeAttempts = 0
    // True once startForeground() has run for THIS instance. onCreate fires
    // only for a fresh instance: a startForegroundService() delivered to an
    // already-alive (quiet) instance must promote it here in
    // onStartCommand, or the system crashes us after 5 s with
    // "did not call startForeground".
    private var foregroundStarted = false
    private val wakeTimeout = Runnable {
        // The screen never came on from the notification — drop it anyway.
        if (overlayView == null) cancelWake()
    }
    // The wake rule must hold in EVERY state, not only on events: while
    // charging, if the screen is off and the clock is not showing, bring it
    // back. After a Power-button exit no further screen events may ever
    // arrive, so re-check periodically.
    private val wakeGuard = object : Runnable {
        override fun run() {
            // Note: no `overlayView == null` pre-check — the clock window can
            // be attached while the screen is still dark (an OEM that ignores
            // FLAG_KEEP_SCREEN_ON); in that state the clock is not visible
            // and the wake notification is exactly what may relight it.
            if (Prefs.autoStandby(this@OverlayService) &&
                !screenOn &&
                !wakeArmed
            ) {
                tryWakeForStandby()
            }
            // RE-POST UNCONDITIONALLY: the guard must stay alive even when
            // the feature is off right now — a warm feature-toggle does not
            // re-run onCreate, and the very next event may switch the
            // feature on again.
            handler.postDelayed(this, WAKE_GUARD_PERIOD_MS)
        }
    }

    // The overlay window carries FLAG_KEEP_SCREEN_ON: on most devices a
    // freshly attached overlay relights a screen that just went dark. Some
    // OEM builds ignore that for a locked screen — for them the clock window
    // can sit attached while the display stays dark, so after a short grace
    // the full-screen-intent notification relights the screen instead.
    private val relightCheck = Runnable {
        if (!isScreenInteractive()) {
            tryWakeForStandby()
        }
    }

    private fun scheduleRelightCheck() {
        handler.removeCallbacks(relightCheck)
        handler.postDelayed(relightCheck, RELIGHT_CHECK_MS)
    }

    // Tracked so we only auto-show the clock when the phone is resting (locked
    // or screen off), never over an app the user is actively using. Seeded
    // from the real state below — a stale "true" would hide the clock when
    // the service is started while the screen is already off (stand).
    // Fast cache of the screen state for event-driven pre-checks (the wake
    // guard). The actual decisions ALWAYS read the live state via
    // isScreenInteractive() — this variable goes stale the moment events are
    // missed (doze, OEM quirks, service restarts).
    private var screenOn = false

    // Accelerometer "pick up" detection.
    private var sensorManager: SensorManager? = null
    private var accelRegistered = false
    private val gravity = FloatArray(3)
    private var startedAt = 0L
    private var movementStreak = 0
    // The user may press Power while still holding the phone and then walk
    // it to the stand — movement right after showing must NOT count as a
    // pickup. The phone must be still for [settleMs] before detection arms.
    private var stillSince = 0L
    private var settled = false
    private val settleMs = 8_000L

    // Last orientation type the rotation listener already evaluated, so the
    // (frequent) sensor callbacks only trigger a re-check on real rotations.
    private var lastSeenOrientationType = -1

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_DISCONNECTED -> stopSelf()
                Intent.ACTION_POWER_CONNECTED -> {
                    // A fresh charge session resets the whole wake cycle.
                    wakeAttempts = 0
                    wakeArmed = false
                    cancelWake()
                    // Re-seed the screen cache: the event arrived while the
                    // service was quiet, the cache may be stale.
                    screenOn = isScreenInteractive()
                    evaluateAndSync()
                    // Plugged in with the screen dark: evaluateAndSync should
                    // have shown the clock; if the screen is still dark
                    // (an OEM that ignores FLAG_KEEP_SCREEN_ON) the
                    // notification is the fallback.
                    if (!isScreenInteractive()) {
                        scheduleRelightCheck()
                    }
                }
            }
        }
    }

    // The auto-standby is schedule-gated (e.g. night only). The answer can
    // only flip while charging continues when the clock hour changes, so
    // re-evaluate every minute: ACTION_TIME_TICK is the protected per-minute
    // broadcast that arrives by itself (ACTION_TIME_CHANGED alone fires only
    // on a manual time change, so a phone left on the charger would never
    // enter/leave the scheduled window by itself).
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            evaluateAndSync()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    if (overlayView != null) {
                        // The desk clock was showing and the user pressed
                        // Power. The Power button behaves EXACTLY like a
                        // screen timeout: "charging + screen off" means the
                        // clock must be up. Detach and re-evaluate below —
                        // the fresh overlay window relights the display via
                        // FLAG_KEEP_SCREEN_ON.
                        removeOverlay()
                    }
                    // Screen went dark (timeout or Power). If we're still
                    // charging, the phone is resting -> become a clock.
                    evaluateAndSync()
                    // If the screen is still dark (the window has not
                    // relit it yet, or the OEM ignores KEEP_SCREEN_ON),
                    // the full-screen-intent notification is the fallback.
                    if (!isScreenInteractive()) {
                        scheduleRelightCheck()
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // The display is lit: any pending "is it still dark?"
                    // check is moot now.
                    handler.removeCallbacks(relightCheck)
                    // The screen responded — the previous wake cycle is over,
                    // reset the attempt budget for the next one.
                    wakeAttempts = 0
                    // e.g. plugging the cable / waking. If the phone is resting
                    // (still locked) and charging, this shows the clock.
                    evaluateAndSync()
                    // Whatever the outcome, the one-shot wake notification has
                    // done (or lost) its job: keep the shade clean.
                    cancelWake()
                }
            }
        }
    }

    /** User unlocked / woke the phone -> hand it back. */
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT && overlayView != null) {
                exitStandby()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Seed the screen state from the device, not from a guess: the service
        // is often started by a POWER_CONNECTED broadcast while the phone is
        // resting on a stand with the screen off.
        screenOn = isScreenInteractive()

        registerGuarded(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        registerGuarded(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)        // every minute, protected broadcast
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        registerGuarded(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        registerGuarded(userPresentReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
        })

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        // Enabled only while the overlay is actually shown (see
        // showOverlay/removeOverlay) — the sensor otherwise polls for the
        // whole life of the foreground service for nothing.
        orientationListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(orientation: Int) {
                val type = currentOrientationType()
                if (type == lastSeenOrientationType) return
                lastSeenOrientationType = type
                if (overlayView != null && type != shownOrientationType) {
                    // Layout depends on orientation -> rebuild it.
                    removeOverlay()
                }
                evaluateAndSync()
            }
        }

        // The wake rule (charging + screen off + no clock -> wake it up) must
        // hold even when no event is coming (e.g. after a Power-button exit),
        // so re-check it periodically; the check itself is cheap.
        handler.removeCallbacks(wakeGuard)
        handler.postDelayed(wakeGuard, WAKE_GUARD_PERIOD_MS)

        // The call monitor is NOT started here: this service also lives as a
        // quiet foreground service while NOT charging, and a 500 ms poll must
        // not run around the clock. evaluateAndSync() starts/stops it — it
        // only polls while the feature is on AND the device is on power.

        // Promote to a foreground service only when the feature is enabled:
        // the "reevaluate" requests from the preview use a plain startService
        // and must not flash a persistent notification for a disabled feature.
        // The startForegroundService callers (ChargingReceiver, the settings
        // switch) always start us with the feature on, so that contract holds.
        if (Prefs.autoStandby(this)) {
            startAsForeground()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                Prefs.setAutoStandby(this, false)
                cancelWake()
                removeOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // onCreate ran at most once, but a startForegroundService can
                // arrive at an instance that was started quietly (plain
                // startService while the feature was off) — satisfy the FGS
                // contract here, NOT above: the ACTION_HIDE path comes in as
                // a plain startService, and promoting before turning the
                // feature off only flashed the notification for an instant.
                if (Prefs.autoStandby(this) && !foregroundStarted) startAsForeground()
                // The service may be starting in the MIDDLE of a charging
                // session (boot on the charger, app opened, receiver start)
                // — so the decision below reads the CURRENT live battery and
                // screen state instead of only waiting for the next
                // POWER_CONNECTED event.
                screenOn = isScreenInteractive()
                evaluateAndSync()
                // Started with the screen dark: evaluateAndSync should have
                // shown the clock; if the screen is still dark (an OEM that
                // ignores FLAG_KEEP_SCREEN_ON) the notification is the
                // fallback.
                if (!isScreenInteractive()) {
                    scheduleRelightCheck()
                }
                // A disabled feature must not keep (or revive) us: the system
                // would otherwise restart a sticky service we just stopped.
                return if (Prefs.autoStandby(this)) START_STICKY else START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        foregroundStarted = false
        cancelWake()
        callMonitor.stop()
        removeOverlay()
        unregisterSensors()
        try {
            unregisterReceiver(powerReceiver)
        } catch (_: IllegalArgumentException) {
        }
        try {
            unregisterReceiver(timeReceiver)
        } catch (_: IllegalArgumentException) {
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }
        try {
            unregisterReceiver(userPresentReceiver)
        } catch (_: IllegalArgumentException) {
        }
        orientationListener?.disable()
        orientationListener = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Register a context receiver; on API 33+ also mark it exported so the
     *  protected system broadcasts (power / screen / user present) reach us. */
    private fun registerGuarded(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    // ---------- screen management ----------

    private fun currentOrientationType(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 1 else 0

    /** Live screen state — always read from the device, never from the
     *  (possibly stale) [screenOn] cache. */
    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        return pm?.isInteractive ?: false
    }

    private fun evaluateAndSync() {
        // 1) The feature is off -> never show, and never keep the
        //    foreground service (and its notification) alive either. This
        //    service can still be started as a side effect (e.g. the
        //    full-screen preview asks us to re-evaluate on open/close).
        if (!Prefs.autoStandby(this)) {
            callMonitor.stop()
            removeOverlay()
            stopSelf()
            return
        }

        // 2) No power -> no StandBy screen. Stay armed (as a quiet
        //    foreground service) so that plugging the cable in later is
        //    caught by our own POWER_CONNECTED receiver reliably, without
        //    the app having to launch itself from the background.
        if (!isCharging(this)) {
            callMonitor.stop()
            removeOverlay()
            return
        }

        // On power (and the feature is on): hide at once when a call starts
        // and re-evaluate when it ends. start() is idempotent.
        callMonitor.start()

        // 3) No overlay permission -> nothing we can draw; do not try.
        if (!Settings.canDrawOverlays(this)) {
            removeOverlay()
            return
        }

        // 4) Outside the allowed hours (the schedule gate, e.g. night only).
        if (!Prefs.isStandbyTimeAllowed(this, java.util.Calendar.getInstance())) {
            removeOverlay()
            return
        }

        // 4b) A call is ringing / in progress (cellular, Telegram, Max…):
        //     never cover the incoming-call dialog.
        if (CallState.inCall(this)) {
            removeOverlay()
            return
        }

        // 5) The user is looking at the in-app preview — it owns the screen.
        if (StandbyUiState.previewVisible) {
            removeOverlay()
            return
        }

        // 6) The system daydream owns the screen (if the firmware honours it).
        if (StandbyUiState.dreaming) {
            removeOverlay()
            return
        }

        // 7) The screen state is read LIVE (PowerManager.isInteractive) —
        //    never from the possibly stale `screenOn` cache:
        //    - screen OFF            -> desk clock (the phone is resting).
        //    - screen ON but locked  -> desk clock over the keyguard.
        //    - screen ON and unlocked-> the user is using an app; never
        //                               cover it while the phone charges.
        // MIUI/HyperOS draws its keyguard ABOVE third-party overlay windows:
        // an overlay added while the keyguard is up is never VISIBLE there
        // (the screen lights, the user still sees the lock screen).
        // Activities are the one display path guaranteed above the keyguard
        // on every vendor, so both resting states are hosted in WakeActivity;
        // the overlay window remains only as the OEM-blocked fallback.
        if (!isScreenInteractive()) {
            hostInActivity(needWake = true)
        } else if ((getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager)
                ?.isKeyguardLocked == true
        ) {
            hostInActivity(needWake = false)
        } else if (System.currentTimeMillis() < relightGraceUntil) {
            // Relight grace: the screen was lit by US seconds ago — an
            // unlocked interactive device on the charger is the phone resting,
            // not in use. Keep the current clock host/overlay as is.
            cancelWake()
        } else {
            removeOverlay()
            cancelWake()
            // Active use (or a keyguard-less device nobody will unlock):
            // a running host must hand the screen over.
            if (hostActive) {
                hostDismissedUntil = System.currentTimeMillis() + HOST_DISMISS_LATCH_MS
                sendBroadcast(Intent(ACTION_STOP_HOST).setPackage(packageName))
            }
        }
    }

    /** Show the locked-screen clock in [WakeActivity] (visible above the
     *  keyguard on every vendor); fall back to the overlay window when a
     *  background activity start is blocked by the OEM. */
    private fun hostInActivity(needWake: Boolean) {
        if (System.currentTimeMillis() < hostDismissedUntil) return
        if (needWake) wakeDisplay()
        removeOverlay()
        if (hostActive) return // the host is already up
        try {
            startActivity(
                Intent(this, com.aidarbreeze.alwayson.WakeActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
        } catch (_: Exception) {
            // Background start blocked: the old overlay path.
            showOverlay()
        }
    }

    /** Light the display up when the clock (overlay window or WakeActivity
     *  host) is added on a DARK screen: FLAG_KEEP_SCREEN_ON only keeps an
     *  already-lit screen on, it never wakes one. ACQUIRE_CAUSES_WAKEUP turns
     *  the display on at once; after the 10 s safety timeout the window's or
     *  activity's own FLAG_KEEP_SCREEN_ON keeps it lit. Arms the relight
     *  grace (see relightGraceUntil). */
    private fun wakeDisplay() {
        if (isScreenInteractive()) return
        relightGraceUntil = System.currentTimeMillis() + RELIGHT_GRACE_MS
        try {
            @Suppress("DEPRECATION")
            val wl = (getSystemService(POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "alwayson:overlay"
                )
            if (wl != null) {
                wl.acquire(10_000L)
                screenWake = wl
            }
        } catch (_: Exception) {
            // Wake lock refused (policy): the wake notification
            // remains the fallback path.
        }
    }

    // ---------- "wake the screen" notification ----------
    //
    // Rule (per user request): while the phone is CHARGING and the screen is
    // OFF without showing the StandBy clock (e.g. the user locked it with the
    // Power button or the on-screen lock button), post a one-shot silent
    // notification whose fullScreenIntent lights the screen. On some OEM
    // builds the overlay alone does not appear in that state, but a
    // full-screen-intent notification reliably wakes the locked screen; once
    // it is lit, the normal evaluateAndSync() shows the clock over the
    // keyguard. The notification is removed as soon as it is no longer
    // needed (screen on / overlay shown / timeout) so it never clutters the
    // notification shade.

    private fun tryWakeForStandby() {
        // Deliberately NO `overlayView == null` early-return: the clock
        // window can be attached while the screen is still dark (an OEM that
        // ignores FLAG_KEEP_SCREEN_ON) — in that state the clock is not
        // visible and the notification is exactly what may relight the
        // screen.
        if (isScreenInteractive()) return   // live check: nothing to wake
        if (!Prefs.autoStandby(this)) return
        if (!isCharging(this)) return
        if (StandbyUiState.previewVisible || StandbyUiState.dreaming) return
        if (CallState.inCall(this)) return  // never over a call screen
        if (!Prefs.isStandbyTimeAllowed(this, java.util.Calendar.getInstance())) return
        if (!Settings.canDrawOverlays(this)) return
        if (wakeAttempts >= WAKE_MAX_ATTEMPTS) return // OEM ignored us before

        if (!postWakeNotification()) return // notifications denied: skip the
                                            // whole wake cycle, budget intact
        wakeArmed = true
        wakeAttempts++

        handler.removeCallbacks(wakeTimeout)
        handler.postDelayed(wakeTimeout, WAKE_TIMEOUT_MS)
    }

    /** Posts the one-shot silent full-screen wake notification. Returns false
     *  if notifications are not allowed (the wake is silently skipped). */
    private fun postWakeNotification(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        if (nm.getNotificationChannel(CHANNEL_WAKE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WAKE,
                    getString(R.string.notif_channel_wake),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        val content = PendingIntent.getActivity(
            this, 2, Intent(this, com.aidarbreeze.alwayson.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fullScreen = PendingIntent.getActivity(
            this, 3, Intent(this, com.aidarbreeze.alwayson.WakeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_stat_standby)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_wake_text))
            .setContentIntent(content)
            .setFullScreenIntent(fullScreen, true)
            .setAutoCancel(true)
            // Silence comes from the soundless channel above (a high-importance
            // channel with no sound/vibration still wakes the screen).
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        return try {
            nm.notify(NOTIF_ID_WAKE, notif)
            true
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied: we cannot wake the screen this way;
            // the clock will show on the next screen-on transition instead.
            false
        }
    }

    /** Remove the one-shot wake notification (idempotent). */
    private fun cancelWake() {
        handler.removeCallbacks(wakeTimeout)
        if (!wakeArmed) return
        wakeArmed = false
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIF_ID_WAKE)
        } catch (_: Exception) {
            // already gone
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        // R.layout.standby_view resolves to the portrait or landscape layout
        // automatically based on the current orientation.
        val view = inflater.inflate(R.layout.standby_view, null)

        // The overlay intentionally CONSUMES touches while it is shown:
        // a quick tap anywhere exits back to the normal screen, a horizontal
        // swipe flips between the clock and notifications pages, and the
        // player prev/next buttons need touches too. All of that is handled
        // by StandbyController's root gestures (an earlier FLAG_NOT_TOUCHABLE
        // here silently broke BOTH taps and the media buttons).
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            // OPAQUE, not TRANSLUCENT: the window is a full black screen and
            // an opaque surface is composited immediately — the lock screen
            // behind can never flash through during show/hide transitions
            // (the "blink" the user saw when the StandBy toggled).
            PixelFormat.OPAQUE
        )
        params.gravity = Gravity.CENTER
        // No system window animation (an open/close animation briefly shows
        // what is behind — i.e. the keyguard — at every transition).
        params.windowAnimations = 0
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.addView(view, params)
        } catch (_: Exception) {
            return
        }
        overlayView = view
        shownOrientationType = currentOrientationType()
        cancelWake() // the clock is up — the wake notification is no longer needed

        // A dark display must be lit for the clock to be seen at all (see
        // wakeDisplay; also arms the relight grace).
        wakeDisplay()

        val c = StandbyController(this, view)
        c.onTapExit = { exitStandby() }
        c.start()
        controller = c

        startedAt = System.currentTimeMillis()
        for (i in 0 until 3) gravity[i] = 0f
        movementStreak = 0
        stillSince = 0L
        settled = false
        registerSensors()
        // Rotation now matters: rebuild the overlay on orientation changes.
        lastSeenOrientationType = -1 // force the first evaluation
        orientationListener?.enable()
    }

    private fun removeOverlay() {
        // The relight lock must not outlive the window.
        try {
            screenWake?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        screenWake = null
        unregisterSensors()
        // Nothing shown -> no need to poll the rotation sensor.
        orientationListener?.disable()
        val view = overlayView ?: run {
            controller?.stop()
            controller = null
            return
        }
        overlayView = null
        controller?.onTapExit = null
        controller?.stop()
        controller = null
        view.setOnTouchListener(null)
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (_: Exception) {
            // already detached
        }
    }

    /** The user is using the phone (tap / unlock / pickup): hide the clock
     *  now. The StandBy rule brings it back on the next screen-off/lock
     *  transition while charging. */
    private fun exitStandby() {
        removeOverlay()
    }

    // ---------- pick-up detection (accelerometer) ----------

    private fun registerSensors() {
        val sm = sensorManager ?: return
        if (accelRegistered) return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor != null) {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            accelRegistered = true
        }
    }

    private fun unregisterSensors() {
        if (!accelRegistered) return
        sensorManager?.unregisterListener(this)
        accelRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val now = System.currentTimeMillis()
        // Ignore the first moments (placing the phone) so it doesn't exit on
        // the initial movement when you set it on the charger.
        if (now - startedAt < 6000) return

        val alpha = 0.8f
        for (i in 0 until 3) {
            gravity[i] = alpha * gravity[i] + (1f - alpha) * event.values[i]
        }
        val dx = event.values[0] - gravity[0]
        val dy = event.values[1] - gravity[1]
        val dz = event.values[2] - gravity[2]
        val magnitude = sqrt(dx * dx + dy * dy + dz * dz)

        if (magnitude > 2.2f) {
            if (settled) {
                // The phone WAS resting and now moves: count it as a pickup
                // once the movement is sustained.
                movementStreak++
                if (movementStreak > 6) handler.post { exitStandby() }
            } else {
                // Still placing it (Power press, walk to the stand): the
                // stillness window restarts, no exit.
                stillSince = 0L
            }
            return
        }

        movementStreak = 0
        if (!settled) {
            if (stillSince == 0L) stillSince = now
            if (now - stillSince >= settleMs) settled = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }

    // ---------- foreground service notification ----------

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, com.aidarbreeze.alwayson.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hideIntent = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hide = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_standby),
            getString(R.string.notif_hide), hideIntent
        ).build()

        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_standby)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(hide)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        foregroundStarted = true
    }
}
