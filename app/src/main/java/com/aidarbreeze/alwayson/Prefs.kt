package com.aidarbreeze.alwayson

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Thin wrapper around the app SharedPreferences with encryption support.
 *
 * PERFORMANCE: this object is read from every custom view's onDraw (clock /
 * calendar / weather / chart) and from 1-second UI tickers, i.e. dozens of
 * times per second on the main thread. Creating EncryptedSharedPreferences
 * (MasterKey + keystore + disk + per-value decrypt) on every access visibly
 * janked scrolling, so:
 *  - the SharedPreferences INSTANCE is created once and cached, and
 *  - every value goes through an in-memory write-through cache ([mem]), so a
 *    hot read is a single map lookup and never touches crypto or disk.
 *
 * The whole app runs in one process (no android:process in the manifest), so
 * the cache cannot go stale behind our back: every write goes through here.
 */
object Prefs {
    private const val FILE = "alwayson_prefs"

    private const val KEY_USE_24H = "use_24h"
    private const val KEY_SHOW_SECONDS = "show_seconds"
    private const val KEY_SHOW_BATTERY = "show_battery"
    // How bright the clock content should be (0..100). Applied to the clock
    // text only, never to the black background.
    private const val KEY_BRIGHTNESS = "brightness"
    // Automatically tune the clock brightness to the ambient light sensor so
    // it is barely visible in the dark yet clearly readable in daylight.
    private const val KEY_AUTO_BRIGHTNESS = "auto_brightness"
    // Clock face style: 0 = normal text, 1 = outline/hollow digits,
    // 2 = dot-matrix "comic" digits, 3 = old flip-clock digits,
    // 4 = seven-segment LED, 5 = neon glow, 6 = rounded chips/blocks,
    // 7 = serif, 8 = heavy italic, 9 = square LED-matrix.
    private const val KEY_CLOCK_STYLE = "clock_style"
    // Line/outline thickness in dp used by the outline style.
    private const val KEY_CLOCK_THICKNESS = "clock_thickness"
    // Clock accent color preset index (0 = white, see clockColorValue).
    private const val KEY_CLOCK_COLOR = "clock_color"
    // iPhone-style Night Mode: red tint of the standby screen in the dark.
    private const val KEY_NIGHT_MODE = "night_mode"
    // Calendar <-> stock chart alternation mode.
    private const val KEY_STOCKS_ENABLED = "stocks_enabled"
    /** Legacy single-ticker storage: superseded by [stockTickers]; the key is
     *  still READ as the fallback for old installs (see stockTickers), but no
     *  code writes it anymore. */
    private const val KEY_STOCK_TICKER = "stock_ticker"
    private const val KEY_STOCK_REF = "stock_reference"
    // Exact (double-bit) storage of the reference price; the legacy float
    // KEY_STOCK_REF migrates on first read (275.35f.toString() -> "275.35",
    // not 275.3500061035156).
    private const val KEY_STOCK_REF_D = "stock_reference_d"
    // Stock chart rendering: 0 = line of closes, 1 = candlesticks.
    private const val KEY_STOCK_TYPE = "stock_type"
    // Chart period/timeframe: 0 = auto-cycle, otherwise a MOEX interval code
    // (1, 10 or 60) to keep showing.
    private const val KEY_STOCK_PERIOD = "stock_period"
    // First day of the calendar week. 0 = follow the locale/system; otherwise
    // a Calendar.DAY_OF_WEEK constant (1 = Sunday, 2 = Monday, 7 = Saturday).
    private const val KEY_WEEK_START = "week_start"
    // Calendar style: 0 classic, 1 minimal, 2 filled today, 3 outlined,
    // 4 weekend accent, 5 monochrome OLED, 6 compact, 7 large numbers.
    private const val KEY_CALENDAR_STYLE = "calendar_style"
    private const val KEY_AUTO_STANDBY = "auto_standby"
    // Weather (Open-Meteo) alternation panel.
    private const val KEY_WEATHER_ENABLED = "weather_enabled"
    // Coordinates are stored as Float.NaN when not set yet.
    private const val KEY_WEATHER_LAT = "weather_lat"
    private const val KEY_WEATHER_LON = "weather_lon"
    // Display name of the location (from manual geocode or empty for GPS).
    private const val KEY_WEATHER_CITY = "weather_city"
    // Weather panel style: 0 = classic (hero temperature + hourly columns +
    // week list with range bars), 1 = curve (24 h temperature curve + dense
    // two-column week list).
    private const val KEY_WEATHER_STYLE = "weather_style"
    // Per-window rotation duration in seconds (calendar / stocks / weather).
    private const val KEY_DUR_CAL = "panel_dur_cal"
    private const val KEY_DUR_STOCK = "panel_dur_stock"
    private const val KEY_DUR_WEATHER = "panel_dur_weather"
    // MOEX watchlist: comma separated, up to 3 tickers (e.g. "ISS,TATN").
    private const val KEY_STOCK_TICKERS = "stock_tickers"
    // Auto-standby schedule: 0 = always (while charging), 1 = custom hours.
    private const val KEY_STANDBY_SCHEDULE = "standby_schedule"
    private const val KEY_STANDBY_FROM_HOUR = "standby_from_hour"
    private const val KEY_STANDBY_TO_HOUR = "standby_to_hour"
    // The user dismissed the auto overlay for this charge session; kept
    // across process death so a kill+reboot does not nag again.
    // OLED protection: dim the white content level (see dimMode).
    private const val KEY_DIM_MODE = "dim_mode"
    // Clock size: 0 small, 1 normal (default), 2 large.
    private const val KEY_CLOCK_SIZE = "clock_size"
    // Date line format: 0 = weekday + day + month (default), 1 = short
    // (dd.MM), 2 = full (month name + day).
    private const val KEY_DATE_FORMAT = "date_format"
    // Temperature unit: 0 = Celsius (default), 1 = Fahrenheit (display only).
    private const val KEY_TEMP_UNIT = "temp_unit"
    // Seconds display: 0 never, 1 always, 2 preview only.
    private const val KEY_SECONDS_MODE = "seconds_mode"
    // Battery display: 0 hidden, 1 percent, 2 current, 3 charging indicator.
    private const val KEY_BATTERY_MODE = "battery_mode"
    // Swipe page with the messenger notifications (Telegram / Max) list.
    private const val KEY_NOTIF_PAGE = "notif_page"
    // How many notifications that page shows at most ("макс."). 1..10.
    private const val KEY_NOTIF_MAX = "notif_max"
    // Diagnostics: last successful data updates + last errors.
    private const val KEY_LAST_WEATHER_MS = "last_weather_ms"
    private const val KEY_LAST_WEATHER_ERR = "last_weather_err"
    private const val KEY_LAST_STOCK_MS = "last_stock_ms"
    private const val KEY_LAST_STOCK_ERR = "last_stock_err"

    // ---------- cached storage access ----------

    @Volatile
    private var cached: SharedPreferences? = null
    private val spLock = Any()

    /** In-memory write-through cache of every value (see class doc). */
    private val mem = java.util.concurrent.ConcurrentHashMap<String, Any?>()

    /** The single SharedPreferences instance (encrypted, plain on failure). */
    private fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(spLock) {
            cached?.let { return it }
            val app = ctx.applicationContext
            val created = try {
                EncryptedSharedPreferences.create(
                    app,
                    FILE,
                    MasterKey.Builder(app)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Exception) {
                // Fallback to regular SharedPreferences if encryption fails.
                // Decided once and cached, so we never mix formats per call.
                app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            }
            cached = created
            return created
        }
    }

    private fun has(ctx: Context, key: String): Boolean {
        if (mem.containsKey(key)) return true
        return try {
            prefs(ctx).contains(key)
        } catch (_: Exception) {
            false
        }
    }

    private fun b(ctx: Context, key: String, def: Boolean): Boolean {
        (mem[key] as? Boolean)?.let { return it }
        val v = try {
            prefs(ctx).getBoolean(key, def)
        } catch (_: Exception) {
            def
        }
        mem[key] = v
        return v
    }

    private fun setB(ctx: Context, key: String, v: Boolean) {
        mem[key] = v
        try {
            prefs(ctx).edit().putBoolean(key, v).apply()
        } catch (_: Exception) {
            // best effort
        }
    }

    private fun i(ctx: Context, key: String, def: Int): Int {
        (mem[key] as? Int)?.let { return it }
        val v = try {
            prefs(ctx).getInt(key, def)
        } catch (_: Exception) {
            def
        }
        mem[key] = v
        return v
    }

    private fun setI(ctx: Context, key: String, v: Int) {
        mem[key] = v
        try {
            prefs(ctx).edit().putInt(key, v).apply()
        } catch (_: Exception) {
            // best effort
        }
    }

    private fun l(ctx: Context, key: String, def: Long): Long {
        (mem[key] as? Long)?.let { return it }
        val v = try {
            prefs(ctx).getLong(key, def)
        } catch (_: Exception) {
            def
        }
        mem[key] = v
        return v
    }

    private fun setL(ctx: Context, key: String, v: Long) {
        mem[key] = v
        try {
            prefs(ctx).edit().putLong(key, v).apply()
        } catch (_: Exception) {
            // best effort
        }
    }

    private fun f(ctx: Context, key: String, def: Float): Float {
        (mem[key] as? Float)?.let { return it }
        val v = try {
            prefs(ctx).getFloat(key, def)
        } catch (_: Exception) {
            def
        }
        mem[key] = v
        return v
    }

    private fun setF(ctx: Context, key: String, v: Float) {
        mem[key] = v
        try {
            prefs(ctx).edit().putFloat(key, v).apply()
        } catch (_: Exception) {
            // best effort
        }
    }

    private fun s(ctx: Context, key: String, def: String): String {
        (mem[key] as? String)?.let { return it }
        val v = try {
            prefs(ctx).getString(key, def)
        } catch (_: Exception) {
            null
        } ?: def
        mem[key] = v
        return v
    }

    private fun setS(ctx: Context, key: String, v: String) {
        mem[key] = v
        try {
            prefs(ctx).edit().putString(key, v).apply()
        } catch (_: Exception) {
            // best effort
        }
    }

    fun force24h(ctx: Context): Boolean = b(ctx, KEY_USE_24H, false)
    fun setForce24h(ctx: Context, v: Boolean) = setB(ctx, KEY_USE_24H, v)

    fun showSeconds(ctx: Context): Boolean = b(ctx, KEY_SHOW_SECONDS, false)
    fun setShowSeconds(ctx: Context, v: Boolean) = setB(ctx, KEY_SHOW_SECONDS, v)

    fun showBattery(ctx: Context): Boolean = b(ctx, KEY_SHOW_BATTERY, false)
    fun setShowBattery(ctx: Context, v: Boolean) = setB(ctx, KEY_SHOW_BATTERY, v)

    /**
     * Seconds display: 0 never, 1 always, 2 preview only. Migrated from the
     * legacy binary switch (on -> always, off -> never).
     */
    fun secondsMode(ctx: Context): Int {
        return if (has(ctx, KEY_SECONDS_MODE)) i(ctx, KEY_SECONDS_MODE, 0).coerceIn(0, 2)
        else if (b(ctx, KEY_SHOW_SECONDS, false)) 1 else 0
    }
    fun setSecondsMode(ctx: Context, v: Int) = setI(ctx, KEY_SECONDS_MODE, v.coerceIn(0, 2))

    /**
     * Battery display: 0 hidden, 1 percent, 2 percent + current, 3 percent +
     * charging indicator. Migrated from the legacy switch (on -> current).
     */
    fun batteryMode(ctx: Context): Int {
        return if (has(ctx, KEY_BATTERY_MODE)) i(ctx, KEY_BATTERY_MODE, 0).coerceIn(0, 3)
        else if (b(ctx, KEY_SHOW_BATTERY, false)) 2 else 0
    }
    fun setBatteryMode(ctx: Context, v: Int) = setI(ctx, KEY_BATTERY_MODE, v.coerceIn(0, 3))

    /** Clock size: 0 small, 1 normal, 2 large. */
    fun clockSize(ctx: Context): Int = i(ctx, KEY_CLOCK_SIZE, 1).coerceIn(0, 2)
    fun setClockSize(ctx: Context, v: Int) = setI(ctx, KEY_CLOCK_SIZE, v.coerceIn(0, 2))

    /** Date line: 0 weekday + day + month, 1 short (dd.MM), 2 full. */
    fun dateFormat(ctx: Context): Int = i(ctx, KEY_DATE_FORMAT, 0).coerceIn(0, 2)
    fun setDateFormat(ctx: Context, v: Int) = setI(ctx, KEY_DATE_FORMAT, v.coerceIn(0, 2))

    /** Temperature unit: 0 Celsius, 1 Fahrenheit (display only). */
    fun tempUnit(ctx: Context): Int = i(ctx, KEY_TEMP_UNIT, 0).coerceIn(0, 1)
    fun setTempUnit(ctx: Context, v: Int) = setI(ctx, KEY_TEMP_UNIT, v.coerceIn(0, 1))

    /** Swipe notifications page (Telegram / Max) on the StandBy screen. */
    fun notifPageEnabled(ctx: Context): Boolean = b(ctx, KEY_NOTIF_PAGE, true)
    fun setNotifPageEnabled(ctx: Context, on: Boolean) = setB(ctx, KEY_NOTIF_PAGE, on)

    /** Max rows on the notifications page ("макс."). The UI offers exactly
     *  3/5/7/10, so a legacy value between the steps (e.g. 4) is snapped
     *  onto the nearest supported one — otherwise the spinner showed "3"
     *  while the pref silently kept 4 until the user touched it. */
    fun notifMax(ctx: Context): Int {
        val v = i(ctx, KEY_NOTIF_MAX, 5)
        return when {
            v <= 3 -> 3
            v <= 5 -> 5
            v <= 7 -> 7
            else -> 10
        }
    }
    fun setNotifMax(ctx: Context, v: Int) = setI(ctx, KEY_NOTIF_MAX, v.coerceIn(1, 10))

    fun lastWeatherUpdateMs(ctx: Context): Long = l(ctx, KEY_LAST_WEATHER_MS, 0L)
    fun setLastWeatherUpdateMs(ctx: Context, v: Long) = setL(ctx, KEY_LAST_WEATHER_MS, v)
    fun lastWeatherError(ctx: Context): String = s(ctx, KEY_LAST_WEATHER_ERR, "")
    fun setLastWeatherError(ctx: Context, v: String) = setS(ctx, KEY_LAST_WEATHER_ERR, v)
    fun lastStockUpdateMs(ctx: Context): Long = l(ctx, KEY_LAST_STOCK_MS, 0L)
    fun setLastStockUpdateMs(ctx: Context, v: Long) = setL(ctx, KEY_LAST_STOCK_MS, v)
    fun lastStockError(ctx: Context): String = s(ctx, KEY_LAST_STOCK_ERR, "")
    fun setLastStockError(ctx: Context, v: String) = setS(ctx, KEY_LAST_STOCK_ERR, v)

    /** Factory-reset every preference (the "reset settings" button). */
    fun reset(ctx: Context) {
        mem.clear()
        try {
            prefs(ctx).edit().clear().apply()
        } catch (_: Exception) {
            // best effort
        }
    }

    /** Clock brightness, 0..100. Default 100 (full). */
    fun brightness(ctx: Context): Int = i(ctx, KEY_BRIGHTNESS, 100).coerceIn(0, 100)
    fun setBrightness(ctx: Context, value: Int) = setI(ctx, KEY_BRIGHTNESS, value.coerceIn(0, 100))

    /** Auto-tune brightness to the ambient light sensor. Default on. */
    fun autoBrightness(ctx: Context): Boolean = b(ctx, KEY_AUTO_BRIGHTNESS, true)
    fun setAutoBrightness(ctx: Context, on: Boolean) = setB(ctx, KEY_AUTO_BRIGHTNESS, on)

    /** Clock face style: 0 normal, 1 outline, 2 dots, 3 flip, 4 LED, 5 neon,
     *  6 chips, 7 serif, 8 italic, 9 LED-matrix, 10 classic digital,
     *  11 bold digital, 12 monospaced, 13 soft rounded, 14 premium AMOLED,
     *  15 stacked iPhone, 16 analog iPhone, 17 float iPhone, 18 solar iPhone,
     *  19 world iPhone, 20 minimal mono.
     *  Default: premium (the new default look). */
    fun clockStyle(ctx: Context): Int = i(ctx, KEY_CLOCK_STYLE, 14).coerceIn(0, 20)
    fun setClockStyle(ctx: Context, v: Int) = setI(ctx, KEY_CLOCK_STYLE, v.coerceIn(0, 20))

    /** Line thickness (dp) for the outline clock, 1..30. */
    fun clockThickness(ctx: Context): Int = i(ctx, KEY_CLOCK_THICKNESS, 6).coerceIn(1, 30)
    fun setClockThickness(ctx: Context, v: Int) = setI(ctx, KEY_CLOCK_THICKNESS, v.coerceIn(1, 30))

    /** Clock accent color preset index, 0..7 (see clock_color_entries). */
    fun clockColor(ctx: Context): Int = i(ctx, KEY_CLOCK_COLOR, 0).coerceIn(0, 7)
    fun setClockColor(ctx: Context, v: Int) = setI(ctx, KEY_CLOCK_COLOR, v.coerceIn(0, 7))

    /** The ARGB accent color behind [clockColor]. Index 0 is plain white. */
    fun clockColorValue(ctx: Context): Int = when (clockColor(ctx)) {
        1 -> 0xFF3B82F6.toInt() // blue (Float-like)
        2 -> 0xFF7DD3FC.toInt() // sky
        3 -> 0xFF34D399.toInt() // green
        4 -> 0xFFFBBF24.toInt() // amber
        5 -> 0xFFFB923C.toInt() // orange
        6 -> 0xFFF87171.toInt() // red
        7 -> 0xFFA78BFA.toInt() // purple
        else -> 0xFFFFFFFF.toInt() // white
    }

    /** iPhone-style Night Mode: red tint of the standby screen in the dark.
     *  On by default, like on the iPhone. */
    fun nightMode(ctx: Context): Boolean = b(ctx, KEY_NIGHT_MODE, true)
    fun setNightMode(ctx: Context, on: Boolean) = setB(ctx, KEY_NIGHT_MODE, on)

    /** Calendar <-> stock chart alternation mode. */
    fun stocksEnabled(ctx: Context): Boolean = b(ctx, KEY_STOCKS_ENABLED, false)
    fun setStocksEnabled(ctx: Context, on: Boolean) = setB(ctx, KEY_STOCKS_ENABLED, on)

    /** Stocks settings are managed only through the [stockTickers] list API. */

    /** User-entered reference price the change percentage is computed from. */
    fun stockReference(ctx: Context): Double =
        if (has(ctx, KEY_STOCK_REF_D))
            java.lang.Double.longBitsToDouble(l(ctx, KEY_STOCK_REF_D, 0L))
        else f(ctx, KEY_STOCK_REF, 0f).toString().toDouble() // migration: exact "275.35"
    fun setStockReference(ctx: Context, v: Double) =
        setL(ctx, KEY_STOCK_REF_D, java.lang.Double.doubleToRawLongBits(v))

    /** Stock chart style: 0 = line, 1 = candles. Default line. */
    fun stockType(ctx: Context): Int = i(ctx, KEY_STOCK_TYPE, 0).coerceIn(0, 1)
    fun setStockType(ctx: Context, v: Int) = setI(ctx, KEY_STOCK_TYPE, v.coerceIn(0, 1))

    /** Chart period: 0 = auto-cycle (default); else a MOEX interval code. */
    fun stockPeriod(ctx: Context): Int = i(ctx, KEY_STOCK_PERIOD, 0)
    fun setStockPeriod(ctx: Context, v: Int) = setI(ctx, KEY_STOCK_PERIOD, v)

    /** First day of calendar week. 0 = locale/system, else Calendar.DAY_OF_WEEK. */
    fun weekStart(ctx: Context): Int = i(ctx, KEY_WEEK_START, 0)
    fun setWeekStart(ctx: Context, v: Int) = setI(ctx, KEY_WEEK_START, v)

    /**
     * Calendar style: 0 classic, 1 minimal, 2 filled today, 3 outlined today,
     * 4 weekend accent, 5 monochrome OLED, 6 compact, 7 large numbers,
     * 8 premium card (default), 9 iPhone style (red title, red today disc).
     */
    fun calendarStyle(ctx: Context): Int = i(ctx, KEY_CALENDAR_STYLE, 8).coerceIn(0, 9)
    fun setCalendarStyle(ctx: Context, v: Int) = setI(ctx, KEY_CALENDAR_STYLE, v.coerceIn(0, 9))

    /** User asked to auto-show StandBy while charging in landscape. */
    fun autoStandby(ctx: Context): Boolean = b(ctx, KEY_AUTO_STANDBY, false)
    fun setAutoStandby(ctx: Context, on: Boolean) = setB(ctx, KEY_AUTO_STANDBY, on)

    /** Weather panel on/off. */
    fun weatherEnabled(ctx: Context): Boolean = b(ctx, KEY_WEATHER_ENABLED, false)
    fun setWeatherEnabled(ctx: Context, on: Boolean) = setB(ctx, KEY_WEATHER_ENABLED, on)

    private fun latLon(ctx: Context): Pair<Double, Double>? {
        val lat = f(ctx, KEY_WEATHER_LAT, Float.NaN).toDouble()
        val lon = f(ctx, KEY_WEATHER_LON, Float.NaN).toDouble()
        if (lat.isNaN() || lon.isNaN()) return null
        if (lat == 0.0 && lon == 0.0) return null // (0,0) is a bogus "no fix"
        return lat to lon
    }

    /** Saved forecast point, if any (geolocation or manual city). */
    fun weatherLocation(ctx: Context): Pair<Double, Double>? = latLon(ctx)

    /** True when we have a usable forecast point. */
    fun hasWeatherLocation(ctx: Context): Boolean = latLon(ctx) != null

    fun setWeatherLocation(ctx: Context, lat: Double, lon: Double) {
        // Two puts, one editor would be nicer, but the helpers keep the cache
        // consistent; location saves are rare (user action only).
        setF(ctx, KEY_WEATHER_LAT, lat.toFloat())
        setF(ctx, KEY_WEATHER_LON, lon.toFloat())
    }

    /** Display name of the forecast location (empty when it came from GPS). */
    fun weatherCity(ctx: Context): String = s(ctx, KEY_WEATHER_CITY, "")
    fun setWeatherCity(ctx: Context, name: String) = setS(ctx, KEY_WEATHER_CITY, name.trim())

    /** Weather panel style: 0 classic, 1 curve, 2 minimal, 3 forecast strip,
     *  4 daily, 5 hero temperature, 6 monochrome, 7 weather+sun, 8 split,
     *  9 premium card (default). */
    fun weatherStyle(ctx: Context): Int = i(ctx, KEY_WEATHER_STYLE, 9).coerceIn(0, 9)
    fun setWeatherStyle(ctx: Context, v: Int) = setI(ctx, KEY_WEATHER_STYLE, v.coerceIn(0, 9))

    /** How long (seconds) each window type stays on screen. Default 10. */
    fun panelDurationCal(ctx: Context): Int = i(ctx, KEY_DUR_CAL, 10).coerceIn(5, 120)
    fun setPanelDurationCal(ctx: Context, v: Int) = setI(ctx, KEY_DUR_CAL, v.coerceIn(5, 120))

    fun panelDurationStock(ctx: Context): Int = i(ctx, KEY_DUR_STOCK, 10).coerceIn(5, 120)
    fun setPanelDurationStock(ctx: Context, v: Int) = setI(ctx, KEY_DUR_STOCK, v.coerceIn(5, 120))

    fun panelDurationWeather(ctx: Context): Int = i(ctx, KEY_DUR_WEATHER, 10).coerceIn(5, 120)
    fun setPanelDurationWeather(ctx: Context, v: Int) = setI(ctx, KEY_DUR_WEATHER, v.coerceIn(5, 120))

    /**
     * MOEX watchlist, up to 3 tickers (uppercased, de-duplicated). Falls back
     * to the legacy single-ticker field so existing installs keep working.
     */
    fun stockTickers(ctx: Context): List<String> {
        val raw = s(ctx, KEY_STOCK_TICKERS, "").ifBlank { s(ctx, KEY_STOCK_TICKER, "") }
        return stockTickersFromRaw(raw)
    }

    fun setStockTickers(ctx: Context, raw: String) {
        val cleaned = stockTickersFromRaw(raw).joinToString(",")
        setS(ctx, KEY_STOCK_TICKERS, cleaned)
    }

    private fun stockTickersFromRaw(raw: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (part in raw.split(',', ' ', ';')) {
            val t = part.trim().uppercase()
            if (t.isNotEmpty()) seen.add(t)
            if (seen.size >= 3) break
        }
        return seen.toList()
    }

    /** Auto-standby schedule: 0 = always, 1 = custom hours. */
    fun standbySchedule(ctx: Context): Int = i(ctx, KEY_STANDBY_SCHEDULE, 0).coerceIn(0, 1)
    fun setStandbySchedule(ctx: Context, v: Int) = setI(ctx, KEY_STANDBY_SCHEDULE, v.coerceIn(0, 1))

    fun standbyFromHour(ctx: Context): Int = i(ctx, KEY_STANDBY_FROM_HOUR, 22).coerceIn(0, 23)
    fun setStandbyFromHour(ctx: Context, v: Int) = setI(ctx, KEY_STANDBY_FROM_HOUR, v.coerceIn(0, 23))

    fun standbyToHour(ctx: Context): Int = i(ctx, KEY_STANDBY_TO_HOUR, 8).coerceIn(0, 23)
    fun setStandbyToHour(ctx: Context, v: Int) = setI(ctx, KEY_STANDBY_TO_HOUR, v.coerceIn(0, 23))

    /**
     * Whether the auto-standby is allowed to show right now, per schedule.
     * A from>to range wraps overnight (22:00 -> 08:00). from == to means
     * "all day" (the range would otherwise be empty).
     */
    fun isStandbyTimeAllowed(ctx: Context, cal: java.util.Calendar): Boolean {
        if (standbySchedule(ctx) != 1) return true
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val from = standbyFromHour(ctx)
        val to = standbyToHour(ctx)
        if (from == to) return true
        return if (from < to) h in from until to else h >= from || h < to
    }

    /**
     * OLED protection: the desk clock can sit on a stand for hours, so cap
     * the white level at ~85% (0.85 content alpha over the pure-black
     * background) instead of burning full-bright pixels.
     */
    fun dimMode(ctx: Context): Boolean = b(ctx, KEY_DIM_MODE, true)
    fun setDimMode(ctx: Context, v: Boolean) = setB(ctx, KEY_DIM_MODE, v)
}
