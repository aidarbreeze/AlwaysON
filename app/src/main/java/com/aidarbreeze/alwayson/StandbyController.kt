package com.aidarbreeze.alwayson

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import com.aidarbreeze.alwayson.media.MediaWatcher
import com.aidarbreeze.alwayson.view.MonthCalendarView
import java.io.File
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
    private val mediaPrev: TextView = root.findViewById(R.id.mediaPrev)
    private val mediaNext: TextView = root.findViewById(R.id.mediaNext)
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
            if (batteryText.visibility == View.VISIBLE && tickCount % 2 == 0) {
                updateBattery() // refresh the charge current about every 2 s
            }
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
        updateBattery()
        mediaPrev.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        mediaNext.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
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
        dateText.text = dateLine(now)
    }

    /** Full date line shown above the clock, e.g. "Понедельник, 8 сентября"
     *  (weekday + day + month, no year, weekday capitalised). */
    private fun dateLine(now: Calendar): String {
        val locale = Locale.getDefault()
        val pattern = if (locale.language.equals("ru", ignoreCase = true)) {
            "EEEE, d MMMM"
        } else {
            "EEEE, MMMM d"
        }
        val raw = SimpleDateFormat(pattern, locale).format(now.timeInMillis)
        return raw.replaceFirstChar { it.titlecase(locale) }
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

    /** Battery level plus, while charging, the live charge current in mA. */
    private fun updateBattery() {
        val intent = appContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (level < 0 || scale <= 0) return
        val percent = (level * 100f / scale).toInt()

        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (!charging) {
            batteryText.text = "$percent%"
            return
        }

        val ma = readChargeMa()
        val unit = appContext.getString(R.string.charging_current_unit)
        batteryText.text = if (ma > 0) "$percent% · $ma $unit" else "$percent%"
    }

    /**
     * Charge current in mA. Tries the [BatteryManager] property first, then —
     * like AIDA64 does — reads the raw values straight from the kernel's
     * battery nodes under /sys/class/power_supply, which report current on
     * more devices (the property often returns 0 even when the node works).
     * Returns <= 0 when no source reports a value.
     */
    private fun readChargeMa(): Int {
        // 1) Android property (µA).
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val micro = try {
            val instant = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
            if (instant == 0 || instant == Int.MIN_VALUE) {
                bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ?: 0
            } else instant
        } catch (_: Exception) {
            0
        }
        if (micro > 0) return micro / 1000

        // 2) sysfs battery nodes (AIDA64 style).
        val nodes = arrayOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/battery/current_average",
            "/sys/class/power_supply/battery/current",
            "/sys/class/power_supply/main/current_now",
            "/sys/class/power_supply/usb/current_now",
            "/sys/class/power_supply/usb/current_average",
            "/sys/class/power_supply/charger/current_now",
            "/sys/class/power_supply/wireless/current_now"
        )
        for (path in nodes) {
            val raw = readSysLong(path) ?: continue
            if (raw == 0L) continue
            return normalizeSysCurrent(raw)
        }
        return -1
    }

    private fun readSysLong(path: String): Long? {
        return try {
            val f = File(path)
            if (!f.canRead()) return null
            f.readText().trim().toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** sysfs usually reports µA (thousands); some ROMs already give mA. */
    private fun normalizeSysCurrent(raw: Long): Int {
        val abs = kotlin.math.abs(raw)
        val ma = if (abs > 200_000L) abs / 1000L else abs
        return ma.toInt()
    }

    /** Sends a hardware-style media key (previous/next) to control playback. */
    private fun sendMediaKey(code: Int) {
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (_: Exception) {
            // media control not allowed / no target; ignore
        }
    }
}
