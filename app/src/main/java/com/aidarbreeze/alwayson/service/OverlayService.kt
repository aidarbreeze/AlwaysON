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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import com.aidarbreeze.alwayson.Prefs
import com.aidarbreeze.alwayson.R
import com.aidarbreeze.alwayson.StandbyController
import com.aidarbreeze.alwayson.ui.StandbyUiState

/**
 * "StandBy while charging" service. It shows the same black StandBy screen as
 * an overlay, but ONLY while the device is charging AND in landscape, because
 * OnePlus/OxygenOS does not auto-start the built-in screen saver on charge.
 *
 * To avoid the earlier "stuck" problem this overlay never consumes touches
 * (FLAG_NOT_TOUCHABLE): all taps pass through to the screen below, and the
 * screen hides itself automatically when you unplug, rotate to portrait, or
 * press "Hide" in the notification. Nothing traps the user.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_HIDE = "com.aidarbreeze.alwayson.HIDE"
        const val ACTION_REFRESH = "com.aidarbreeze.alwayson.REFRESH"
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "alwayson_standby"

        /** Ask a running service to re-check whether the overlay should show. */
        fun requestReevaluate(context: Context) {
            try {
                val i = Intent(context, OverlayService::class.java)
                i.action = ACTION_REFRESH
                context.startService(i)
            } catch (_: Exception) {
                // service not available; ignore
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

        fun isLandscape(context: Context): Boolean =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private var overlayView: View? = null
    private var controller: StandbyController? = null
    private var orientationListener: OrientationEventListener? = null

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_DISCONNECTED -> stopSelf()
                Intent.ACTION_POWER_CONNECTED -> evaluateAndSync()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
        )
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                evaluateAndSync()
            }
        }
        orientationListener?.enable()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            Prefs.setAutoStandby(this, false) // keep the in-app switch consistent
            stopSelf()
            return START_NOT_STICKY
        }
        evaluateAndSync()
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        try {
            unregisterReceiver(powerReceiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
        orientationListener?.disable()
        orientationListener = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    private fun evaluateAndSync() {
        // Never stack the overlay on top of the full-screen StandbyActivity.
        val show = !StandbyUiState.previewVisible &&
            isCharging(this) && isLandscape(this) && Settings.canDrawOverlays(this)
        if (show) showOverlay() else removeOverlay()
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.standby_view, null)

        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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
        val c = StandbyController(this, view)
        c.start()
        controller = c
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        overlayView = null
        controller?.stop()
        controller = null
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (_: Exception) {
            // view already detached
        }
    }
}
