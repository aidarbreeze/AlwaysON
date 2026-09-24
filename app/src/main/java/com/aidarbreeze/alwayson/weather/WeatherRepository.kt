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

    // Rate limiting: minimum interval between weather API calls (15 minutes)
    private const val RATE_LIMIT_MS = 15L * 60L * 1000L
    // 30 min is fresh enough for a forecast and stops per-panel re-fetching.
    private const val TTL_MS = 30L * 60L * 1000L
    
    // Input validation: valid latitude/longitude ranges
    private const val MIN_LAT = -90.0
    private const val MAX_LAT = 90.0
    private const val MIN_LON = -180.0
    private const val MAX_LON = 180.0

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "alwayson-weather")
    }
    private val lock = Any()
    private var inFlightKey: String? = null
    private var lastGood: WeatherInfo? = null
    private var lastGoodKey: String? = null
    private var lastGoodTs: Long = 0L
    // For rate limiting, per location key (a global timestamp would block a
    // fetch for a DIFFERENT city for 15 min after any successful fetch).
    private val lastFetchByKey = HashMap<String, Long>()
    // Callbacks that joined an already in-flight fetch for a key; they get
    // the same result as the initiator (see [finish]) instead of a useless
    // immediate "no data".
    private val waiters = HashMap<String, MutableList<(Result) -> Unit>>()
    private var diskLoaded = false

    private fun keyOf(lat: Double, lon: Double): String = "$lat|$lon"
    
    /** Validate that coordinates are within valid ranges */
    private fun isValidCoordinates(lat: Double, lon: Double): Boolean {
        return lat in MIN_LAT..MAX_LAT && lon in MIN_LON..MAX_LON
    }

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

        // A failed geocode leaves lat/lon as NaN — never hit the network with
        // that (guaranteed 400 + pointless traffic); show last-known instead.
        if (lat.isNaN() || lon.isNaN()) {
            val cur = synchronized(lock) { lastGood }
            deliver(Result(cur, fresh = false, stale = cur != null, offline = false), onResult)
            return
        }
        
        // Security: Validate coordinates are within valid ranges
        if (!isValidCoordinates(lat, lon)) {
            val cur = synchronized(lock) { lastGood }
            deliver(Result(cur, fresh = false, stale = cur != null, offline = false), onResult)
            return
        }

        val key = keyOf(lat, lon)
        ensureLoaded(c)

        val cached = synchronized(lock) {
            if (lastGoodKey == key) lastGood else null
        }
        if (cached != null && !force && isFresh(synchronized(lock) { lastGoodTs })) {
            deliver(Result(cached, fresh = true, stale = false, offline = false), onResult)
            return
        }

        // Rate limiting: check if enough time has passed since the last fetch
        // for THIS location.
        val now = System.currentTimeMillis()
        var joined = false
        val shouldFetch = synchronized(lock) {
            when {
                inFlightKey == key -> {
                    // An identical fetch is already running: join it and get
                    // the real result when it lands (not a blank placeholder).
                    waiters.getOrPut(key) { ArrayList() }.add(onResult)
                    joined = true
                    false
                }
                now - (lastFetchByKey[key] ?: 0L) < RATE_LIMIT_MS && !force -> {
                    // Rate limit enforced - not enough time since last fetch
                    false
                }
                else -> {
                    inFlightKey = key
                    true
                }
            }
        }
        if (joined) return   // the result arrives via [finish] with the fetch
        if (!shouldFetch) {
            // Rate limit active; deliver what we have now.
            val cur = synchronized(lock) { if (lastGoodKey == key) lastGood else null }
            deliver(Result(cur, fresh = false, stale = cur != null, offline = !isOnline(c)), onResult)
            return
        }

        executor.execute {
            try {
                val offline = !isOnline(c)
                val data: WeatherInfo? = if (offline) null else WeatherApi.fetch(lat, lon, city)
                val result = if (data != null) {
                    synchronized(lock) {
                        lastGood = data
                        lastGoodKey = key
                        lastGoodTs = System.currentTimeMillis()
                        lastFetchByKey[key] = System.currentTimeMillis()
                    }
                    persistFull(c, data, lat, lon)
                    WeatherSharedCache.save(c, data) // snapshot for the widget
                    Result(data, fresh = true, stale = false, offline = false)
                } else {
                    val cur = synchronized(lock) { if (lastGoodKey == key) lastGood else null }
                    Result(cur, fresh = false, stale = cur != null, offline = offline)
                }
                finish(key, result, onResult)
            } catch (_: Throwable) {
                // Never let one bad fetch wedge the single worker: hand back
                // the last-known forecast for this location (if any).
                val cur = synchronized(lock) { if (lastGoodKey == key) lastGood else null }
                finish(key, Result(cur, fresh = false, stale = cur != null, offline = true), onResult)
            }
        }
    }

    /**
     * Deliver [result] to the initiating callback [first] AND to every
     * callback that joined the in-flight fetch for [key], always on the main
     * thread. The in-flight marker is released inside the same lock section
     * that detaches the waiters: a get() arriving afterwards can then start a
     * NEW fetch instead of silently joining one that is about to end (which
     * would leave it without a callback).
     */
    private fun finish(key: String, result: Result, first: (Result) -> Unit) {
        val others = synchronized(lock) {
            if (inFlightKey == key) inFlightKey = null
            waiters.remove(key).orEmpty()
        }
        // Each callback in its own post: an exception thrown by one consumer
        // must not rob the others (and the initiator) of their result.
        main.post { first(result) }
        others.forEach { cb -> main.post { cb(result) } }
    }

    /**
     * Sunrise/sunset (ms) plus the IANA zone of the forecast point, or null
     * when no cached forecast exists yet. The zone matters: the instants are
     * absolute epoch ms, but they describe ANOTHER city's day — labels must
     * be formatted in ITS zone (the solar clock face used to format them in
     * the device zone and showed shifted times for any city outside it).
     * Cheap: memory + a single disk load max, no network — safe from a view.
     */
    class SunTimes(val riseMs: Long, val setMs: Long, val timezoneId: String)

    fun sunTimesFull(ctx: Context): SunTimes? {
        ensureLoaded(ctx.applicationContext)
        val info = synchronized(lock) { lastGood } ?: return null
        if (info.sunriseMs <= 0L || info.sunsetMs <= 0L) return null
        return SunTimes(info.sunriseMs, info.sunsetMs, info.timezoneId)
    }

    fun sunTimes(ctx: Context): Pair<Long, Long>? {
        val st = sunTimesFull(ctx) ?: return null
        return st.riseMs to st.setMs
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

    /** Always hand the result back on the main thread: `get()` can be called
     *  from the UI thread (cache hit — already main) or from background
     *  threads (the two synchronous paths below), and consumers assume a
     *  single delivery thread. */
    private fun deliver(result: Result, onResult: (Result) -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) onResult(result)
        else main.post { onResult(result) }
    }

    /** Fresh = a positive timestamp AND age inside [0, TTL). The ts>0 guard
     *  catches a corrupted/absent "ts", and the lower bound catches system
     *  clocks that were rolled backwards (negative age is not "extra fresh"). */
    private fun isFresh(ts: Long): Boolean {
        if (ts <= 0L) return false
        val age = System.currentTimeMillis() - ts
        return age in 0L until TTL_MS
    }

    private fun isOnline(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            // No active network at all = airplane mode / Wi-Fi off. The old
            // `?: return true` treated that as "online" and burned a doomed
            // request before falling back. (Not requiring
            // NET_CAPABILITY_VALIDATED on purpose: some healthy setups never
            // set it, and a failed fetch degrades gracefully anyway.)
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
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
            // 0° is a real "feels like" value, so presence is the signal:
            // only write the key when the API actually sent it.
            if (info.feelsNowC != null) j.put("feels", info.feelsNowC)
            j.put("sunrise", info.sunriseMs)
            j.put("sunset", info.sunsetMs)
            j.put("tz", info.timezoneId)
            j.put("lat", lat)
            j.put("lon", lon)
            j.put("ts", System.currentTimeMillis())
            // Build the arrays with explicit puts. `put(arrayOf<Any>(...))`
            // boxes the Kotlin Array as a generic Object, which org.json
            // serializes with Array.toString() ("[Ljava.lang.Object;@…") —
            // silently corrupting the on-disk cache on every save.
            val hours = JSONArray()
            for (h in info.hours) {
                val a = JSONArray()
                a.put(h.timeMs)
                a.put(h.tempC)
                a.put(h.code)
                hours.put(a)
            }
            j.put("hours", hours)
            val days = JSONArray()
            for (d in info.days) {
                val a = JSONArray()
                a.put(d.timeMs)
                a.put(d.code)
                a.put(d.tMin)
                a.put(d.tMax)
                days.put(a)
            }
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
        // Absent key = not provided (null); optInt would read it as 0, which
        // is a real temperature and would render as "feels like 0°".
        val feels: Int? = if (j.has("feels")) j.optInt("feels") else null
        val sunrise = j.optLong("sunrise")
        val sunset = j.optLong("sunset")
        val tz = j.optString("tz", "")
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
        return WeatherInfo(city, temp, code, hours, days, feels, sunrise, sunset, tz)
    }
}
