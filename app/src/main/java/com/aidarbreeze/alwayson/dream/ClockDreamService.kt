package com.aidarbreeze.alwayson.dream

import android.content.res.Configuration
import android.service.dreams.DreamService
import android.view.LayoutInflater
import android.view.OrientationEventListener
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
 *
 * Unlike an Activity, a dream is a Service: its content view is not recreated
 * automatically when the device rotates, so a portrait layout would otherwise
 * be stretched into a landscape window (and vice versa). We therefore watch
 * the orientation and re-inflate R.layout.standby_view, which resolves to the
 * matching portrait / landscape layout, whenever it changes.
 */
class ClockDreamService : DreamService() {

    private var controller: StandbyController? = null
    private var orientationListener: OrientationEventListener? = null

    // Which layout variant is currently shown in the dream window.
    private var shownLandscape = false
    private var dreaming = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                // Device turned while the dream is up -> switch layout variant.
                relayoutIfNeeded()
            }
        }
        orientationListener?.enable()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Belt & braces: the framework may deliver the rotation this way too.
        relayoutIfNeeded()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dreaming = true
        showLayout()
        controller?.start()
    }

    override fun onDreamingStopped() {
        dreaming = false
        controller?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        dreaming = false
        controller?.stop()
        controller = null
        orientationListener?.disable()
        orientationListener = null
        super.onDetachedFromWindow()
    }

    /** Picks the layout that matches the current orientation and (re)shows it. */
    private fun relayoutIfNeeded() {
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape == shownLandscape) return
        showLayout()
        if (dreaming) controller?.start()
    }

    /** Inflate R.layout.standby_view (auto-resolves to portrait/landscape for
     *  the current orientation) and bind it to the dream window. */
    private fun showLayout() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.standby_view, null)

        controller?.stop()
        controller = null
        setContentView(view)
        controller = StandbyController(this, view)

        shownLandscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
}
