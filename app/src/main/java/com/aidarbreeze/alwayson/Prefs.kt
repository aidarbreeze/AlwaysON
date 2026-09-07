package com.aidarbreeze.alwayson

import android.content.Context

/** Thin wrapper around the app SharedPreferences. */
object Prefs {
    private const val FILE = "alwayson_prefs"

    private const val KEY_OVERLAY_ACTIVE = "overlay_active"
    private const val KEY_OVERLAY_ALPHA = "overlay_alpha"   // 0..1
    private const val KEY_USE_24H = "use_24h"
    private const val KEY_SHOW_SECONDS = "show_seconds"
    private const val KEY_SHOW_BATTERY = "show_battery"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isOverlayActive(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_OVERLAY_ACTIVE, false)
    fun setOverlayActive(ctx: Context, active: Boolean) =
        sp(ctx).edit().putBoolean(KEY_OVERLAY_ACTIVE, active).apply()

    /** Default overlay visibility: 60%. */
    fun overlayAlpha(ctx: Context): Float =
        sp(ctx).getFloat(KEY_OVERLAY_ALPHA, 0.6f).coerceIn(0.1f, 1f)
    fun setOverlayAlpha(ctx: Context, alpha: Float) =
        sp(ctx).edit().putFloat(KEY_OVERLAY_ALPHA, alpha.coerceIn(0.1f, 1f)).apply()

    /** null = follow the system locale (12/24). */
    fun force24h(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_USE_24H, false)
    fun setForce24h(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_USE_24H, v).apply()

    fun showSeconds(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHOW_SECONDS, false)
    fun setShowSeconds(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_SHOW_SECONDS, v).apply()

    fun showBattery(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHOW_BATTERY, false)
    fun setShowBattery(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_SHOW_BATTERY, v).apply()
}
