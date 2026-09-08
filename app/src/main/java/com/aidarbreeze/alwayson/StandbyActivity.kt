package com.aidarbreeze.alwayson

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.aidarbreeze.alwayson.service.OverlayService
import com.aidarbreeze.alwayson.ui.StandbyUiState

/**
 * Full-screen preview of the StandBy screen (same layout & logic as the
 * services), following the device orientation (portrait and landscape both
 * supported). Used to show the design instantly and to keep it open as a
 * manual desk clock. Pressing Back exits — it never locks the user out.
 */
class StandbyActivity : Activity() {

    private var controller: StandbyController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        setContentView(R.layout.standby_view)
        controller = StandbyController(this, findViewById(R.id.standbyRoot))
        controller?.start()

        // Long-press toggles pinning the current window (pauses the rotation);
        // a second long-press resumes it from the same window.
        findViewById<View>(R.id.standbyRoot).setOnLongClickListener {
            val isPinned = controller?.togglePinned() ?: false
            Toast.makeText(
                this,
                if (isPinned) "Окно закреплено — ещё раз, чтобы вернуться к смене"
                else "Смена окон включена",
                Toast.LENGTH_SHORT
            ).show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        StandbyUiState.previewVisible = true
        // Ask the auto-overlay service to back off while this screen is shown,
        // so we never stack two StandBy windows.
        OverlayService.requestReevaluate(this)
    }

    override fun onPause() {
        super.onPause()
        StandbyUiState.previewVisible = false
    }

    override fun onDestroy() {
        StandbyUiState.previewVisible = false
        controller?.stop()
        controller = null
        // The preview is gone now, so let the auto-overlay re-sync (it may show
        // again if the phone is charging and the feature is on).
        OverlayService.requestReevaluate(this)
        super.onDestroy()
    }

    private fun hideSystemUi() {
        val decor = window.decorView
        decor.systemUiVisibility = decor.systemUiVisibility or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = window.attributes
    }
}
