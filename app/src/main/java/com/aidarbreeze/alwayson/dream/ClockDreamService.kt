package com.aidarbreeze.alwayson.dream

import android.content.res.Configuration
import android.os.Build
import android.service.dreams.DreamService
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import com.aidarbreeze.alwayson.R
import com.aidarbreeze.alwayson.StandbyController
import com.aidarbreeze.alwayson.ui.StandbyUiState

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

        // Hide the status & navigation bars for a clean, immersive full-screen
        // clock. isFullscreen alone is not honoured by every ROM (OxygenOS
        // keeps showing the status bar), so apply the window flags too.
        hideSystemUi()

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
        StandbyUiState.dreaming = true
        showLayout()
        controller?.start()
        // Let the charging overlay step aside immediately (no stacked blacks).
        com.aidarbreeze.alwayson.service.OverlayService.requestReevaluate(this)
    }

    override fun onDreamingStopped() {
        dreaming = false
        StandbyUiState.dreaming = false
        controller?.stop()
        super.onDreamingStopped()
        // The dream gave the screen back — re-arm the overlay if it may show.
        com.aidarbreeze.alwayson.service.OverlayService.requestReevaluate(this)
    }

    override fun onDetachedFromWindow() {
        dreaming = false
        StandbyUiState.dreaming = false
        controller?.stop()
        controller = null
        orientationListener?.disable()
        orientationListener = null
        super.onDetachedFromWindow()
    }

    private fun hideSystemUi() {
        try {
            val decor = window.decorView
            decor.systemUiVisibility = decor.systemUiVisibility or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            window.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        } catch (_: Exception) {
            // best effort only
        }
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
