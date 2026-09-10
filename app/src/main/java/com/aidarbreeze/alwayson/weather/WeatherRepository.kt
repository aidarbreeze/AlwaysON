package com.aidarbreeze.alwayson.weather

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The single safe weather cache + fetch coordinator, shared by the StandBy
 * overlay, the screen saver, the settings mini-preview and (via the
 * [WeatherSharedCache] snapshot) the home-screen widget.
 *
 * Guarantees (per the stability brief):
 *  - ONE in-flight network request per location — panel switches never fire
 *    parallel or repeated fetches (a 30-min TTL absorbs them);
 *  - the last SUCCESSFUL forecast is kept (in memory + on disk) and shown with
 *    a "stale" mark when a refresh fails — never replaced by a blank screen;
 *  - a changed city / coordinates only replaces the cache once the NEW
 *    forecast is confirmed — the previous city is never rendered under the
 *    new name;
 *  - every result is delivered on the main thread.
 */
object WeatherRepository {

    /** One refresh outcome. */
    class Result(
        val info: WeatherInfo?, // data to show (a live fetch or the last-known one)
        val fresh: Boolean,     // true when it came from a live (or still-fresh cache) fetch
        val stale: Boolean,     // true when showing last-known after a failed refresh
        val offline: Boolean    // true when there is no usable network
    )

    private const val FILE = "alwayson_weather_cache"
    private const val KEY_FULL = "full"

    // 30 min is fresh enough for a forecast and stops per-panel re-fetching.
    private const val TTL_MS = 30L * 60L * 1000L

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "alwayson-weather")
    }
    private val lock = Any()
    private var inFlightKey: String? = null
    private var lastGood: WeatherInfo? = null
    private var lastGoodKey: String? = null
    private var lastGoodTs: Long = 0L
    private var diskLoaded = false

    private fun keyOf(lat: Double, lon: Double): String = "$lat|$lon"

    /**
     * Make sure we have a forecast for (lat,lon) and deliver the best
     * available result to [onResult] (always on the main thread). When a fresh
     * forecast is already cached and [force] is false, it is returned
     * immediately with no network. Otherwise a single deduplicated background
     * fetch is started; on failure the last-known forecast for the SAME
     * location (if any) is handed back marked as stale.
     */
    fun get(
        ctx: Context,
        lat: Double,
        lon: Double,
        city: String,
        force: Boolean,
        onResult: (Result) -> Unit
    ) {
        val c = ctx.applicationContext
        val key = keyOf(lat, lon)
        ensureLoaded(c)

        val cached = synchronized(lock) {
            if (lastGoodKey == key) lastGood else null
        }
        if (cached != null && !force &&
            System.currentTimeMillis() - lastGoodTs < TTL_MS
        ) {
            onResult(Result(cached, fresh = true, stale = false, offline = false))
            return
        }

        val shouldFetch = synchronized(lock) {
            if (inFlightKey == key) false
            else {
                inFlightKey = key
                true
            }
        }
        if (!shouldFetch) {
            // An identical fetch is already running; deliver what we have now.
            val cur = synchronized(lock) { if (lastGoodKey == key) lastGood else null }
            onResult(Result(cur, fresh = false, stale = cur != null, offline = !isOnline(c)))
            return
        }

        executor.execute {
            val offline = !isOnline(c)
            val data: WeatherInfo? = if (offline) null else WeatherApi.fetch(lat, lon, city)
            val result = if (data != null) {
                synchronized(lock) {
                    lastGood = data
                    lastGoodKey = key
                    lastGoodTs = System.currentTimeMillis()
                }
                persistFull(c, data, lat, lon)
                WeatherSharedCache.save(c, data) // snapshot for the widget
                Result(data, fresh = true, stale = false, offline = false)
            } else {
                val cur = synchronized(lock) { if (lastGoodKey == key) lastGood else null }
                Result(cur, fresh = false, stale = cur != null, offline = offline)
            }
            synchronized(lock) { inFlightKey = null }
            main.post { onResult(result) }
        }
    }

    private fun ensureLoaded(c: Context) {
        if (diskLoaded) return
        synchronized(lock) {
            if (diskLoaded) return
            try {
                val raw = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getString(KEY_FULL, null)
                if (raw != null) {
                    val j = JSONObject(raw)
                    val info = parseFull(j)
                    if (info != null) {
                        val lat = j.optDouble("lat", Double.NaN)
                        val lon = j.optDouble("lon", Double.NaN)
                        lastGood = info
                        lastGoodKey = keyOf(lat, lon)
                        lastGoodTs = j.optLong("ts", 0L)
                    }
                }
            } catch (_: Exception) {
                // corrupt/absent cache — start clean
            }
            diskLoaded = true
        }
    }

    private fun isOnline(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val net = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(net) ?: return true
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    // ---------- full-forecast persistence (survives process death) ----------

    private fun persistFull(c: Context, info: WeatherInfo, lat: Double, lon: Double) {
        try {
            val j = JSONObject()
            j.put("city", info.city)
            j.put("temp", info.tempNowC)
            j.put("code", info.codeNow)
            j.put("feels", info.feelsNowC)
            j.put("sunrise", info.sunriseMs)
            j.put("sunset", info.sunsetMs)
            j.put("lat", lat)
            j.put("lon", lon)
            j.put("ts", System.currentTimeMillis())
            val hours = JSONArray()
            for (h in info.hours) hours.put(arrayOf<Any>(h.timeMs, h.tempC, h.code))
            j.put("hours", hours)
            val days = JSONArray()
            for (d in info.days) days.put(arrayOf<Any>(d.timeMs, d.code, d.tMin, d.tMax))
            j.put("days", days)
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY_FULL, j.toString()).apply()
            synchronized(lock) {
                lastGood = info
                lastGoodKey = keyOf(lat, lon)
                lastGoodTs = System.currentTimeMillis()
            }
        } catch (_: Exception) {
            // cache is best-effort
        }
    }

    private fun parseFull(j: JSONObject): WeatherInfo? {
        val city = j.optString("city")
        val temp = j.optInt("temp")
        val code = j.optInt("code")
        val feels = j.optInt("feels")
        val sunrise = j.optLong("sunrise")
        val sunset = j.optLong("sunset")
        val hoursArr = j.optJSONArray("hours")
        val hours = ArrayList<WeatherHour>()
        if (hoursArr != null) {
            for (i in 0 until hoursArr.length()) {
                val a = hoursArr.optJSONArray(i) ?: continue
                if (a.length() < 3) continue
                hours.add(WeatherHour(a.getLong(0), a.getInt(1), a.getInt(2)))
            }
        }
        val daysArr = j.optJSONArray("days")
        val days = ArrayList<WeatherDay>()
        if (daysArr != null) {
            for (i in 0 until daysArr.length()) {
                val a = daysArr.optJSONArray(i) ?: continue
                if (a.length() < 4) continue
                days.add(WeatherDay(a.getLong(0), a.getInt(1), a.getInt(2), a.getInt(3)))
            }
        }
        if (hours.isEmpty() && days.isEmpty()) return null
        return WeatherInfo(city, temp, code, hours, days, feels, sunrise, sunset)
    }
}
