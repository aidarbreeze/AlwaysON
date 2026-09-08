package com.aidarbreeze.alwayson

import android.content.Context

/** Thin wrapper around the app SharedPreferences. */
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
    // Calendar <-> stock chart alternation mode.
    private const val KEY_STOCKS_ENABLED = "stocks_enabled"
    private const val KEY_STOCK_TICKER = "stock_ticker"
    private const val KEY_STOCK_REF = "stock_reference"
    // Stock chart rendering: 0 = line of closes, 1 = candlesticks.
    private const val KEY_STOCK_TYPE = "stock_type"
    // Chart period/timeframe: 0 = auto-cycle, otherwise a MOEX interval code
    // (1, 10 or 60) to keep showing.
    private const val KEY_STOCK_PERIOD = "stock_period"
    // First day of the calendar week. 0 = follow the locale/system; otherwise
    // a Calendar.DAY_OF_WEEK constant (1 = Sunday, 2 = Monday, 7 = Saturday).
    private const val KEY_WEEK_START = "week_start"
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
    private const val KEY_STANDBY_SUPPRESSED = "standby_suppressed"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun force24h(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_USE_24H, false)
    fun setForce24h(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_USE_24H, v).apply()

    fun showSeconds(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHOW_SECONDS, false)
    fun setShowSeconds(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_SHOW_SECONDS, v).apply()

    fun showBattery(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHOW_BATTERY, false)
    fun setShowBattery(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_SHOW_BATTERY, v).apply()

    /** Clock brightness, 0..100. Default 100 (full). */
    fun brightness(ctx: Context): Int =
        sp(ctx).getInt(KEY_BRIGHTNESS, 100).coerceIn(0, 100)
    fun setBrightness(ctx: Context, value: Int) =
        sp(ctx).edit().putInt(KEY_BRIGHTNESS, value.coerceIn(0, 100)).apply()

    /** Auto-tune brightness to the ambient light sensor. Default on. */
    fun autoBrightness(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_AUTO_BRIGHTNESS, true)
    fun setAutoBrightness(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_AUTO_BRIGHTNESS, on).apply()

    /** Clock face style: 0 normal, 1 outline, 2 dots, 3 flip, 4 LED, 5 neon,
     *  6 chips, 7 serif, 8 italic, 9 LED-matrix. */
    fun clockStyle(ctx: Context): Int = sp(ctx).getInt(KEY_CLOCK_STYLE, 0)
    fun setClockStyle(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_CLOCK_STYLE, v).apply()

    /** Line thickness (dp) for the outline clock, 1..30. */
    fun clockThickness(ctx: Context): Int =
        sp(ctx).getInt(KEY_CLOCK_THICKNESS, 6).coerceIn(1, 30)
    fun setClockThickness(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_CLOCK_THICKNESS, v.coerceIn(1, 30)).apply()

    /** Calendar <-> stock chart alternation mode. */
    fun stocksEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_STOCKS_ENABLED, false)
    fun setStocksEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_STOCKS_ENABLED, on).apply()

    /** Stock ticker symbol, e.g. "AAPL" or "SBER.ME". */
    fun stockTicker(ctx: Context): String =
        (sp(ctx).getString(KEY_STOCK_TICKER, null) ?: "").trim().uppercase()
    fun setStockTicker(ctx: Context, v: String) =
        sp(ctx).edit().putString(KEY_STOCK_TICKER, v.trim().uppercase()).apply()

    /** User-entered reference price the change percentage is computed from. */
    fun stockReference(ctx: Context): Double =
        sp(ctx).getFloat(KEY_STOCK_REF, 0f).toDouble()
    fun setStockReference(ctx: Context, v: Double) =
        sp(ctx).edit().putFloat(KEY_STOCK_REF, v.toFloat()).apply()

    /** Stock chart style: 0 = line, 1 = candles. Default line. */
    fun stockType(ctx: Context): Int = sp(ctx).getInt(KEY_STOCK_TYPE, 0)
    fun setStockType(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_STOCK_TYPE, v.coerceIn(0, 1)).apply()

    /** Chart period: 0 = auto-cycle (default); else a MOEX interval code. */
    fun stockPeriod(ctx: Context): Int = sp(ctx).getInt(KEY_STOCK_PERIOD, 0)
    fun setStockPeriod(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_STOCK_PERIOD, v).apply()

    /** First day of calendar week. 0 = locale/system, else Calendar.DAY_OF_WEEK. */
    fun weekStart(ctx: Context): Int = sp(ctx).getInt(KEY_WEEK_START, 0)
    fun setWeekStart(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_WEEK_START, v).apply()

    /** User asked to auto-show StandBy while charging in landscape. */
    fun autoStandby(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_STANDBY, false)
    fun setAutoStandby(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_AUTO_STANDBY, on).apply()

    /** Weather panel on/off. */
    fun weatherEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_WEATHER_ENABLED, false)
    fun setWeatherEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_WEATHER_ENABLED, on).apply()

    private fun latLon(ctx: Context): Pair<Double, Double>? {
        val lat = sp(ctx).getFloat(KEY_WEATHER_LAT, Float.NaN).toDouble()
        val lon = sp(ctx).getFloat(KEY_WEATHER_LON, Float.NaN).toDouble()
        if (lat.isNaN() || lon.isNaN()) return null
        if (lat == 0.0 && lon == 0.0) return null // (0,0) is a bogus "no fix"
        return lat to lon
    }

    /** Saved forecast point, if any (geolocation or manual city). */
    fun weatherLocation(ctx: Context): Pair<Double, Double>? = latLon(ctx)

    /** True when we have a usable forecast point. */
    fun hasWeatherLocation(ctx: Context): Boolean = latLon(ctx) != null

    fun setWeatherLocation(ctx: Context, lat: Double, lon: Double) {
        sp(ctx).edit()
            .putFloat(KEY_WEATHER_LAT, lat.toFloat())
            .putFloat(KEY_WEATHER_LON, lon.toFloat())
            .apply()
    }

    /** Display name of the forecast location (empty when it came from GPS). */
    fun weatherCity(ctx: Context): String =
        sp(ctx).getString(KEY_WEATHER_CITY, null) ?: ""
    fun setWeatherCity(ctx: Context, name: String) =
        sp(ctx).edit().putString(KEY_WEATHER_CITY, name.trim()).apply()

    /** Weather panel style: 0 = classic (default), 1 = curve. */
    fun weatherStyle(ctx: Context): Int = sp(ctx).getInt(KEY_WEATHER_STYLE, 0)
    fun setWeatherStyle(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_WEATHER_STYLE, v.coerceIn(0, 1)).apply()

    /** How long (seconds) each window type stays on screen. Default 10. */
    fun panelDurationCal(ctx: Context): Int =
        sp(ctx).getInt(KEY_DUR_CAL, 10).coerceIn(5, 120)
    fun setPanelDurationCal(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_DUR_CAL, v.coerceIn(5, 120)).apply()

    fun panelDurationStock(ctx: Context): Int =
        sp(ctx).getInt(KEY_DUR_STOCK, 10).coerceIn(5, 120)
    fun setPanelDurationStock(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_DUR_STOCK, v.coerceIn(5, 120)).apply()

    fun panelDurationWeather(ctx: Context): Int =
        sp(ctx).getInt(KEY_DUR_WEATHER, 10).coerceIn(5, 120)
    fun setPanelDurationWeather(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_DUR_WEATHER, v.coerceIn(5, 120)).apply()

    /**
     * MOEX watchlist, up to 3 tickers (uppercased, de-duplicated). Falls back
     * to the legacy single-ticker field so existing installs keep working.
     */
    fun stockTickers(ctx: Context): List<String> {
        val raw = sp(ctx).getString(KEY_STOCK_TICKERS, null)
            ?.ifBlank { sp(ctx).getString(KEY_STOCK_TICKER, null) }
            ?: ""
        val seen = LinkedHashSet<String>()
        for (part in raw.split(',', ' ', ';')) {
            val t = part.trim().uppercase()
            if (t.isNotEmpty()) seen.add(t)
            if (seen.size >= 3) break
        }
        return seen.toList()
    }

    fun setStockTickers(ctx: Context, raw: String) {
        val cleaned = stockTickersFromRaw(raw).joinToString(",")
        sp(ctx).edit().putString(KEY_STOCK_TICKERS, cleaned).apply()
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
    fun standbySchedule(ctx: Context): Int = sp(ctx).getInt(KEY_STANDBY_SCHEDULE, 0)
    fun setStandbySchedule(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_STANDBY_SCHEDULE, v.coerceIn(0, 1)).apply()

    fun standbyFromHour(ctx: Context): Int =
        sp(ctx).getInt(KEY_STANDBY_FROM_HOUR, 22).coerceIn(0, 23)
    fun setStandbyFromHour(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_STANDBY_FROM_HOUR, v.coerceIn(0, 23)).apply()

    fun standbyToHour(ctx: Context): Int =
        sp(ctx).getInt(KEY_STANDBY_TO_HOUR, 8).coerceIn(0, 23)
    fun setStandbyToHour(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_STANDBY_TO_HOUR, v.coerceIn(0, 23)).apply()

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

    /** The user dismissed the auto overlay (persisted across process death). */
    fun standbySuppressed(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_STANDBY_SUPPRESSED, false)
    fun setStandbySuppressed(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_STANDBY_SUPPRESSED, v).apply()
}
