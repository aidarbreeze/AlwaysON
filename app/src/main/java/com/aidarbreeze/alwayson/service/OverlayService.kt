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
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
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
 *  - tapping the screen anywhere,
 *  - unlocking / waking the screen,
 *  - or picking the phone up (detected with the accelerometer).
 *
 * Pressing the power button behaves exactly like a screen timeout: the
 * screen goes dark and the clock shows again. The overlay window carries
 * FLAG_KEEP_SCREEN_ON, so it relights the screen by itself; the one-shot
 * full-screen-intent notification is only a fallback for OEM builds that
 * ignore that.
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
         *  status alone does not prove the device is still on power. */
        fun isCharging(context: Context): Boolean {
            val intent = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return false

            val status = intent.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN
            )

            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

            val connectedToPower =
                plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                    plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                    plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS

            val validBatteryState =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            return connectedToPower && validBatteryState
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var controller: StandbyController? = null
    private var orientationListener: OrientationEventListener? = null

    private var shownOrientationType = -1

    // While true, a one-shot "wake" notification is in the shade (see
    // tryWakeForStandby); it is removed as soon as the screen comes back or
    // the overlay is shown.
    private var wakeArmed = false
    // Bounded retries: if the OEM ignores the notification (screen never
    // comes on), do not flash a new one forever — three attempts per
    // charge/session, then wait for a real event (plug, boot, screen).
    private var wakeAttempts = 0
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

    // The auto-standby is schedule-gated (e.g. night only). The clock hour
    // change is the only moment the answer can flip while charging continues.
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
            addAction(Intent.ACTION_TIME_CHANGED)
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
        cancelWake()
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
            removeOverlay()
            stopSelf()
            return
        }

        // 2) No power -> no StandBy screen. Stay armed (as a quiet
        //    foreground service) so that plugging the cable in later is
        //    caught by our own POWER_CONNECTED receiver reliably, without
        //    the app having to launch itself from the background.
        if (!isCharging(this)) {
            removeOverlay()
            return
        }

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
        if (!isScreenInteractive()) {
            showOverlay()
            cancelWake()
        } else if ((getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager)
                ?.isKeyguardLocked == true
        ) {
            showOverlay()
            cancelWake()
        } else {
            removeOverlay()
            cancelWake()
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

        // Tap anywhere exits to the normal screen.
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                exitStandby()
            }
            true
        }

        // Security: Add FLAG_NOT_TOUCHABLE to prevent clickjacking attacks
        // when overlay is used for display-only purposes
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.addView(view, params)
        } catch (_: Exception) {
            return
        }
        overlayView = view
        shownOrientationType = currentOrientationType()
        cancelWake() // the clock is up — the wake notification is no longer needed

        val c = StandbyController(this, view)
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
        unregisterSensors()
        // Nothing shown -> no need to poll the rotation sensor.
        orientationListener?.disable()
        val view = overlayView ?: run {
            controller?.stop()
            controller = null
            return
        }
        overlayView = null
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
    }
}
