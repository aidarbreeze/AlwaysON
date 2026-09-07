package com.aidarbreeze.alwayson.dream

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.LayoutInflater
import android.view.View
import com.aidarbreeze.alwayson.AodViews
import com.aidarbreeze.alwayson.Prefs
import com.aidarbreeze.alwayson.R

/**
 * Screen saver ("daydream") that shows an always-on style clock on a pure
 * black background.
 *
 * Android starts it automatically when the device is idle and (per the system
 * setting) charging/docked, and stops it the moment the user touches the
 * screen or moves the device — so there is never a "stuck in the mode"
 * situation.
 *
 * Enable it: Android Settings > Display > Screen saver, pick "AlwaysON clock"
 * and set "Start when: charging / docked".
 */
class ClockDreamService : DreamService() {

    private val handler = Handler(Looper.getMainLooper())
    private var views: AodViews? = null
    private var contentView: View? = null

    private val tick = object : Runnable {
        override fun run() {
            views?.updateNow()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.aod_view, null)

        contentView = view.findViewById(R.id.aodContent)
        // Dim only the clock content, keep the background pure black so OLED
        // pixels are truly off outside the digits.
        contentView?.alpha = Prefs.brightness(this) / 100f

        setContentView(view)
        views = AodViews(this, view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        views?.applyOptions()
        views?.updateNow()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onDreamingStopped() {
        handler.removeCallbacks(tick)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        contentView = null
        views = null
        super.onDetachedFromWindow()
    }
}
