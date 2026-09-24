package com.aidarbreeze.alwayson.dream

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.service.dreams.DreamService
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import com.aidarbreeze.alwayson.CallState
import com.aidarbreeze.alwayson.R
import com.aidarbreeze.alwayson.StandbyController
import com.aidarbreeze.alwayson.ui.StandbyUiState

/**
 * Screen saver ("daydream") showing the iPhone-StandBy-style clock: big time,
 * date, month calendar and (when music plays) a compact now-playing card, all
 * on a pure black OLED-friendly background with burn-in protection.
 *
 * Android starts it automatically when the device is idle and (per the system
 * setting) charging/docked. Many OEM builds (e.g. OnePlus/OxygenOS) do not
 * auto-start third-party dreams while charging — for those, use the app's
 * optional "show while charging" overlay instead.
 *
 * The dream is INTERACTIVE: a horizontal swipe flips between the clock and
 * the messenger notifications page, a quick tap exits (finish()). It also
 * steps aside by itself while a call is up, so an incoming-call dialog is
 * never covered. The window background is pure black from the first frame,
 * so the lock screen can never flash through when the dream starts or stops.
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

    // While a call is up the dream must go away (call dialog on top); the
    // monitor polls and reports flips only.
    private val callMonitor = CallState.Monitor(this) { active ->
        if (active) {
            // Keep the re-arm below: after the call ends the overlay should
            // come back on its own — only a user tap dismisses for good.
            finish()
        }
    }

    // Which layout variant is currently shown in the dream window.
    private var shownLandscape = false
    // Deferred overlay re-arm (see onDreamingStarted). Kept as a field so
    // the teardown can cancel it: a dream stopped within the 150 ms grace
    // must not poke the overlay service on its way out.
    private val reArmOverlay = Runnable {
        com.aidarbreeze.alwayson.service.OverlayService.requestReevaluate(this)
    }
    private var dreaming = false
    // True when the user (or a call) made us finish() ourselves — the lock
    // screen must then be handed over CLEANLY (see onDreamingStopped).
    private var selfStopped = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Interactive: touches reach the content (paging gestures + tap to
        // exit) instead of instantly stopping the dream behind the user's
        // back — with a black window there is no keyguard flash either way.
        isInteractive = true
        isFullscreen = true

        // Pure black from the very first frame: the lock screen behind the
        // dream can never show through during start/stop transitions (the
        // "blink" between the keyguard and the screen saver).
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

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
        callMonitor.start()
        // Let the charging overlay step aside (no stacked blacks), but only
        // after the dream's first frames are on screen — both windows are
        // opaque black with the same clock, so the handover never shows the
        // keyguard behind them.
        window.decorView.removeCallbacks(reArmOverlay)
        window.decorView.postDelayed(reArmOverlay, 150L)
    }

    override fun onDreamingStopped() {
        dreaming = false
        StandbyUiState.dreaming = false
        // A dream stopped within the 150 ms grace must not re-arm the
        // overlay on its way out.
        window.decorView.removeCallbacks(reArmOverlay)
        callMonitor.stop()
        controller?.stop()
        super.onDreamingStopped()
        // FLICKER FIX: when the user tapped to dismiss the screen saver, the
        // keyguard must be handed over CLEANLY — re-covering it with the
        // overlay at once looked like a blink (lock screen -> clock again).
        // For every other stop (system, call) re-arm the overlay as usual.
        if (!selfStopped) {
            com.aidarbreeze.alwayson.service.OverlayService.requestReevaluate(this)
        }
        selfStopped = false
    }

    override fun onDetachedFromWindow() {
        dreaming = false
        StandbyUiState.dreaming = false
        callMonitor.stop()
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
        controller = StandbyController(this, view).apply {
            // Tap anywhere on an empty area hands the screen back to the
            // keyguard (mirrors the old "touch stops the dream" behaviour);
            // swipes flip pages (handled inside the controller).
            onTapExit = {
                selfStopped = true
                finish()
            }
        }

        shownLandscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
}
