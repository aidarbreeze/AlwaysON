package com.aidarbreeze.alwayson

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.aidarbreeze.alwayson.media.MediaWatcher
import com.aidarbreeze.alwayson.view.MonthCalendarView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Drives the iPhone-StandBy-style screen shared by the screen-saver service,
 * the auto "while charging" overlay and the preview activity:
 *  - big clock + date (always)
 *  - month calendar (always, refreshes daily)
 *  - battery (option)
 *  - compact now-playing card, shown only while music actually plays
 *  - OLED burn-in protection: the whole content block slowly drifts by a few
 *    pixels on a timer so no pixels stay lit in one place for long.
 */
class StandbyController(context: Context, root: View) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val clockText: TextView = root.findViewById(R.id.clockText)
    private val dateText: TextView = root.findViewById(R.id.dateText)
    private val batteryText: TextView = root.findViewById(R.id.batteryText)
    private val mediaGroup: View = root.findViewById(R.id.mediaGroup)
    private val mediaGlyph: TextView = root.findViewById(R.id.mediaGlyph)
    private val mediaText: TextView = root.findViewById(R.id.mediaText)
    private val monthView: MonthCalendarView = root.findViewById(R.id.monthView)
    private val content: View = root.findViewById(R.id.standbyContent)

    private val mediaWatcher = MediaWatcher(appContext)

    private var lastDay = -1
    private var tickCount = 0
    private var lastMedia: String? = null
    private var driftStep = 0

    // Largest clock size (px) before any shrink, captured from the layout.
    private var clockBasePx = 0f

    // Deterministic small offsets used for OLED burn-in drift (px).
    private val driftX = intArrayOf(0, 3, -2, 5, -5, 2, -3, 0)
    private val driftY = intArrayOf(0, 2, 4, 1, -3, -4, 3, 0)

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            tickCount++
            updateMedia() // poll every second so playback changes feel instant
            handler.postDelayed(this, 1000)
        }
    }

    private val drift = object : Runnable {
        override fun run() {
            driftStep = (driftStep + 1) % driftX.size
            content.translationX = driftX[driftStep].toFloat()
            content.translationY = driftY[driftStep].toFloat()
            handler.postDelayed(this, 60_000) // shift content every minute
        }
    }

    /** Apply display options (battery visibility, brightness) to the views. */
    fun applyOptions() {
        batteryText.visibility =
            if (Prefs.showBattery(appContext)) View.VISIBLE else View.GONE
        // Dim the clock content only; the root background stays pure black.
        val a = Prefs.brightness(appContext) / 100f
        content.alpha = a.coerceIn(0.1f, 1f)
    }

    fun start() {
        applyOptions()
        updateClock()
        updateMedia()
        lastDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        monthView.invalidate()
        handler.post(tick)
        handler.post(drift)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateClock() {
        val now = Calendar.getInstance()
        if (now.get(Calendar.DAY_OF_MONTH) != lastDay) {
            lastDay = now.get(Calendar.DAY_OF_MONTH)
            monthView.invalidate()
        }
        val millis = now.timeInMillis
        val use24 = if (Prefs.force24h(appContext)) true
        else android.text.format.DateFormat.is24HourFormat(appContext)
        val secs = Prefs.showSeconds(appContext)
        val pattern = when {
            use24 && secs -> "HH:mm:ss"
            use24 -> "HH:mm"
            secs -> "h:mm:ss"
            else -> "h:mm"
        }
        clockText.text = SimpleDateFormat(pattern, Locale.getDefault()).format(millis)
        // Runs after layout each second, so the digits always fit on one line
        // (needed e.g. when seconds "23:45:33" are shown) and never wrap.
        clockText.post { fitClock() }
        dateText.text = DateFormat.getDateInstance(DateFormat.LONG).format(millis)
        if (batteryText.visibility == View.VISIBLE) {
            batteryText.text = batterySummary()
        }
    }

    /**
     * Keeps the clock digits on a single line: shrinks the font just enough so
     * the rendered text fits the width available inside its column. Never
     * grows beyond the size the layout gave it ([clockBasePx]).
     */
    private fun fitClock() {
        val tv = clockText
        val text = tv.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val parent = tv.parent as? View ?: return
        // Room to breathe inside the column (a few px on each side).
        val avail = (parent.width - dp(16f)).toFloat()
        if (avail <= 0f) return // not laid out yet; a later tick will retry
        if (clockBasePx <= 0f) clockBasePx = tv.textSize

        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tv.typeface
            textSize = clockBasePx
        }
        val textWidth = paint.measureText(text)
        val target = if (textWidth <= avail) clockBasePx
        else clockBasePx * (avail / textWidth)

        if (kotlin.math.abs(tv.textSize - target) > 0.5f) {
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, target)
        }
    }

    private fun dp(value: Float): Int =
        (appContext.resources.displayMetrics.density * value).toInt()

    private fun updateMedia() {
        val now = mediaWatcher.current()
        if (now == null) {
            if (mediaGroup.visibility == View.VISIBLE) {
                mediaGroup.visibility = View.GONE
                lastMedia = null
            }
            return
        }
        val line = if (now.artist.isBlank()) now.title else "${now.artist} — ${now.title}"
        if (line != lastMedia) {
            lastMedia = line
            mediaText.text = line
        }
        mediaGlyph.text = if (now.playing) "\u25B6" else "\u23F8"
        if (mediaGroup.visibility != View.VISIBLE) {
            mediaGroup.visibility = View.VISIBLE
        }
    }

    private fun batterySummary(): String {
        val intent = appContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return ""
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (level < 0 || scale <= 0) return ""
        val percent = (level * 100f / scale).toInt()
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return if (charging) "$percent% (charging)" else "$percent%"
    }
}
