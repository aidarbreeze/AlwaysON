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
 * "StandBy while charging". Shows a dim, orientation-aware clock overlay while
 * the device is charging and resting. Two goals:
 *
 *  1. The screen stays on (dimmed) for the whole charge session, like a desk
 *     clock / iPhone StandBy on a stand.
 *  2. It reliably gives the phone back (removes the overlay, revealing the
 *     normal lock/home screen) the moment the user interacts:
 *       - tapping the screen anywhere,
 *       - pressing power / waking the screen,
 *       - or picking the phone up (detected with the accelerometer).
 *
 * It only shows again on the next charging session (or when re-enabled).
 */
class OverlayService : Service(), SensorEventListener {

    companion object {
        const val ACTION_HIDE = "com.aidarbreeze.alwayson.HIDE"
        const val ACTION_REFRESH = "com.aidarbreeze.alwayson.REFRESH"
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "alwayson_standby"

        fun requestReevaluate(context: Context) {
            try {
                val i = Intent(context, OverlayService::class.java)
                i.action = ACTION_REFRESH
                context.startService(i)
            } catch (_: Exception) {
                // service unavailable; ignore
            }
        }

        fun isCharging(context: Context): Boolean {
            val intent = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return false
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var controller: StandbyController? = null
    private var orientationListener: OrientationEventListener? = null

    // Once the user takes/wakes the phone we stop showing until the next
    // charge session starts, so we never nag over a phone that is in use.
    private var suppressed = false
    private var shownOrientationType = -1

    // Tracked so we only auto-show the clock when the phone is resting (locked
    // or screen off), never over an app the user is actively using. Seeded
    // from the real state below — a stale "true" would hide the clock when
    // the service is started while the screen is already off (stand).
    private var screenOn = true

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
                    // A fresh charge session lifts the "user took the phone"
                    // suppression (persisted, so it survives process death).
                    suppressed = false
                    Prefs.setStandbySuppressed(this@OverlayService, false)
                    evaluateAndSync()
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
                        // The desk clock was showing (screen held on by us), so
                        // the user pressed Power to leave it -> hand the phone
                        // back and stay quiet for this charge session.
                        exitStandby()
                    } else {
                        // Screen went to sleep from normal use. If we're still
                        // charging, the phone is now resting -> become a clock.
                        evaluateAndSync()
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // e.g. plugging the cable / waking. If the phone is resting
                    // (still locked) and charging, this shows the clock.
                    evaluateAndSync()
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
        // Remembered across process death: a dismissed overlay must not come
        // back on its own during the same charge session.
        suppressed = Prefs.standbySuppressed(this)

        // Seed the screen state from the device, not from a guess: the service
        // is often started by a POWER_CONNECTED broadcast while the phone is
        // resting on a stand with the screen off.
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        screenOn = pm?.isInteractive ?: true

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
                suppressed = true
                Prefs.setStandbySuppressed(this, true)
                removeOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Fresh start (app enabled / charging connected / boot) resets
                // the "user took the phone" suppression; ACTION_REFRESH
                // (preview opened/closed) must not.
                if (intent?.action != ACTION_REFRESH) {
                    suppressed = false
                    Prefs.setStandbySuppressed(this, false)
                }
                evaluateAndSync()
                // A disabled feature must not keep (or revive) us: the system
                // would otherwise restart a sticky service we just stopped.
                return if (Prefs.autoStandby(this)) START_STICKY else START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
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

    private fun evaluateAndSync() {
        if (!Prefs.autoStandby(this)) {
            // The feature is disabled. This service can still be started as a
            // side effect (e.g. the full-screen preview asks us to re-evaluate
            // on open/close) — never leave a foreground service running with
            // its persistent notification in that case.
            removeOverlay()
            stopSelf()
            return
        }
        if (!isCharging(this)) {
            // Not charging -> no StandBy screen. Stay armed (as a quiet
            // foreground service) so that plugging the cable in later is
            // caught by our own POWER_CONNECTED receiver reliably, without the
            // app having to launch itself from the background. When the user
            // turns the feature off, MainActivity stops this service.
            removeOverlay()
            return
        }

        val allowed = Prefs.autoStandby(this) &&
            !suppressed &&
            Prefs.isStandbyTimeAllowed(this, java.util.Calendar.getInstance()) &&
            !StandbyUiState.previewVisible &&
            Settings.canDrawOverlays(this)

        if (!allowed) {
            removeOverlay()
            return
        }

        // Only auto-show while the phone is resting (locked or screen off), so
        // we never cover an app the user is actively using while it charges.
        if (atRest()) showOverlay()
    }

    /** True when the phone is idle enough for a desk clock: screen off, or on
     *  but behind the keyguard. Never true while the user is using an app. */
    private fun atRest(): Boolean {
        if (!screenOn) return true
        val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return km?.isKeyguardLocked == true
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

    /** User took/woke the phone: hide now and stay quiet this charge session. */
    private fun exitStandby() {
        removeOverlay()
        suppressed = true
        Prefs.setStandbySuppressed(this, true)
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
