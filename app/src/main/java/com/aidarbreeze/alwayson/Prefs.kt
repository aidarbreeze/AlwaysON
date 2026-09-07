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

    /** User asked to auto-show StandBy while charging in landscape. */
    fun autoStandby(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_STANDBY, false)
    fun setAutoStandby(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_AUTO_STANDBY, on).apply()
}
