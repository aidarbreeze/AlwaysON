package com.aidarbreeze.alwayson.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.aidarbreeze.alwayson.AodViews
import com.aidarbreeze.alwayson.NotificationHelper
import com.aidarbreeze.alwayson.Prefs
import com.aidarbreeze.alwayson.R

/**
 * Draws the AlwaysON clock as a system overlay (the "on top of the current
 * screen" night-stand mode). Requires the "display over other apps"
 * permission which the settings screen requests.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_OPTIONS_CHANGED = "com.aidarbreeze.alwayson.OPTIONS_CHANGED"

        fun sendOptionsChanged(context: Context) {
            val i = Intent(context, OverlayService::class.java)
            i.action = ACTION_OPTIONS_CHANGED
            context.startService(i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var aodViews: AodViews? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenOn = true

    private val tick = object : Runnable {
        override fun run() {
            if (screenOn) aodViews?.updateNow()
            handler.postDelayed(this, 1000)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    addOverlay()
                    aodViews?.updateNow()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    removeOverlay()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "AlwaysON:overlay"
        ).apply {
            setReferenceCounted(false)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }

        startForegroundInternal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OPTIONS_CHANGED) {
            applyOptionsToViews()
            // Keep the restart policy sticky regardless of which intent started us.
            return START_STICKY
        }
        // Normal (re)start: make sure everything is up.
        addOverlay()
        aodViews?.updateNow()
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // already gone
        }
        handler.removeCallbacksAndMessages(null)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    private fun startForegroundInternal() {
        val notif = NotificationHelper.build(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, notif)
        }
    }

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlayView != null) return

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.aod_view, null)
        // Let the user dim the whole layer from the settings brightness slider.
        view.alpha = Prefs.overlayAlpha(this)

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        // Note: gravity/insets are fine for a simple MATCH_PARENT black layer.

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            return
        }

        overlayView = view
        val av = AodViews(this, view)
        av.applyOptions()
        aodViews = av

        if (wakeLock?.isHeld == false) wakeLock?.acquire()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
            // view may already be detached
        }
        overlayView = null
        aodViews = null
        handler.removeCallbacks(tick)
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun applyOptionsToViews() {
        val view = overlayView
        if (view != null) view.alpha = Prefs.overlayAlpha(this)
        aodViews?.applyOptions()
        aodViews?.updateNow()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
