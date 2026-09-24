package com.aidarbreeze.alwayson

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.text.TextUtils.TruncateAt
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import com.aidarbreeze.alwayson.media.MediaArtView
import com.aidarbreeze.alwayson.media.MediaWatcher
import com.aidarbreeze.alwayson.notif.NotifCache
import com.aidarbreeze.alwayson.notif.NotifItem
import com.aidarbreeze.alwayson.stock.Candle
import com.aidarbreeze.alwayson.stock.StockApi
import com.aidarbreeze.alwayson.view.ClockView
import com.aidarbreeze.alwayson.view.MonthCalendarView
import com.aidarbreeze.alwayson.view.StockChartView
import com.aidarbreeze.alwayson.view.WeatherPanelView
import com.aidarbreeze.alwayson.weather.WeatherInfo
import com.aidarbreeze.alwayson.weather.WeatherRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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
class StandbyController(context: Context, private val root: View) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val clockView: ClockView = root.findViewById(R.id.clockText)
    private val dateText: TextView = root.findViewById(R.id.dateText)
    private val batteryText: TextView = root.findViewById(R.id.batteryText)
    private val mediaGroup: View = root.findViewById(R.id.mediaGroup)
    private val mediaGlyph: TextView = root.findViewById(R.id.mediaGlyph)
    private val mediaText: TextView = root.findViewById(R.id.mediaText)
    private val mediaArt: MediaArtView = root.findViewById(R.id.mediaArt)
    private val mediaPrev: TextView = root.findViewById(R.id.mediaPrev)
    private val mediaNext: TextView = root.findViewById(R.id.mediaNext)
    private val monthView: MonthCalendarView = root.findViewById(R.id.monthView)
    private val stockView: StockChartView = root.findViewById(R.id.stockView)
    private val weatherView: WeatherPanelView = root.findViewById(R.id.weatherView)
    private val content: View = root.findViewById(R.id.standbyContent)
    // Dedicated black backdrop: on entry it is opaque from the very first
    // frame (see enter()) and the data is revealed over it.
    private val aodBg: View = root.findViewById(R.id.aodBg)

    // Swipe pages: 1 = the usual clock/calendar screen, 2 = the messenger
    // notifications list (Telegram / Max) under a compact clock.
    private val pageClock: View = root.findViewById(R.id.pageClock)
    private val pageNotifs: View = root.findViewById(R.id.pageNotifs)
    private val notifTimeText: TextView = root.findViewById(R.id.notifTimeText)
    private val notifDateText: TextView = root.findViewById(R.id.notifDateText)
    private val notifList: LinearLayout = root.findViewById(R.id.notifList)
    private val notifEmpty: TextView = root.findViewById(R.id.notifEmpty)

    private val mediaWatcher = MediaWatcher(appContext)

    private var lastDay = -1
    private var tickCount = 0
    private var lastMedia: String? = null
    // Minutes elapsed, used to compute the burn-in drift offset.
    private var driftTick = 0L

    // --- panel alternation: calendar <-> stock chart <-> weather ---
    // A fixed set of "windows" is rotated on a timer. Windows can be the month
    // calendar, a MOEX chart of a chosen interval, or the weather forecast.
    // Weather is only included once a forecast point (geolocation or a manually
    // entered city) is available.
    private enum class Kind { CAL, STOCK, WEATHER }
    private class Panel(val kind: Kind, val interval: Int = 0, val symbol: String = "")

    // Stock data, keyed by "SYMBOL-INTERVAL" so the watchlist (up to 3
    // tickers) does not clobber each other's cache.
    private val stockCached = HashMap<String, List<Candle>>()
    // Interval the cached candles REALLY are (a fallback may have served a
    // coarser one than the requested key says).
    private val stockActualCode = HashMap<String, Int>()
    // When each key was last attempted, so the panel cycle does not hit the
    // network again for data we just received (or a bad ticker that keeps
    // returning nothing). The timestamp doubles as the fetch-generation:
    // a result is applied only if no newer fetch for the same key started.
    private val stockAttemptAt = HashMap<String, Long>()
    // Which ticker+interval the chart view is currently showing.
    private var currentStock: Pair<String, Int>? = null
    private val stockFreshMs = 60_000L
    private var weatherCached: WeatherInfo? = null
    private var weatherStale = false
    private var panelSeq: List<Panel> = emptyList()
    private var panelStep = 0
    // Long-press in the preview pins the current window (pauses rotation).
    private var pinned = false

    // Smooth window transition: the current panel fades out to black, a short
    // hold, then the next panel is swapped in and fades back in. Driven by the
    // main handler (no dependencies); [transitionGen] invalidates pending
    // frames/swaps when the cycle restarts or the screen is torn down, so a
    // window can never be left half-faded.
    private val fadeMs = 350L
    private val fadeHoldMs = 120L
    private var panelAnimating = false
    private var transitionGen = 0L
    private val panelRunnable = object : Runnable {
        override fun run() {
            if (panelSeq.isEmpty()) return
            // Never advance while a fade is in flight, or a window would be
            // skipped; the next tick retries the step.
            if (!panelAnimating) {
                panelStep = (panelStep + 1) % panelSeq.size
                renderPanel()
            }
            // Each window type has its own on-screen duration (user setting);
            // the delay is measured for the panel that is on screen now.
            handler.postDelayed(this, durationMs(panelSeq.getOrNull(panelStep)))
        }
    }

    /** The user-set on-screen duration (ms) of a window type. */
    private fun durationMs(panel: Panel?): Long {
        val sec = when (panel?.kind) {
            Kind.CAL -> Prefs.panelDurationCal(appContext)
            Kind.STOCK -> Prefs.panelDurationStock(appContext)
            Kind.WEATHER -> Prefs.panelDurationWeather(appContext)
            null -> 10
        }
        return sec * 1000L
    }

    /** Toggle pinning; returns true while pinned. */
    fun togglePinned(): Boolean {
        pinned = !pinned
        if (pinned) {
            handler.removeCallbacks(panelRunnable)
        } else {
            handler.postDelayed(panelRunnable, durationMs(panelSeq.getOrNull(panelStep)))
        }
        return pinned
    }

    /**
     * Re-read every user setting and rebuild the panel cycle without
     * recreating the screen. Used by the full-screen preview when the user
     * comes back from the settings with changed styles/panels: without this
     * the running preview kept showing the old configuration until restart.
     */
    fun reapplySettings() {
        applyOptions()
        startPanelCycle()
    }

    /** Short label for an ISS interval code, shown at the bottom of the chart. */
    private fun intervalLabel(code: Int): String {
        val m = if (Locale.getDefault().language.equals("ru", ignoreCase = true)) "М" else "m"
        return "$code$m"
    }

    // --- auto brightness (ambient light sensor) ---
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var lightListener: SensorEventListener? = null
    private var autoBrightness = false
    // iPhone-style Night Mode: red tint in the dark (see setNightActive).
    // Written on the main thread, read from the sensor callback thread.
    @Volatile
    private var nightMode = false
    @Volatile
    private var nightActive = false
    // Luminance-preserving red tint: every lit pixel becomes red scaled by
    // its brightness (a plain red MULTIPLY would turn blue/green content
    // black instead). Applied as the content layer's paint.
    private val nightPaint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.35f, 0.55f, 0.10f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
    // Smoothed current alpha so the light sensor does not cause flicker.
    private var currentAlpha = -1f
    private var manualAlpha = 1f
    private val sensorHandler = Handler(Looper.getMainLooper())
    private val minAlpha = 0.22f // lowest: dim but clearly visible in the dark
    // Entry progress (0..1): the data is only revealed after the black
    // backdrop has fully faded in.
    private var entranceAlpha = 1f

    // --- swipe paging (clock page <-> notifications page) ---
    /** Host hook: called on a single tap on a non-interactive area. The
     *  overlay uses it to exit, the dream to finish, the preview leaves it
     *  null (Back exits there). */
    var onTapExit: (() -> Unit)? = null

    private var currentPage = 0
    private var pageFlipping = false
    // Notifications-list rebuild signature (cache stamp + the "макс." cap).
    private var lastNotifSig = ""
    // Manual swipe/tap/long-press recognition on the root (the controller
    // owns the root touch: children like the media buttons still get their
    // events first and consume them themselves).
    private val touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownAt = 0L
    private var touchDragging = false
    private var touchLongFired = false
    private val longPressRunnable = Runnable {
        if (!touchDragging) {
            touchLongFired = true
            root.performLongClick()
        }
    }

    /** OLED-protection white cap: 85% when the dim mode is on. */
    private fun dimFactor(): Float = if (Prefs.dimMode(appContext)) 0.85f else 1f

    /**
     * The single place that writes the content alpha:
     * brightness level × OLED dim cap × entry progress.
     */
    private fun refreshContentAlpha() {
        val base = if (autoBrightness && currentAlpha >= 0f) currentAlpha
        else manualAlpha.coerceIn(minAlpha, 1f)
        content.alpha = base * dimFactor() * entranceAlpha
    }

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            tickCount++
            updateMedia() // poll every second so playback changes feel instant
            renderNotifs() // cheap signature check; rebuilds only on changes
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
            if (Prefs.batteryMode(appContext) != 0) View.VISIBLE else View.GONE
        // Clock style / thickness may have changed in settings -> redraw.
        clockView.refresh()
        // Calendar week start may have changed.
        monthView.setFirstDayOfWeek(Prefs.weekStart(appContext))
        // Dim the clock content only; the root background stays pure black.
        manualAlpha = (Prefs.brightness(appContext) / 100f).coerceIn(0f, 1f)
        autoBrightness = Prefs.autoBrightness(appContext)
        nightMode = Prefs.nightMode(appContext)
        // The light sensor feeds both the auto brightness and the Night
        // Mode tint; with both off there is nothing to listen for.
        if (!autoBrightness && !nightMode) {
            // Fixed manual level (kept at least slightly visible).
            refreshContentAlpha()
            setNightActive(false) // no sensor running: no night tint either
            stopLightSensor()
        } else {
            startLightSensor()
        }
    }

    /** Start ticking. Idempotent: pending frames from a previous start are
     *  cancelled first, so calling start() twice can never double the timers
     *  or the panel cycle. */
    fun start() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(drift)
        handler.removeCallbacks(panelRunnable)
        applyOptions()
        updateClock()
        updateMedia()
        updateBattery()
        renderNotifs(force = true)
        mediaPrev.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        mediaNext.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        attachGestures()
        // A stop() mid-flip left the page animation running (its end action
        // never fired and pageFlipping stayed true): cancel both animations
        // and clear the flag so swipes work from the first try. The explicit
        // visibility resets below override whatever the cancel unwound.
        pageClock.animate().cancel()
        pageNotifs.animate().cancel()
        pageFlipping = false
        // Always start on the clock page of a fresh session.
        currentPage = 0
        pageClock.visibility = View.VISIBLE
        pageClock.translationX = 0f
        pageNotifs.visibility = View.GONE
        pageNotifs.translationX = 0f
        lastDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        monthView.invalidate()
        handler.post(tick)
        handler.post(drift)
        startPanelCycle()
        enter()
    }

    /**
     * Entry sequence: the black backdrop is ALREADY opaque on the very first
     * frame — fading it in from transparent used to let the system lock
     * screen flash through the half-transparent window at every StandBy
     * on/off transition (the visible "blink" between the keyguard and the
     * clock). Only the data fades in now, quickly, over pure black. Driven
     * on the main handler with the shared generation counter, so a teardown
     * mid-entry can never leave the screen half-covered.
     */
    private fun enter() {
        aodBg.alpha = 1f
        entranceAlpha = 0f
        refreshContentAlpha()
        transitionGen++
        val gen = transitionGen
        val start = System.currentTimeMillis()
        val frame = object : Runnable {
            override fun run() {
                if (gen != transitionGen) return
                val t = ((System.currentTimeMillis() - start).toDouble() / 200.0)
                    .coerceIn(0.0, 1.0)
                entranceAlpha = t.toFloat()
                refreshContentAlpha()
                if (t < 1.0) handler.postDelayed(this, 16)
            }
        }
        handler.postDelayed(frame, 16)
    }

    fun stop() {
        stopLightSensor()
        // Invalidate any in-flight transition and drop all pending frames so
        // no window is left half-faded when the screen goes away.
        transitionGen++
        panelAnimating = false
        pinned = false
        // Same for a flip caught mid-animation: the end action (which resets
        // pageFlipping) would never run after the teardown below.
        pageClock.animate().cancel()
        pageNotifs.animate().cancel()
        pageFlipping = false
        handler.removeCallbacksAndMessages(null)
        root.setOnTouchListener(null)
        onTapExit = null
        // Clean entry state: the next start() animates from scratch.
        aodBg.alpha = 1f
        entranceAlpha = 1f
        monthView.alpha = 1f
        stockView.alpha = 1f
        weatherView.alpha = 1f
        refreshContentAlpha()
        showCalendarOnly()
    }

    // ---------- swipe paging: clock page <-> notifications page ----------

    /**
     * Root gestures, consumed by the controller (children still get their
     * events first and consume them themselves — e.g. the media buttons):
     *  - horizontal swipe -> flip between the clock and notifications pages,
     *  - quick tap         -> [onTapExit] (the host decides: overlay hides,
     *    the dream finishes, the preview does nothing),
     *  - long-press        -> the root's OnLongClickListener (preview pin).
     */
    private fun attachGestures() {
        root.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = ev.x
                    touchDownY = ev.y
                    touchDownAt = SystemClock.uptimeMillis()
                    touchDragging = false
                    touchLongFired = false
                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(longPressRunnable, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!touchDragging &&
                        (abs(ev.x - touchDownX) > touchSlop ||
                            abs(ev.y - touchDownY) > touchSlop)
                    ) {
                        touchDragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    val dx = ev.x - touchDownX
                    val dy = ev.y - touchDownY
                    val dt = SystemClock.uptimeMillis() - touchDownAt
                    when {
                        touchLongFired -> Unit // long-press already handled
                        abs(dx) > touchSlop * 2.5f && abs(dx) > abs(dy) * 1.6f -> flipPage()
                        !touchDragging && dt < 500L -> onTapExit?.invoke()
                        // a slow drag / pause: deliberate, not a tap — stay.
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> true
            }
        }
    }

    /** Flip to the other page with a short horizontal slide ("перелистывание"). */
    private fun flipPage() {
        if (!Prefs.notifPageEnabled(appContext) || pageFlipping) return
        val target = if (currentPage == 0) 1 else 0
        val outView = if (currentPage == 0) pageClock else pageNotifs
        val inView = if (target == 0) pageClock else pageNotifs
        val width = root.width.toFloat().coerceAtLeast(1f)
        // 1 -> content leaves to the left; 0 -> to the right.
        val dir = if (target == 1) -1f else 1f
        if (target == 1) renderNotifs(force = true)
        currentPage = target
        pageFlipping = true
        inView.visibility = View.VISIBLE
        inView.translationX = -dir * width
        inView.animate().translationX(0f).setDuration(240L).start()
        outView.animate().translationX(dir * width).setDuration(240L)
            .withEndAction {
                outView.visibility = View.GONE
                outView.translationX = 0f
                pageFlipping = false
            }.start()
    }

    /**
     * Rebuild the notifications rows from [NotifCache] (newest first, at most
     * Prefs.notifMax rows — the user's "макс." cap). Cheap no-op while the
     * cache stamp and the cap are unchanged; hidden page rebuilds on flip.
     */
    private fun renderNotifs(force: Boolean = false) {
        val max = Prefs.notifMax(appContext)
        val sig = NotifCache.updatedAt.toString() + '#' + max
        if (!force && sig == lastNotifSig) return
        lastNotifSig = sig
        if (!force && pageNotifs.visibility != View.VISIBLE) return
        val items = NotifCache.items.take(max)
        notifList.removeAllViews()
        notifEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (item in items) notifList.addView(notifRow(item))
    }

    /** One notifications row: "HH:mm  Title / text" (times per the 12/24h). */
    private fun notifRow(item: NotifItem): View {
        val density = appContext.resources.displayMetrics.density
        fun dpI(v: Int): Int = (v * density).toInt().coerceAtLeast(1)
        val use24 = Prefs.force24h(appContext) || DateFormat.is24HourFormat(appContext)
        val timeFmt = SimpleDateFormat(if (use24) "HH:mm" else "h:mm", Locale.getDefault())

        val row = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpI(10) }
        }
        val time = TextView(appContext).apply {
            text = timeFmt.format(Date(item.timeMs))
            textSize = 13f
            setTextColor(appContext.getColor(R.color.aod_text_tertiary))
            setPadding(0, dpI(2), dpI(12), 0)
        }
        val col = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
        }
        col.addView(TextView(appContext).apply {
            text = item.title
            textSize = 15f
            setTextColor(appContext.getColor(R.color.aod_text_primary))
            maxLines = 1
            ellipsize = TruncateAt.END
        })
        if (item.text.isNotEmpty() && item.text != item.title) {
            col.addView(TextView(appContext).apply {
                text = item.text
                textSize = 13f
                setTextColor(appContext.getColor(R.color.aod_secondary_text))
                maxLines = 2
                ellipsize = TruncateAt.END
            })
        }
        row.addView(time)
        row.addView(
            col,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        return row
    }

    // ---------- calendar <-> stock chart alternation ----------

    /** The watchlist (1-3 MOEX tickers), empty when nothing usable is set. */
    private fun tickers(): List<String> = Prefs.stockTickers(appContext)

    private fun weatherReady(): Boolean =
        Prefs.weatherEnabled(appContext) && Prefs.hasWeatherLocation(appContext)

    private fun startPanelCycle() {
        // A new cycle invalidates any in-flight transition; restore full
        // opacity in case a view was caught mid-fade.
        transitionGen++
        panelAnimating = false
        monthView.alpha = 1f
        stockView.alpha = 1f
        weatherView.alpha = 1f
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
        if (!pinned) handler.postDelayed(panelRunnable, durationMs(panelSeq[0]))
    }

    /**
     * The rotating set of windows for the currently enabled modes. Every
     * watchlist ticker gets its own chart window(s): a fixed period gives one
     * window per ticker; "auto" cycles 60/10/1 for a single ticker and shows
     * one 60-min window per ticker for a longer list (keeps the cycle sane).
     */
    private fun buildPanels(): List<Panel> {
        val tickers = tickers()
        val stocks = Prefs.stocksEnabled(appContext) && tickers.isNotEmpty()
        val weather = weatherReady()
        if (!stocks && !weather) return listOf(Panel(Kind.CAL))
        val out = ArrayList<Panel>()
        out.add(Panel(Kind.CAL))
        if (stocks) {
            val chosen = Prefs.stockPeriod(appContext)
            for (t in tickers) {
                when {
                    chosen == 1 || chosen == 10 || chosen == 60 ->
                        out.add(Panel(Kind.STOCK, chosen, t))
                    tickers.size == 1 -> {
                        out.add(Panel(Kind.STOCK, 60, t))
                        out.add(Panel(Kind.STOCK, 10, t))
                        out.add(Panel(Kind.STOCK, 1, t))
                    }
                    else -> out.add(Panel(Kind.STOCK, 60, t))
                }
            }
        }
        if (weather) out.add(Panel(Kind.WEATHER))
        return out
    }

    private fun renderPanel() {
        if (panelAnimating) return
        val panel = panelSeq.getOrNull(panelStep) ?: return
        // Same window (e.g. the next stock interval, or a weather data
        // refresh): swap the content in place, no fade needed.
        if (currentPanelKind() == panel.kind) {
            applyPanel(panel)
            return
        }
        val outgoing = visiblePanel()
        if (outgoing == null) {
            applyPanel(panel)
            return
        }
        // Fade the current window out to black, hold briefly, then swap in the
        // next window and fade it back in.
        panelAnimating = true
        val gen = transitionGen
        animateAlpha(outgoing, 0f, gen) {
            handler.postDelayed({
                if (gen != transitionGen || !panelAnimating) return@postDelayed
                outgoing.visibility = View.GONE
                outgoing.alpha = 1f
                applyPanel(panel)
                val incoming = visiblePanel()
                if (incoming == null) {
                    panelAnimating = false
                    return@postDelayed
                }
                incoming.alpha = 0f
                animateAlpha(incoming, 1f, gen) {
                    if (gen == transitionGen) panelAnimating = false
                }
            }, fadeHoldMs)
        }
    }

    /** Which window is currently on screen, if any. */
    private fun currentPanelKind(): Kind? = when {
        stockView.visibility == View.VISIBLE -> Kind.STOCK
        weatherView.visibility == View.VISIBLE -> Kind.WEATHER
        monthView.visibility == View.VISIBLE -> Kind.CAL
        else -> null
    }

    private fun visiblePanel(): View? = when {
        stockView.visibility == View.VISIBLE -> stockView
        weatherView.visibility == View.VISIBLE -> weatherView
        monthView.visibility == View.VISIBLE -> monthView
        else -> null
    }

    private fun applyPanel(panel: Panel) {
        when (panel.kind) {
            Kind.CAL -> showCalendarOnly()
            Kind.STOCK -> showChart(panel.symbol, panel.interval)
            Kind.WEATHER -> showWeather()
        }
    }

    /**
     * A small linear alpha animation driven by the main handler. Frames of a
     * superseded transition ([gen] != [transitionGen]) are dropped silently,
     * and stop() clears the pending frames outright.
     */
    private fun animateAlpha(view: View, target: Float, gen: Long, onEnd: () -> Unit) {
        val from = view.alpha
        if (from == target) {
            if (gen == transitionGen) onEnd()
            return
        }
        val start = System.currentTimeMillis()
        val frame = object : Runnable {
            override fun run() {
                if (gen != transitionGen) return
                val t = ((System.currentTimeMillis() - start).toDouble() / fadeMs).coerceIn(0.0, 1.0)
                view.alpha = (from + (target - from) * t).toFloat()
                if (t < 1.0) handler.postDelayed(this, 16) else onEnd()
            }
        }
        handler.postDelayed(frame, 16)
    }

    private fun showCalendarOnly() {
        monthView.visibility = View.VISIBLE
        stockView.visibility = View.GONE
        weatherView.visibility = View.GONE
    }

    private fun stockKey(symbol: String, code: Int): String = "$symbol-$code"

    private fun showChart(symbol: String, code: Int) {
        monthView.visibility = View.GONE
        weatherView.visibility = View.GONE
        stockView.visibility = View.VISIBLE
        currentStock = symbol to code

        val ref = Prefs.stockReference(appContext)
        if (symbol.isEmpty()) {
            stockView.setStatus("—")
            return
        }
        val key = stockKey(symbol, code)
        val cached = stockCached[key]
        if (cached != null && cached.size >= 2) {
            // Show what we have immediately (may be minutes old) and refresh
            // in the background only when the cache is stale.
            val useCode = stockActualCode[key] ?: code
            stockView.setData(symbol, ref, intervalLabel(useCode), cached, useCode * 60)
        } else {
            stockView.setStatus("Загрузка…")
        }
        val age = System.currentTimeMillis() - (stockAttemptAt[key] ?: 0L)
        if (age >= stockFreshMs) {
            fetchStock(symbol, code, ref)
        }
    }

    /** Honest one-line status for a failed stock fetch (null = stay silent,
     *  keep showing "Загрузка…" — the retry comes with the next cycle). */
    private fun stockErrorText(res: StockApi.FetchResult): String? = when (res.error) {
        null, StockApi.FetchError.RATE_LIMITED -> null
        StockApi.FetchError.BAD_TICKER, StockApi.FetchError.NOT_FOUND -> "тикер не найден"
        StockApi.FetchError.CLOSED_EMPTY -> "торги закрыты"
        StockApi.FetchError.NETWORK ->
            if (res.httpCode > 0) "нет сети (HTTP ${res.httpCode})" else "нет сети"
    }

    /** Fetch candles for a ticker+interval; apply the result only when it is
     *  still the freshest attempt for that key AND the chart still shows it. */
    private fun fetchStock(symbol: String, code: Int, ref: Double) {
        val key = stockKey(symbol, code)
        val attempt = System.currentTimeMillis()
        stockAttemptAt[key] = attempt
        Thread {
            val res = StockApi.fetch(symbol, code)
            handler.post {
                if (stockAttemptAt[key] != attempt) return@post // superseded
                val data = res.candles
                if (data != null && data.size >= 2) {
                    stockCached[key] = data
                    stockActualCode[key] = res.actualCode
                    Prefs.setLastStockUpdateMs(appContext, System.currentTimeMillis())
                    Prefs.setLastStockError(appContext, "")
                    if (currentStock == symbol to code) {
                        // A fallback may have served a coarser interval:
                        // label the chart with what it really shows.
                        val useCode = res.actualCode
                        stockView.setData(symbol, ref, intervalLabel(useCode), data, useCode * 60)
                    }
                } else if (currentStock == symbol to code &&
                    stockCached[key] == null
                ) {
                    // Only show the error when we have nothing to show at all —
                    // a transient network hiccup must not wipe an older chart.
                    val msg = stockErrorText(res)
                    if (msg != null) {
                        stockView.setStatus(msg)
                        Prefs.setLastStockError(appContext, msg)
                    }
                }
            }
        }.start()
    }

    private fun showWeather() {
        monthView.visibility = View.GONE
        stockView.visibility = View.GONE
        weatherView.visibility = View.VISIBLE
        renderWeather()
        fetchWeatherIfStale()
    }

    private fun renderWeather() {
        val data = weatherCached
        if (data == null) {
            weatherView.setStatus("Погода: нет данных")
            return
        }
        weatherView.show(data, Prefs.weatherStyle(appContext), weatherStale)
    }

    /**
     * Ensure we have a forecast, via the shared [WeatherRepository]: it dedupes
     * in-flight requests, applies a TTL (so panel switches never re-fetch) and
     * hands back the last-known forecast with a stale mark when a refresh
     * fails — so a transient network error never blanks a good forecast.
     * The repository delivers on the main thread.
     */
    private fun fetchWeatherIfStale(force: Boolean = false) {
        if (!weatherReady()) return
        val loc = Prefs.weatherLocation(appContext) ?: return
        if (weatherCached == null && weatherView.visibility == View.VISIBLE) {
            weatherView.setStatus("Погода: загрузка…")
        }
        WeatherRepository.get(
            appContext, loc.first, loc.second, Prefs.weatherCity(appContext), force
        ) { res ->
            if (res.info != null) {
                weatherCached = res.info
                weatherStale = res.stale
                Prefs.setLastWeatherUpdateMs(appContext, System.currentTimeMillis())
                Prefs.setLastWeatherError(appContext, "")
                if (weatherView.visibility == View.VISIBLE) renderWeather()
            } else if (weatherView.visibility == View.VISIBLE) {
                // Nothing at all (never fetched for this location): a clear
                // state, not a stale forecast of another city.
                weatherStale = false
                val err = if (res.offline) "нет сети" else "нет данных"
                weatherView.setStatus("Погода: $err")
                Prefs.setLastWeatherError(appContext, err)
            }
        }
    }

    // ---------- auto brightness (ambient light) ----------

    /**
     * Toggles the iPhone-style red night tint. Implemented as a hardware
     * layer with a luminance-preserving red ColorMatrix on the whole
     * content block: every lit pixel turns red, the black background stays
     * black, and no custom view needs to know about it.
     */
    private fun setNightActive(active: Boolean) {
        if (active == nightActive) return
        nightActive = active
        content.setLayerType(
            if (active) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE,
            if (active) nightPaint else null
        )
        content.invalidate()
    }

    /**
     * Registers the ambient light sensor. On every reading we map lux to an
     * alpha target (dark room -> barely visible, bright/sun -> full manual
     * brightness), ease the actual alpha toward it to avoid flicker, and
     * evaluate the Night Mode red tint.
     */
    private fun startLightSensor() {
        if (lightListener != null) return // already listening
        val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return
        sensorManager = sm
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        lightSensor = sensor
        currentAlpha = if (currentAlpha < 0f) minAlpha else currentAlpha
        refreshContentAlpha()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_LIGHT) return
                val lux = event.values[0]
                // Night Mode wants red below ~8 lux and releases above ~30
                // (hysteresis so a flickering doorway does not strobe it).
                val wantNight = nightMode && (lux < 8f || (nightActive && lux < 30f))
                // Target between min (dark) and manualAlpha (bright).
                val target = targetAlpha(lux)
                // Ease toward it on the main thread (a few % per reading).
                sensorHandler.post {
                    if (autoBrightness) {
                        currentAlpha = if (currentAlpha < 0f) target
                        else currentAlpha + (target - currentAlpha) * 0.25f
                        refreshContentAlpha()
                    }
                    setNightActive(wantNight)
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
        // Mode 1 = always, 2 = preview only (so no seconds on the AOD).
        val secs = Prefs.secondsMode(appContext) == 1
        val pattern = when {
            use24 && secs -> "HH:mm:ss"
            use24 -> "HH:mm"
            secs -> "h:mm:ss"
            else -> "h:mm"
        }
        // The clock view self-fits to its column for every style.
        val timeStr = SimpleDateFormat(pattern, Locale.getDefault()).format(millis)
        clockView.setTime(timeStr)
        val date = dateLine(now)
        dateText.text = date
        // The notifications page carries a compact clock of its own ("time
        // with the notifications list").
        notifTimeText.text = timeStr
        notifDateText.text = date
    }

    /** Date line shown above the clock, per the user's format:
     *  0 = "Понедельник, 8 сентября" (weekday + day + month, capitalised),
     *  1 = short "08.09", 2 = full "8 сентября". */
    private fun dateLine(now: Calendar): String {
        val locale = Locale.getDefault()
        val ru = locale.language.equals("ru", ignoreCase = true)
        val pattern = when (Prefs.dateFormat(appContext)) {
            1 -> "dd.MM"
            2 -> if (ru) "d MMMM" else "MMMM d"
            else -> if (ru) "EEEE, d MMMM" else "EEEE, MMMM d"
        }
        val raw = SimpleDateFormat(pattern, locale).format(now.timeInMillis)
        return if (Prefs.dateFormat(appContext) == 0)
            raw.replaceFirstChar { it.titlecase(locale) }
        else raw
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
        // Album art (grayscale tile), shown only when the player provides it.
        mediaArt.setArt(now.art)
        mediaArt.visibility = if (now.art != null) View.VISIBLE else View.GONE
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
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        when (Prefs.batteryMode(appContext)) {
            // 1: percent only
            1 -> batteryText.text = "$percent%"
            // 3: percent + charging indicator
            3 -> batteryText.text = if (charging) "$percent% \u26A1\uFE0E" else "$percent%"
            // 2 (default): percent + live charge current when the device
            //    reports it. Normalised mA (always positive); the sign is
            //    derived from the charge status, NOT from the raw value's
            //    sign (raw signs are not consistent across devices, and the
            //    raw property is in microamps and must not be shown as-is).
            else -> {
                val ma = BatteryInfo.readCurrentMa(appContext)
                if (ma <= 0) {
                    batteryText.text = "$percent%"
                    return
                }
                val withSign = if (charging) "+$ma" else "-$ma"
                batteryText.text = "$percent% · $withSign"
            }
        }
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
