package com.aidarbreeze.alwayson.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import com.aidarbreeze.alwayson.StandbyUiState

/**
 * Performs the user-specified choreography that flips their own
 * bypass-charging QS tile (ColorOS control center) WITHOUT root:
 *
 *   1. pull the control center down from the RIGHT side:
 *      (1000,10) -> (1000,300) over 250 ms,
 *   2. let the panel settle (350 ms, so the tap never lands mid-animation),
 *   3. tap the bypass tile at (600,1700),
 *   4. wait 500 ms, collapse the shade (global BACK).
 *
 * If the system daydream (our screensaver) is up, a first tap exits it —
 * the injected gestures must land on the system UI, not on our windows.
 * OverlayService stops the WakeActivity host and latches re-hosting for
 * the duration (makeWayForBypass), then restores the clock (clearBypassWay).
 *
 * Requires a one-time enable in the accessibility settings
 * (canPerformGestures); no other permissions are involved.
 */
class BypassAutomationService : AccessibilityService() {

    companion object {
        // Choreography coordinates (user device, portrait 1080x2354).
        private const val SWIPE_X = 1000f
        private const val SWIPE_Y0 = 10f
        private const val SWIPE_Y1 = 300f
        private const val SWIPE_MS = 250L
        private const val TILE_X = 600f
        private const val TILE_Y = 1700f
        private const val PANEL_SETTLE_MS = 350L  // control-center animation
        private const val AFTER_TAP_MS = 500L     // user-specified
        private const val REHOST_DELAY_MS = 1200L // let the shade finish closing

        @Volatile
        private var instance: BypassAutomationService? = null

        /** True while the gesture choreography is in progress. */
        @Volatile
        var running = false
            private set

        /** True once the user enabled this service in the accessibility
         *  settings (onServiceConnected ran). */
        fun isReady(): Boolean = instance != null

        /** Runs the full sequence; false when the service is not enabled
         *  (yet) or the screen is dark. */
        fun runBypassSequence(): Boolean {
            val svc = instance ?: return false
            if (running) return true // already in progress: do not double-tap
            val pm = svc.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isInteractive != true) return false
            running = true
            svc.runSequence()
            return true
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        running = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun runSequence() {
        val dreaming = StandbyUiState.dreaming
        // 0) our screensaver is up: a tap exits it (curtain + finish) — the
        //    gestures below must land on the system control center.
        if (dreaming) schedule({ tap(540f, 1177f) }, 0L)
        // 1) the WakeActivity host, if any, was already stopped by
        //    OverlayService.makeWayForBypass right before this call.
        val swipeAt = if (dreaming) 800L else 0L
        // 2) pull the control center from the right side of the status bar.
        schedule({ swipeDown() }, swipeAt)
        // 3) settle, then tap the bypass tile.
        val tileAt = swipeAt + SWIPE_MS + PANEL_SETTLE_MS
        schedule({ tap(TILE_X, TILE_Y) }, tileAt)
        // 4) 500 ms later collapse the shade.
        schedule({ performGlobalAction(GLOBAL_ACTION_BACK) }, tileAt + AFTER_TAP_MS)
        // 5) hand the screen back to the standby hosts (clock returns).
        schedule({
            OverlayService.clearBypassWay(applicationContext)
            running = false
        }, tileAt + AFTER_TAP_MS + REHOST_DELAY_MS)
    }

    private fun schedule(step: () -> Unit, delayMs: Long) {
        if (delayMs <= 0L) step() else mainHandler.postDelayed(step, delayMs)
    }

    private fun swipeDown() {
        val path = Path().apply {
            moveTo(SWIPE_X, SWIPE_Y0)
            lineTo(SWIPE_X, SWIPE_Y1)
        }
        dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_MS))
                .build()
        )
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y + 0.5f) // a 0.5 px stroke = a tap at (x, y)
        }
        dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
                .build()
        )
    }

    private fun dispatch(gesture: GestureDescription) {
        try {
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            // A mid-run disable or a display-size change: the sequence
            // aborts; OverlayService still restores the hosts via the
            // scheduled clearBypassWay.
        }
    }
}
