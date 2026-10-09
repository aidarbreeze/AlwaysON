package com.aidarbreeze.alwayson.weather

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Network-independent normalized weather model used between providers and the
 * app's existing WeatherRepository/WeatherInfo model.
 *
 * No third-party HTTP/JSON dependency is required: Android's HttpURLConnection
 * and org.json are enough.
 */
data class NormalizedWeather(
    val city: String,
    val timezoneId: String,
    val tempNowC: Int,
    val feelsNowC: Int?,
    val codeNow: Int,
    val hours: List<NormalizedHour>,
    val days: List<NormalizedDay>,
    val sunriseMs: Long = 0L,
    val sunsetMs: Long = 0L,
    val fetchedAtMs: Long = System.currentTimeMillis()
)

data class NormalizedHour(
    val timeMs: Long,
    val tempC: Int,
    val code: Int
)

data class NormalizedDay(
    val timeMs: Long,
    val tMin: Int,
    val tMax: Int,
    val code: Int
)

data class WeatherRequest(
    val latitude: Double,
    val longitude: Double,
    val city: String = "",
    /** Use the forecast-location timezone when known; otherwise device timezone. */
    val timezoneHint: String = TimeZone.getDefault().id
)

enum class WeatherProviderId(val label: String) {
    OPEN_METEO("Open-Meteo"),
    MET_NORWAY("MET Norway"),
    CACHE("cache")
}

data class WeatherFetchResult(
    val weather: NormalizedWeather,
    val provider: WeatherProviderId,
    val fallback: Boolean,
    val stale: Boolean,
    val primaryError: String? = null,
    val backupError: String? = null
)

interface WeatherSource {
    val id: WeatherProviderId

    /** Blocking call. WeatherFailoverService.fetch() must run off the main thread. */
    @Throws(Exception::class)
    fun load(request: WeatherRequest): NormalizedWeather
}

/**
 * Primary provider. Mirrors the fields already consumed by WeatherPanelView:
 * current temperature / apparent temperature / WMO weather code, hourly values,
 * daily min/max/code and sunrise/sunset.
 */
class OpenMeteoWeatherSource(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 7_000
) : WeatherSource {

    override val id: WeatherProviderId = WeatherProviderId.OPEN_METEO

    override fun load(request: WeatherRequest): NormalizedWeather {
        val lat = coord(request.latitude)
        val lon = coord(request.longitude)
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=").append(lat)
            append("&longitude=").append(lon)
            append("&current=temperature_2m,apparent_temperature,weather_code")
            append("&hourly=temperature_2m,weather_code")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset")
            append("&timezone=auto")
            append("&forecast_days=8")
        }

        val root = JSONObject(httpGet(url, null, connectTimeoutMs, readTimeoutMs))
        val tzId = root.optString("timezone").takeIf { it.isNotBlank() }
            ?: request.timezoneHint
        val tz = safeTimeZone(tzId)

        val current = root.getJSONObject("current")
        val tempNow = current.getDouble("temperature_2m").roundToInt()
        val feelsNow = if (current.has("apparent_temperature") &&
            !current.isNull("apparent_temperature")
        ) current.getDouble("apparent_temperature").roundToInt() else null
        val codeNow = current.optInt("weather_code", 0)

        val hourly = root.optJSONObject("hourly")
        val hours = if (hourly != null) parseOpenMeteoHours(hourly, tz) else emptyList()

        val daily = root.optJSONObject("daily")
        val parsedDaily = if (daily != null) parseOpenMeteoDays(daily, tz) else emptyList()
        val rise = daily?.optJSONArray("sunrise")?.let { firstIsoMs(it, tz) } ?: 0L
        val set = daily?.optJSONArray("sunset")?.let { firstIsoMs(it, tz) } ?: 0L

        require(hours.isNotEmpty() || parsedDaily.isNotEmpty()) {
            "Open-Meteo returned no forecast rows"
        }

        return NormalizedWeather(
            city = request.city,
            timezoneId = tz.id,
            tempNowC = tempNow,
            feelsNowC = feelsNow,
            codeNow = codeNow,
            hours = hours,
            days = parsedDaily,
            sunriseMs = rise,
            sunsetMs = set
        )
    }

    private fun parseOpenMeteoHours(o: JSONObject, tz: TimeZone): List<NormalizedHour> {
        val times = o.optJSONArray("time") ?: return emptyList()
        val temps = o.optJSONArray("temperature_2m") ?: return emptyList()
        val codes = o.optJSONArray("weather_code") ?: JSONArray()
        val n = minOf(times.length(), temps.length(), 96) // four days is enough for the UI
        val out = ArrayList<NormalizedHour>(n)
        for (i in 0 until n) {
            val ms = parseLocalIso(times.optString(i), tz)
            if (ms <= 0L) continue
            val temp = temps.optDouble(i, Double.NaN)
            if (temp.isNaN()) continue
            out += NormalizedHour(ms, temp.roundToInt(), codes.optInt(i, 0))
        }
        return out
    }

    private fun parseOpenMeteoDays(o: JSONObject, tz: TimeZone): List<NormalizedDay> {
        val times = o.optJSONArray("time") ?: return emptyList()
        val lows = o.optJSONArray("temperature_2m_min") ?: return emptyList()
        val highs = o.optJSONArray("temperature_2m_max") ?: return emptyList()
        val codes = o.optJSONArray("weather_code") ?: JSONArray()
        val n = minOf(times.length(), lows.length(), highs.length(), 8)
        val out = ArrayList<NormalizedDay>(n)
        for (i in 0 until n) {
            val ms = parseLocalDate(times.optString(i), tz)
            val lo = lows.optDouble(i, Double.NaN)
            val hi = highs.optDouble(i, Double.NaN)
            if (ms <= 0L || lo.isNaN() || hi.isNaN()) continue
            out += NormalizedDay(ms, lo.roundToInt(), hi.roundToInt(), codes.optInt(i, 0))
        }
        return out
    }
}

/**
 * Backup provider: MET Norway Locationforecast 2.0 /compact.
 *
 * IMPORTANT: MET Norway requires an identifying User-Agent. Do not use generic
 * values such as "okhttp", "Dalvik" or "Java". Pass your actual app id and a
 * stable contact URL/address in production.
 */
class MetNorwayWeatherSource(
    private val userAgent: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 7_000
) : WeatherSource {

    override val id: WeatherProviderId = WeatherProviderId.MET_NORWAY

    init {
        require(userAgent.isNotBlank()) { "MET Norway requires a non-empty User-Agent" }
        val ua = userAgent.lowercase(Locale.US)
        require(listOf("okhttp", "dalvik", "java", "fhttp").none { it in ua }) {
            "Use an identifying MET Norway User-Agent, not a generic library name"
        }
    }

    override fun load(request: WeatherRequest): NormalizedWeather {
        val lat = coord(request.latitude)
        val lon = coord(request.longitude)
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$lat&lon=$lon"
        val json = httpGet(
            url,
            mapOf("User-Agent" to userAgent, "Accept" to "application/json"),
            connectTimeoutMs,
            readTimeoutMs
        )
        val root = JSONObject(json)
        val series = root.optJSONObject("properties")?.optJSONArray("timeseries")
            ?: throw IllegalStateException("MET Norway returned no timeseries")
        require(series.length() > 0) { "MET Norway returned an empty timeseries" }

        val tz = safeTimeZone(request.timezoneHint)
        val hours = ArrayList<NormalizedHour>(minOf(series.length(), 96))
        var currentTemp: Int? = null
        var currentFeels: Int? = null
        var currentCode = 0

        for (i in 0 until minOf(series.length(), 96)) {
            val item = series.optJSONObject(i) ?: continue
            val ms = parseUtcIso(item.optString("time"))
            if (ms <= 0L) continue
            val data = item.optJSONObject("data") ?: continue
            val details = data.optJSONObject("instant")?.optJSONObject("details") ?: continue
            val air = details.optDouble("air_temperature", Double.NaN)
            if (air.isNaN()) continue
            val symbol = symbolCode(data)
            val wmo = metSymbolToWmo(symbol)
            val temp = air.roundToInt()
            hours += NormalizedHour(ms, temp, wmo)

            if (currentTemp == null) {
                currentTemp = temp
                // MET Locationforecast has no direct apparent-temperature field.
                currentFeels = null
                currentCode = wmo
            }
        }

        require(hours.isNotEmpty()) { "MET Norway returned no usable hourly rows" }
        val days = aggregateMetDays(hours, tz)
        return NormalizedWeather(
            city = request.city,
            timezoneId = tz.id,
            tempNowC = currentTemp ?: hours.first().tempC,
            feelsNowC = currentFeels,
            codeNow = currentCode,
            hours = hours,
            days = days,
            // Locationforecast does not provide sunrise/sunset. The failover
            // service below reuses recent cached sun times when available.
            sunriseMs = 0L,
            sunsetMs = 0L
        )
    }

    private fun symbolCode(data: JSONObject): String {
        val one = data.optJSONObject("next_1_hours")?.optJSONObject("summary")
            ?.optString("symbol_code").orEmpty()
        if (one.isNotBlank()) return one
        return data.optJSONObject("next_6_hours")?.optJSONObject("summary")
            ?.optString("symbol_code").orEmpty()
    }

    private fun aggregateMetDays(
        hours: List<NormalizedHour>,
        tz: TimeZone
    ): List<NormalizedDay> {
        data class Bucket(
            var firstMs: Long,
            var lo: Int,
            var hi: Int,
            var chosenCode: Int,
            var chosenScore: Int
        )

        val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = tz }
        val buckets = LinkedHashMap<String, Bucket>()
        for (h in hours) {
            val key = keyFmt.format(Date(h.timeMs))
            val score = weatherSeverity(h.code)
            val b = buckets[key]
            if (b == null) {
                buckets[key] = Bucket(h.timeMs, h.tempC, h.tempC, h.code, score)
            } else {
                if (h.tempC < b.lo) b.lo = h.tempC
                if (h.tempC > b.hi) b.hi = h.tempC
                if (score > b.chosenScore) {
                    b.chosenScore = score
                    b.chosenCode = h.code
                }
            }
        }

        return buckets.values.take(8).map { b ->
            val c = Calendar.getInstance(tz).apply {
                timeInMillis = b.firstMs
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            NormalizedDay(c.timeInMillis, b.lo, b.hi, b.chosenCode)
        }
    }

    /** Maps MET symbol_code names to the WMO codes already used by the app UI. */
    private fun metSymbolToWmo(raw: String): Int {
        val s = raw.lowercase(Locale.US).substringBefore('_')
        return when {
            "thunder" in s -> 95
            "heavysnow" in s -> 75
            "snowshowers" in s -> 85
            "lightsnow" in s -> 71
            "snow" in s -> 73
            "heavysleet" in s -> 67
            "sleetshowers" in s -> 66
            "sleet" in s -> 66
            "heavyrain" in s -> 65
            "rainshowers" in s -> 80
            "lightrain" in s -> 61
            "rain" in s -> 63
            "fog" in s -> 45
            "cloudy" in s -> 3
            "partlycloudy" in s -> 2
            "fair" in s -> 1
            "clearsky" in s -> 0
            else -> 3
        }
    }
}

/** Small persistent last-good cache; avoids a blank AOD screen on network/API outages. */
class WeatherSnapshotCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "weather_failover_cache_v1",
        Context.MODE_PRIVATE
    )

    fun save(request: WeatherRequest, data: NormalizedWeather) {
        val root = JSONObject()
        root.put("lat", request.latitude)
        root.put("lon", request.longitude)
        root.put("city", data.city)
        root.put("tz", data.timezoneId)
        root.put("temp", data.tempNowC)
        if (data.feelsNowC != null) root.put("feels", data.feelsNowC)
        root.put("code", data.codeNow)
        root.put("rise", data.sunriseMs)
        root.put("set", data.sunsetMs)
        root.put("fetched", data.fetchedAtMs)

        root.put("hours", JSONArray().apply {
            data.hours.take(96).forEach { h ->
                put(JSONObject().apply {
                    put("t", h.timeMs); put("v", h.tempC); put("c", h.code)
                })
            }
        })
        root.put("days", JSONArray().apply {
            data.days.take(8).forEach { d ->
                put(JSONObject().apply {
                    put("t", d.timeMs); put("lo", d.tMin); put("hi", d.tMax); put("c", d.code)
                })
            }
        })
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    fun load(request: WeatherRequest): NormalizedWeather? {
        val raw = prefs.getString(KEY, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return null
            // Do not reuse a forecast for a clearly different place.
            if (distanceSq(lat, lon, request.latitude, request.longitude) > 0.01) return null

            val hoursJson = o.optJSONArray("hours") ?: JSONArray()
            val hours = ArrayList<NormalizedHour>(hoursJson.length())
            for (i in 0 until hoursJson.length()) {
                val h = hoursJson.optJSONObject(i) ?: continue
                hours += NormalizedHour(h.optLong("t"), h.optInt("v"), h.optInt("c"))
            }
            val daysJson = o.optJSONArray("days") ?: JSONArray()
            val days = ArrayList<NormalizedDay>(daysJson.length())
            for (i in 0 until daysJson.length()) {
                val d = daysJson.optJSONObject(i) ?: continue
                days += NormalizedDay(
                    d.optLong("t"), d.optInt("lo"), d.optInt("hi"), d.optInt("c")
                )
            }
            if (hours.isEmpty() && days.isEmpty()) return null

            NormalizedWeather(
                city = o.optString("city", request.city),
                timezoneId = o.optString("tz", request.timezoneHint),
                tempNowC = o.optInt("temp"),
                feelsNowC = if (o.has("feels") && !o.isNull("feels")) o.optInt("feels") else null,
                codeNow = o.optInt("code"),
                hours = hours,
                days = days,
                sunriseMs = o.optLong("rise", 0L),
                sunsetMs = o.optLong("set", 0L),
                fetchedAtMs = o.optLong("fetched", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun distanceSq(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dx = aLat - bLat
        val dy = aLon - bLon
        return dx * dx + dy * dy
    }

    companion object { private const val KEY = "last_good" }
}

/**
 * Primary -> backup -> last-good cache orchestration.
 *
 * Use from the app's worker/coroutine/Executor, never directly on the UI thread.
 */
class WeatherFailoverService(
    context: Context,
    private val primary: WeatherSource = OpenMeteoWeatherSource(),
    private val backup: WeatherSource,
    private val cache: WeatherSnapshotCache = WeatherSnapshotCache(context)
) {

    fun fetch(request: WeatherRequest): WeatherFetchResult {
        var primaryError: String? = null
        try {
            val data = primary.load(request)
            cache.save(request, data)
            return WeatherFetchResult(
                weather = data,
                provider = primary.id,
                fallback = false,
                stale = false
            )
        } catch (e: Exception) {
            primaryError = shortError(e)
        }

        val cachedBeforeBackup = cache.load(request)?.takeIf { isCacheUsable(it) }
        var backupError: String? = null
        try {
            var data = backup.load(request)
            // Preserve recent astronomical data from the primary cache because
            // Locationforecast itself does not include sunrise/sunset.
            if (cachedBeforeBackup != null &&
                System.currentTimeMillis() - cachedBeforeBackup.fetchedAtMs < 30L * 60L * 60L * 1000L &&
                cachedBeforeBackup.sunriseMs > 0L && cachedBeforeBackup.sunsetMs > 0L
            ) {
                data = data.copy(
                    sunriseMs = cachedBeforeBackup.sunriseMs,
                    sunsetMs = cachedBeforeBackup.sunsetMs
                )
            }
            cache.save(request, data)
            return WeatherFetchResult(
                weather = data,
                provider = backup.id,
                fallback = true,
                stale = false,
                primaryError = primaryError
            )
        } catch (e: Exception) {
            backupError = shortError(e)
        }

        val cached = cachedBeforeBackup ?: cache.load(request)?.takeIf { isCacheUsable(it) }
        if (cached != null) {
            return WeatherFetchResult(
                weather = cached,
                provider = WeatherProviderId.CACHE,
                fallback = true,
                stale = true,
                primaryError = primaryError,
                backupError = backupError
            )
        }

        throw WeatherUnavailableException(primaryError, backupError)
    }

    private fun shortError(e: Exception): String =
        (e.message ?: e.javaClass.simpleName).take(180)

    private fun isCacheUsable(data: NormalizedWeather): Boolean {
        if (data.fetchedAtMs <= 0L) return false
        val age = System.currentTimeMillis() - data.fetchedAtMs
        return age in 0..MAX_STALE_MS
    }

    companion object {
        private const val MAX_STALE_MS = 36L * 60L * 60L * 1000L
    }
}

class WeatherUnavailableException(
    primary: String?,
    backup: String?
) : Exception("No weather data. primary=${primary ?: "unknown"}; backup=${backup ?: "unknown"}")

/**
 * Generic bridge so the new failover layer can feed the project's existing
 * WeatherInfo model without assuming its constructor signature.
 *
 * Example:
 * val gateway = WeatherFailoverGateway(service) { n ->
 *     WeatherInfo(... map your existing constructor here ...)
 * }
 */
class WeatherFailoverGateway<T>(
    private val service: WeatherFailoverService,
    private val mapper: (NormalizedWeather) -> T
) {
    data class Result<T>(
        val data: T,
        val sourceLabel: String,
        val fallback: Boolean,
        val stale: Boolean,
        val fetchedAtMs: Long,
        val primaryError: String?,
        val backupError: String?
    )

    fun fetch(request: WeatherRequest): Result<T> {
        val r = service.fetch(request)
        return Result(
            data = mapper(r.weather),
            sourceLabel = r.provider.label,
            fallback = r.fallback,
            stale = r.stale,
            fetchedAtMs = r.weather.fetchedAtMs,
            primaryError = r.primaryError,
            backupError = r.backupError
        )
    }
}

private fun httpGet(
    url: String,
    headers: Map<String, String>?,
    connectTimeoutMs: Int,
    readTimeoutMs: Int
): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        useCaches = true
        headers?.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }.orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: ${body.take(220)}")
        }
        if (body.isBlank()) throw IllegalStateException("Empty HTTP response")
        return body
    } finally {
        conn.disconnect()
    }
}

private fun coord(v: Double): String = String.format(Locale.US, "%.4f", v)

private fun safeTimeZone(id: String): TimeZone {
    if (id.isBlank()) return TimeZone.getDefault()
    val tz = TimeZone.getTimeZone(id)
    // TimeZone silently maps unknown IDs to GMT. Preserve real GMT/UTC requests.
    if (tz.id == "GMT" && !id.equals("GMT", true) && !id.equals("UTC", true)) {
        return TimeZone.getDefault()
    }
    return tz
}

private fun parseLocalIso(value: String, tz: TimeZone): Long {
    if (value.isBlank()) return 0L
    return try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply {
            isLenient = false
            timeZone = tz
        }.parse(value)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}

private fun parseLocalDate(value: String, tz: TimeZone): Long {
    if (value.isBlank()) return 0L
    return try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
            timeZone = tz
        }.parse(value)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}

private fun parseUtcIso(value: String): Long {
    if (value.isBlank()) return 0L
    val patterns = arrayOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'")
    for (p in patterns) {
        try {
            val d = SimpleDateFormat(p, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(value)
            if (d != null) return d.time
        } catch (_: Exception) {
            // try next pattern
        }
    }
    return 0L
}

private fun firstIsoMs(a: JSONArray, tz: TimeZone): Long {
    if (a.length() <= 0) return 0L
    return parseLocalIso(a.optString(0), tz)
}

private fun weatherSeverity(code: Int): Int = when (code) {
    95, 96, 99 -> 100
    65, 67, 75, 82, 86 -> 90
    63, 66, 73, 81, 85 -> 80
    61, 71, 80 -> 70
    51, 53, 55, 56, 57, 77 -> 60
    45, 48 -> 50
    3 -> 30
    2 -> 20
    1 -> 10
    else -> 0
}
