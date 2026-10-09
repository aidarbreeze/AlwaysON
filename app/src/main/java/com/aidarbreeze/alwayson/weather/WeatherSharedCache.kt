package com.aidarbreeze.alwayson.weather

import android.content.Context
import org.json.JSONObject

/**
 * A tiny last-known-weather snapshot persisted in SharedPreferences so the
 * home-screen widget (which must not do its own networking) can show the same
 * city/temperature as the StandBy panel. Written by whoever fetched the
 * forecast (the StandBy controller or the settings preview), read by the
 * widget.
 */
object WeatherSharedCache {

    private const val FILE = "alwayson_weather_cache"
    private const val KEY = "last"

    /** Persist the current-conditions part of a fresh forecast. */
    fun save(ctx: Context, info: WeatherInfo) {
        try {
            val j = JSONObject()
            j.put("city", info.city)
            j.put("temp", info.tempNowC)
            j.put("code", info.codeNow)
            j.put("ts", System.currentTimeMillis())
            ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY, j.toString()).apply()
        } catch (_: Exception) {
            // cache is best-effort
        }
    }

    /** The last saved snapshot, or null when missing/too old (> [maxAgeMs]). */
    fun load(ctx: Context, maxAgeMs: Long = 90L * 60L * 1000L): Snapshot? {
        return try {
            val raw = ctx.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return null
            val j = JSONObject(raw)
            val ts = j.optLong("ts", 0L)
            if (ts <= 0L || System.currentTimeMillis() - ts > maxAgeMs) null
            else Snapshot(
                city = j.optString("city"),
                tempNowC = j.optInt("temp"),
                codeNow = j.optInt("code")
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Just the fields the widget needs. */
    data class Snapshot(val city: String, val tempNowC: Int, val codeNow: Int)
}
