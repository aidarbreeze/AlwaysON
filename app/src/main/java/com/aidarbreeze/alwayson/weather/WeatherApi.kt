package com.aidarbreeze.alwayson.weather

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/** One hourly forecast point. */
data class WeatherHour(val timeMs: Long, val tempC: Int, val code: Int)

/** One daily forecast. */
data class WeatherDay(val timeMs: Long, val code: Int, val tMin: Int, val tMax: Int)

/** Full weather payload shown by the panel. */
data class WeatherInfo(
    val city: String,
    val tempNowC: Int,
    val codeNow: Int,
    val hours: List<WeatherHour>,
    val days: List<WeatherDay>,
    /** "Feels like" temperature now, C; null = not provided (0 is a real value). */
    val feelsNowC: Int? = null,
    /** Today's sunrise / sunset as absolute epoch ms (0 = unknown). The
     *  instants are already converted to the forecast timezone. */
    val sunriseMs: Long = 0L,
    val sunsetMs: Long = 0L,
    /** IANA zone of the forecast point (e.g. "Europe/Amsterdam"); all
     *  human-readable times must be formatted in this zone. */
    val timezoneId: String = ""
)

/** A place chosen by name (manual city input). */
data class GeoPlace(val lat: Double, val lon: Double, val name: String)

/**
 * Fetches weather for a coordinate from Open-Meteo (free, keyless) and geocodes
 * city names for the manual-location option. Hourly points are rounded to the
 * hour and daily points are one entry per day, matching the WMO weather_code
 * used by the panel. All networking runs on the caller's background thread.
 */
object WeatherApi {

    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"

    /** How many forecast days to keep. */
    private const val DAYS = 7

    /** Look up a city/place by name; returns its coordinates and display name. */
    fun geocode(name: String): GeoPlace? {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val url = "$GEOCODE?name=$q&count=1&language=${lang()}&format=json"
        val body = httpGet(url) ?: return null
        return try {
            val root = JSONObject(body)
            val results = root.optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val r = results.getJSONObject(0)
            val lat = r.optDouble("latitude", Double.NaN)
            val lon = r.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return null
            val display = buildString {
                append(r.optString("name").ifBlank { name.trim() })
                val country = r.optString("country")
                if (country.isNotBlank()) append(", ").append(country)
            }
            GeoPlace(lat, lon, display)
        } catch (_: Exception) {
            null
        }
    }

    /** One fetch outcome: payload plus the provider that served it. */
    class FetchOutcome(
        val info: WeatherInfo?,
        /** Label for the panel footer ("Open-Meteo", "MET Norway"); "" = none. */
        val source: String,
        /** True when the primary provider failed and the backup served. */
        val fallback: Boolean
    )

    /** MET Norway requires an identifying User-Agent with real contact data;
     *  this app's actual repository URL is used (never a generic library name). */
    private const val BACKUP_USER_AGENT =
        "AlwaysON/1.0 (https://github.com/aidarbreeze/AlwaysON)"

    /**
     * Fetch the hourly + daily forecast: Open-Meteo first, MET Norway
     * Locationforecast 2.0 /compact as the automatic backup. info == null
     * when both providers failed (the repository then serves its last-known
     * forecast, marked stale). Two sequential network calls worst case, each
     * with its own timeouts — must run on the caller's background thread.
     */
    fun fetch(lat: Double, lon: Double, city: String = ""): FetchOutcome {
        try {
            val info = fetchOpenMeteo(lat, lon, city)
            if (info != null) return FetchOutcome(info, "Open-Meteo", false)
        } catch (_: Exception) {
            // primary failed — fall through to the backup provider
        }
        return try {
            val n = MetNorwayWeatherSource(BACKUP_USER_AGENT)
                .load(WeatherRequest(lat, lon, city))
            FetchOutcome(normalizedToInfo(n, city), "MET Norway", true)
        } catch (_: Exception) {
            FetchOutcome(null, "", false)
        }
    }

    private fun fetchOpenMeteo(lat: Double, lon: Double, city: String): WeatherInfo? {
        val url = buildUrl(lat, lon)
        val body = httpGet(url) ?: return null
        return parse(body, city)
    }

    /** Map the provider-neutral forecast onto the app's WeatherInfo. */
    private fun normalizedToInfo(n: NormalizedWeather, fallbackCity: String): WeatherInfo =
        WeatherInfo(
            city = n.city.ifBlank { fallbackCity },
            tempNowC = n.tempNowC,
            codeNow = n.codeNow,
            hours = n.hours.map { WeatherHour(it.timeMs, it.tempC, it.code) },
            days = n.days.map { WeatherDay(it.timeMs, it.code, it.tMin, it.tMax) },
            feelsNowC = n.feelsNowC,
            sunriseMs = n.sunriseMs,
            sunsetMs = n.sunsetMs,
            timezoneId = n.timezoneId
        )

    private fun buildUrl(lat: Double, lon: Double): String {
        // Round to ~2 decimals: plenty for weather, keeps URLs short.
        val la = String.format(Locale.US, "%.2f", lat)
        val lo = String.format(Locale.US, "%.2f", lon)
        return "$FORECAST?latitude=$la&longitude=$lo" +
            "&current=temperature_2m,apparent_temperature,weather_code" +
            "&hourly=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
            "&timezone=auto&forecast_days=$DAYS&forecast_hours=48"
    }

    private fun httpGet(url: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection)
        return try {
            c.requestMethod = "GET"
            c.connectTimeout = 9000
            c.readTimeout = 9000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "AlwaysON/1.0")
            if (c.responseCode !in 200..299) {
                // Drain the error stream so the platform can reuse the
                // connection instead of dropping it after every failure.
                try {
                    c.errorStream?.use { it.readBytes() }
                } catch (_: Exception) {
                }
                null
            } else {
                c.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun parse(body: String, city: String): WeatherInfo? {
        if (body.isBlank()) return null
        return try {
            val root = JSONObject(body)
            val cur = root.getJSONObject("current")

            val tempNow = Math.round(cur.getDouble("temperature_2m")).toInt()
            val codeNow = cur.getInt("weather_code")
            // 0 is a REAL temperature, so "missing" must be null, not 0.
            val feelsNow: Int? =
                if (cur.has("apparent_temperature") && !cur.isNull("apparent_temperature"))
                    Math.round(cur.getDouble("apparent_temperature")).toInt() else null

            // timezone=auto -> all times in the response are LOCAL to the
            // forecast point. Parse them in that zone, not the device zone,
            // or the hours/sun times shift by the zone difference.
            val timezoneId = root.optString("timezone", "")
            val zone = if (timezoneId.isNotBlank())
                java.util.TimeZone.getTimeZone(timezoneId)
            else java.util.TimeZone.getDefault()

            // ---- hourly (rounded to the hour) ----
            val hourly = root.getJSONObject("hourly")
            val hTimes = hourly.getJSONArray("time")
            val hTemp = hourly.getJSONArray("temperature_2m")
            // Nullable on purpose: a response without weather_code must not
            // kill the whole parse (the old `?: getJSONArray(...)` re-threw
            // right here and discarded temperatures that were already
            // fetched) — such hours fall back to the current condition.
            val hCode = hourly.optJSONArray("weather_code")
            val hFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = zone }
            val hours = ArrayList<WeatherHour>()
            // Bound by the SHORTEST of the parallel arrays: a truncated
            // temperature array must not throw isNull(i) past its end and
            // kill the whole parse from the outer catch (same class of bug
            // the weather_code length guards below already fix).
            val hBound = minOf(hTimes.length(), hTemp.length())
            for (i in 0 until hBound) {
                if (hTemp.isNull(i)) continue
                val timeMs = parseIso(hFmt, hTimes.getString(i))
                if (timeMs < 0L) continue
                hours.add(
                    WeatherHour(
                        timeMs = timeMs,
                        tempC = Math.round(hTemp.getDouble(i)).toInt(),
                        // Length guard: a truncated/short weather_code array
                        // must not throw isNull(i) past its end and kill the
                        // whole parse from the outer catch.
                        code = if (hCode != null && i < hCode.length() && !hCode.isNull(i))
                            hCode.getInt(i) else codeNow
                    )
                )
            }

            // ---- daily ----
            val daily = root.getJSONObject("daily")
            val dTimes = daily.getJSONArray("time")
            // Same nullable treatment as the hourly array above: a missing
            // daily weather_code array must not discard the whole forecast.
            val dCode = daily.optJSONArray("weather_code")
            val dMax = daily.getJSONArray("temperature_2m_max")
            val dMin = daily.getJSONArray("temperature_2m_min")
            val dFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
            // Today's sunrise/sunset (first day = today, local "yyyy-MM-ddTHH:mm").
            val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = zone }
            var sunriseMs = 0L
            var sunsetMs = 0L
            if (dTimes.length() > 0) {
                val sun = daily.optJSONArray("sunrise")
                val suns = daily.optJSONArray("sunset")
                if (sun != null && !sun.isNull(0)) {
                    // coerce: parseIso signals failure with -1; persist 0
                    // (= "unknown") instead of a negative timestamp.
                    sunriseMs = parseIso(isoFmt, sun.getString(0)).coerceAtLeast(0L)
                }
                if (suns != null && !suns.isNull(0)) {
                    sunsetMs = parseIso(isoFmt, suns.getString(0)).coerceAtLeast(0L)
                }
            }
            val days = ArrayList<WeatherDay>()
            for (i in 0 until dTimes.length()) {
                // Guard both temperatures: a single null cell must not throw
                // and kill the whole forecast parse (outer catch -> null).
                if (dMax.isNull(i) || dMin.isNull(i)) continue
                val t = parseDate(dFmt, dTimes.getString(i))
                if (t < 0L) continue
                days.add(
                    WeatherDay(
                        timeMs = t,
                        // Length guard, same as the hourly array above: a
                        // short daily weather_code array must not throw past
                        // its end and discard the whole forecast.
                        code = if (dCode != null && i < dCode.length() && !dCode.isNull(i))
                            dCode.getInt(i) else codeNow,
                        tMin = Math.round(dMin.getDouble(i)).toInt(),
                        tMax = Math.round(dMax.getDouble(i)).toInt()
                    )
                )
            }

            if (days.isEmpty() && hours.isEmpty()) null
            else WeatherInfo(city, tempNow, codeNow, hours, days, feelsNow, sunriseMs, sunsetMs, timezoneId)
        } catch (_: Exception) {
            null
        }
    }

    private fun lang(): String =
        if (Locale.getDefault().language.equals("ru", ignoreCase = true)) "ru" else "en"

    private fun parseIso(fmt: SimpleDateFormat, s: String): Long {
        return try {
            fmt.parse(s)?.time ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun parseDate(fmt: SimpleDateFormat, s: String): Long {
        return try {
            fmt.parse(s)?.time ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }
}
