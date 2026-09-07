package com.aidarbreeze.alwayson

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import com.aidarbreeze.alwayson.media.MediaWatcher
import com.aidarbreeze.alwayson.stock.Candle
import com.aidarbreeze.alwayson.stock.StockApi
import com.aidarbreeze.alwayson.view.ClockView
import com.aidarbreeze.alwayson.view.MonthCalendarView
import com.aidarbreeze.alwayson.view.StockChartView
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

    private val clockView: ClockView = root.findViewById(R.id.clockText)
    private val dateText: TextView = root.findViewById(R.id.dateText)
    private val batteryText: TextView = root.findViewById(R.id.batteryText)
    private val mediaGroup: View = root.findViewById(R.id.mediaGroup)
    private val mediaGlyph: TextView = root.findViewById(R.id.mediaGlyph)
    private val mediaText: TextView = root.findViewById(R.id.mediaText)
    private val mediaPrev: TextView = root.findViewById(R.id.mediaPrev)
    private val mediaNext: TextView = root.findViewById(R.id.mediaNext)
    private val monthView: MonthCalendarView = root.findViewById(R.id.monthView)
    private val stockView: StockChartView = root.findViewById(R.id.stockView)
    private val content: View = root.findViewById(R.id.standbyContent)

    private val mediaWatcher = MediaWatcher(appContext)

    private var lastDay = -1
    private var tickCount = 0
    private var lastMedia: String? = null
    private var driftStep = 0

    // --- calendar <-> stock chart alternation ---
    // null = show the calendar; an Int = MOEX ISS candle interval (code 1/10/60
    // minutes). Sequence: calendar, 60М, calendar, 10М, calendar, 1М, repeat.
    private val panelSteps = arrayOf<Int?>(null, 60, null, 10, null, 1)
    private var panelStep = 0
    private val stockCached = HashMap<Int, List<Candle>>()
    private var fetchGen = 0L
    private val panelTickMs = 10_000L
    private val panelRunnable = object : Runnable {
        override fun run() {
            panelStep = (panelStep + 1) % panelSteps.size
            renderPanel()
            handler.postDelayed(this, panelTickMs)
        }
    }

    /** Short label for an ISS interval code, shown at the bottom of the chart. */
    private fun intervalLabel(code: Int): String = when (code) {
        1 -> "1М"
        10 -> "10М"
        60 -> "60М"
        else -> "${code}М"
    }

    // --- auto brightness (ambient light sensor) ---
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var lightListener: SensorEventListener? = null
    private var autoBrightness = false
    // Smoothed current alpha so the light sensor does not cause flicker.
    private var currentAlpha = -1f
    private var manualAlpha = 1f
    private val sensorHandler = Handler(Looper.getMainLooper())
    private val minAlpha = 0.22f // lowest: dim but clearly visible in the dark

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
        // Clock style / thickness may have changed in settings -> redraw.
        clockView.refresh()
        // Dim the clock content only; the root background stays pure black.
        manualAlpha = (Prefs.brightness(appContext) / 100f).coerceIn(0f, 1f)
        autoBrightness = Prefs.autoBrightness(appContext)
        if (!autoBrightness) {
            // Fixed manual level (kept at least slightly visible).
            content.alpha = manualAlpha.coerceIn(minAlpha, 1f)
            stopLightSensor()
        } else {
            startLightSensor()
        }
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
        startPanelCycle()
    }

    fun stop() {
        stopLightSensor()
        handler.removeCallbacksAndMessages(null)
        showCalendarOnly()
    }

    // ---------- calendar <-> stock chart alternation ----------

    private fun panelEnabled(): Boolean =
        Prefs.stocksEnabled(appContext) &&
            Prefs.stockTicker(appContext).isNotEmpty()

    private fun startPanelCycle() {
        handler.removeCallbacks(panelRunnable)
        if (!panelEnabled()) {
            showCalendarOnly()
            return
        }
        panelStep = 0
        renderPanel()
        handler.postDelayed(panelRunnable, panelTickMs)
    }

    private fun renderPanel() {
        val code = panelSteps[panelStep]
        if (code == null) {
            showCalendarOnly()
        } else {
            showChart(code)
        }
    }

    private fun showCalendarOnly() {
        monthView.visibility = View.VISIBLE
        stockView.visibility = View.GONE
    }

    private fun showChart(code: Int) {
        monthView.visibility = View.GONE
        stockView.visibility = View.VISIBLE

        val symbol = Prefs.stockTicker(appContext)
        val ref = Prefs.stockReference(appContext)
        val label = intervalLabel(code)
        if (symbol.isEmpty()) {
            stockView.setStatus("—")
            return
        }
        val cached = stockCached[code]
        if (cached != null && cached.size >= 2) {
            stockView.setData(symbol, ref, label, cached)
        } else {
            stockView.setStatus("Загрузка…")
        }
        fetchStock(symbol, code, ref, label)
    }

    /** Fetch candles for an interval on a background thread; drop stale results. */
    private fun fetchStock(symbol: String, code: Int, ref: Double, label: String) {
        val gen = ++fetchGen
        Thread {
            val data = StockApi.fetchCandles(symbol, code)
            handler.post {
                if (gen != fetchGen) return@post
                if (data != null && data.size >= 2) {
                    stockCached[code] = data
                    if (stockView.visibility == View.VISIBLE) {
                        stockView.setData(symbol, ref, label, data)
                    }
                } else if (stockView.visibility == View.VISIBLE) {
                    stockView.setStatus("нет данных / нет сети")
                }
            }
        }.start()
    }

    // ---------- auto brightness (ambient light) ----------

    /**
     * Registers the ambient light sensor. On every reading we map lux to an
     * alpha target (dark room -> barely visible, bright/sun -> full manual
     * brightness) and ease the actual alpha toward it to avoid flicker.
     */
    private fun startLightSensor() {
        if (lightListener != null) return // already listening
        val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return
        sensorManager = sm
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        lightSensor = sensor
        currentAlpha = if (currentAlpha < 0f) minAlpha else currentAlpha
        content.alpha = currentAlpha
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_LIGHT) return
                val lux = event.values[0]
                // Target between min (dark) and manualAlpha (bright).
                val target = targetAlpha(lux)
                // Ease toward it on the main thread (a few % per reading).
                sensorHandler.post {
                    currentAlpha = if (currentAlpha < 0f) target
                    else currentAlpha + (target - currentAlpha) * 0.25f
                    content.alpha = currentAlpha
                }
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        lightListener = listener
        // Deliver on the main thread so we can touch the view directly.
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI, sensorHandler)
    }

    private fun stopLightSensor() {
        val listener = lightListener ?: return
        lightListener = null
        try {
            sensorManager?.unregisterListener(listener, lightSensor)
        } catch (_: Exception) {
            // already unregistered
        }
        lightSensor = null
        sensorManager = null
    }

    /** Maps lux to a target content alpha. Logarithmic so both the very dark
     *  and the bright end get usable range. */
    private fun targetAlpha(lux: Float): Float {
        if (lux <= 0f) return minAlpha
        // log scale ~1..20000 lux -> 0..1, saturating on both ends.
        val t = (kotlin.math.log10(lux + 1f) - kotlin.math.log10(2f)) /
            (kotlin.math.log10(20001f) - kotlin.math.log10(2f))
        val f = t.coerceIn(0f, 1f)
        val top = manualAlpha.coerceAtLeast(minAlpha).coerceAtMost(1f)
        return minAlpha + (top - minAlpha) * f
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
        // The clock view self-fits to its column for every style.
        clockView.setTime(SimpleDateFormat(pattern, Locale.getDefault()).format(millis))
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

    /**
     * Battery level plus the live raw value read straight from
     * BATTERY_PROPERTY_CURRENT_NOW (shown as-is, no unit scaling, so a number
     * like 1847 or -215 is displayed). The raw value is negative while
     * charging on this ROM, so we invert the sign for display: a minus reading
     * is shown as "+", a plus reading as "-" (per the user's convention).
     */
    private fun updateBattery() {
        val intent = appContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return
        val percent = (level * 100f / scale).toInt()

        // Raw signed value (negative while charging on this device).
        val raw = BatteryInfo.readCurrentNowRaw(appContext)
        if (raw == null) {
            batteryText.text = "$percent%"
            return
        }

        // Invert the sign so charging shows as a positive number with "+".
        val display = -raw
        val withSign = if (display > 0) "+$display" else "$display"
        batteryText.text = "$percent% · $withSign"
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
