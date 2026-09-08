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
    val days: List<WeatherDay>
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

    /** Fetch the hourly + daily forecast for a point. Null on failure. */
    fun fetch(lat: Double, lon: Double, city: String = ""): WeatherInfo? {
        return try {
            val url = buildUrl(lat, lon)
            val body = httpGet(url) ?: return null
            parse(body, city)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildUrl(lat: Double, lon: Double): String {
        // Round to ~2 decimals: plenty for weather, keeps URLs short.
        val la = String.format(Locale.US, "%.2f", lat)
        val lo = String.format(Locale.US, "%.2f", lon)
        return "$FORECAST?latitude=$la&longitude=$lo" +
            "&current=temperature_2m,weather_code" +
            "&hourly=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
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
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }
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

            // ---- hourly (rounded to the hour) ----
            val hourly = root.getJSONObject("hourly")
            val hTimes = hourly.getJSONArray("time")
            val hTemp = hourly.getJSONArray("temperature_2m")
            val hCode = hourly.optJSONArray("weather_code") ?: hourly.getJSONArray("weather_code")
            val hFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
            val hours = ArrayList<WeatherHour>()
            for (i in 0 until hTimes.length()) {
                if (hTemp.isNull(i)) continue
                val timeMs = parseIso(hFmt, hTimes.getString(i))
                if (timeMs < 0L) continue
                hours.add(
                    WeatherHour(
                        timeMs = timeMs,
                        tempC = Math.round(hTemp.getDouble(i)).toInt(),
                        code = if (!hCode.isNull(i)) hCode.getInt(i) else codeNow
                    )
                )
            }

            // ---- daily ----
            val daily = root.getJSONObject("daily")
            val dTimes = daily.getJSONArray("time")
            val dCode = daily.optJSONArray("weather_code") ?: daily.getJSONArray("weather_code")
            val dMax = daily.getJSONArray("temperature_2m_max")
            val dMin = daily.getJSONArray("temperature_2m_min")
            val dFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
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
                        code = if (!dCode.isNull(i)) dCode.getInt(i) else codeNow,
                        tMin = Math.round(dMin.getDouble(i)).toInt(),
                        tMax = Math.round(dMax.getDouble(i)).toInt()
                    )
                )
            }

            if (days.isEmpty() && hours.isEmpty()) null
            else WeatherInfo(city, tempNow, codeNow, hours, days)
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
