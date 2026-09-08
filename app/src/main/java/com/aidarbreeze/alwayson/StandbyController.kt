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
import com.aidarbreeze.alwayson.view.WeatherPanelView
import com.aidarbreeze.alwayson.weather.WeatherApi
import com.aidarbreeze.alwayson.weather.WeatherInfo
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
    private val weatherView: WeatherPanelView = root.findViewById(R.id.weatherView)
    private val content: View = root.findViewById(R.id.standbyContent)

    private val mediaWatcher = MediaWatcher(appContext)

    private var lastDay = -1
    private var tickCount = 0
    private var lastMedia: String? = null
    // Minutes elapsed, used to compute the burn-in drift offset.
    private var driftTick = 0L

    // --- panel alternation: calendar <-> stock chart <-> weather ---
    // A fixed set of "windows" is rotated on a timer. Windows can be the month
    // calendar, a MOEX chart of a chosen interval, or the weather forecast in
    // its hourly or daily view. Weather is only included once a forecast point
    // (geolocation or a manually entered city) is available.
    private enum class Kind { CAL, STOCK, HOUR, DAY }
    private class Panel(val kind: Kind, val interval: Int = 0)

    private val stockCached = HashMap<Int, List<Candle>>()
    private var weatherCached: WeatherInfo? = null
    private var weatherFetchedAt = 0L
    private var weatherFetching = false
    private var weatherMode = WeatherPanelView.MODE_DAILY
    private var panelSeq: List<Panel> = emptyList()
    private var panelStep = 0
    private var fetchGen = 0L
    private var weatherGen = 0L
    private val panelTickMs = 10_000L
    private val weatherFreshMs = 55L * 60L * 1000L // refetch the forecast hourly
    private val panelRunnable = object : Runnable {
        override fun run() {
            panelStep = (panelStep + 1) % panelSeq.size
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

    /**
     * OLED burn-in protection: each minute the whole content block is nudged to
     * a new offset by a slowly "wandering" motion, so no lit pixel stays in the
     * same place for long. Two incommensurate sine terms give a wandering path
     * that keeps moving and only rarely lands back on the exact same spot —
     * unlike a fixed loop that would return to the base position every cycle.
     */
    private val drift = object : Runnable {
        override fun run() {
            driftTick++
            val t = driftTick.toDouble()
            // ~7px wander in X, ~5px in Y, plus a small secondary term so the
            // path is a drifting figure-eight rather than a simple back-and-forth.
            val x = (7.0 * Math.sin(t * 0.71) + 3.0 * Math.sin(t * 0.17)).toInt().toFloat()
            val y = (5.0 * Math.sin(t * 0.47) + 3.0 * Math.cos(t * 0.23)).toInt().toFloat()
            content.translationX = x
            content.translationY = y
            handler.postDelayed(this, 60_000) // shift content every minute
        }
    }

    /** Apply display options (battery visibility, brightness) to the views. */
    fun applyOptions() {
        batteryText.visibility =
            if (Prefs.showBattery(appContext)) View.VISIBLE else View.GONE
        // Clock style / thickness may have changed in settings -> redraw.
        clockView.refresh()
        // Calendar week start may have changed.
        monthView.setFirstDayOfWeek(Prefs.weekStart(appContext))
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

    private fun weatherReady(): Boolean =
        Prefs.weatherEnabled(appContext) && Prefs.hasWeatherLocation(appContext)

    private fun startPanelCycle() {
        handler.removeCallbacks(panelRunnable)
        panelSeq = buildPanels()
        if (weatherReady()) fetchWeatherIfStale() // warm the cache in the background
        if (panelSeq.size <= 1) {
            panelStep = 0
            showCalendarOnly()
            return
        }
        panelStep = 0
        renderPanel()
        handler.postDelayed(panelRunnable, panelTickMs)
    }

    /** The rotating set of windows for the currently enabled modes. */
    private fun buildPanels(): List<Panel> {
        val stocks = panelEnabled()
        val weather = weatherReady()
        if (!stocks && !weather) return listOf(Panel(Kind.CAL))
        if (!weather) {
            // Stocks only — keep the original behaviour where the calendar is
            // interleaved between the (auto) chart intervals.
            val chosen = Prefs.stockPeriod(appContext)
            return if (chosen == 1 || chosen == 10 || chosen == 60) {
                listOf(Panel(Kind.CAL), Panel(Kind.STOCK, chosen))
            } else {
                listOf(
                    Panel(Kind.CAL), Panel(Kind.STOCK, 60),
                    Panel(Kind.CAL), Panel(Kind.STOCK, 10),
                    Panel(Kind.CAL), Panel(Kind.STOCK, 1)
                )
            }
        }
        // Weather on: one clean loop of every enabled window, ending with the
        // two weather views (hourly, then daily).
        val out = ArrayList<Panel>()
        out.add(Panel(Kind.CAL))
        if (stocks) {
            val chosen = Prefs.stockPeriod(appContext)
            if (chosen == 1 || chosen == 10 || chosen == 60) {
                out.add(Panel(Kind.STOCK, chosen))
            } else {
                out.add(Panel(Kind.STOCK, 60))
                out.add(Panel(Kind.STOCK, 10))
                out.add(Panel(Kind.STOCK, 1))
            }
        }
        out.add(Panel(Kind.HOUR))
        out.add(Panel(Kind.DAY))
        return out
    }

    private fun renderPanel() {
        val panel = panelSeq.getOrNull(panelStep) ?: return
        when (panel.kind) {
            Kind.CAL -> showCalendarOnly()
            Kind.STOCK -> showChart(panel.interval)
            Kind.HOUR -> showWeather(WeatherPanelView.MODE_HOURLY)
            Kind.DAY -> showWeather(WeatherPanelView.MODE_DAILY)
        }
    }

    private fun showCalendarOnly() {
        monthView.visibility = View.VISIBLE
        stockView.visibility = View.GONE
        weatherView.visibility = View.GONE
    }

    private fun showChart(code: Int) {
        monthView.visibility = View.GONE
        weatherView.visibility = View.GONE
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

    private fun showWeather(mode: Int) {
        weatherMode = mode
        monthView.visibility = View.GONE
        stockView.visibility = View.GONE
        weatherView.visibility = View.VISIBLE
        renderWeather()
        fetchWeatherIfStale()
    }

    private fun renderWeather() {
        val data = weatherCached
        if (data == null) {
            weatherView.setStatus(
                if (weatherFetching) "Погода: загрузка…" else "Погода: нет данных"
            )
            return
        }
        if (weatherMode == WeatherPanelView.MODE_HOURLY) {
            weatherView.showHourly(data)
        } else {
            weatherView.showDaily(data)
        }
    }

    /** Fetch the forecast on a background thread when it is missing or stale. */
    private fun fetchWeatherIfStale() {
        if (weatherFetching) return
        val cached = weatherCached
        if (cached != null &&
            System.currentTimeMillis() - weatherFetchedAt < weatherFreshMs
        ) {
            return
        }
        val loc = Prefs.weatherLocation(appContext) ?: return
        weatherFetching = true
        if (weatherCached == null && weatherView.visibility == View.VISIBLE) {
            weatherView.setStatus("Погода: загрузка…")
        }
        val gen = ++weatherGen
        val city = Prefs.weatherCity(appContext)
        Thread {
            val data = WeatherApi.fetch(loc.first, loc.second, city)
            handler.post {
                if (gen != weatherGen) return@post
                weatherFetching = false
                if (data != null) {
                    weatherCached = data
                    weatherFetchedAt = System.currentTimeMillis()
                    if (weatherView.visibility == View.VISIBLE) renderWeather()
                } else if (weatherView.visibility == View.VISIBLE) {
                    weatherView.setStatus("Погода: нет данных / нет сети")
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
        // Only ever show the player card while audio is actually playing.
        if (now == null || !now.playing) {
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
        mediaGlyph.text = "\u25B6"
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
