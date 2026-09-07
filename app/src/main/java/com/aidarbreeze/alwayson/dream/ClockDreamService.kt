package com.aidarbreeze.alwayson.dream

import android.service.dreams.DreamService
import android.view.LayoutInflater
import com.aidarbreeze.alwayson.R
import com.aidarbreeze.alwayson.StandbyController

/**
 * Screen saver ("daydream") showing the iPhone-StandBy-style clock: big time,
 * date, month calendar and (when music plays) a compact now-playing card, all
 * on a pure black OLED-friendly background with burn-in protection.
 *
 * Android starts it automatically when the device is idle and (per the system
 * setting) charging/docked, and stops it the moment the user touches the
 * screen or moves the device. Many OEM builds (e.g. OnePlus/OxygenOS) do not
 * auto-start third-party dreams while charging — for those, use the app's
 * optional "show while charging" overlay instead.
 */
class ClockDreamService : DreamService() {

    private var controller: StandbyController? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.standby_view, null)
        setContentView(view)

        controller = StandbyController(this, view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        controller?.start()
    }

    override fun onDreamingStopped() {
        controller?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        controller?.stop()
        controller = null
        super.onDetachedFromWindow()
    }
}
