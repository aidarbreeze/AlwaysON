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
}
