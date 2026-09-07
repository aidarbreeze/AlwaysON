package com.aidarbreeze.alwayson.stock

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Fetches intraday candle series for a security listed on the Moscow Exchange
 * from the public, keyless MOEX ISS endpoint. This is the reliable source for
 * Russian tickers such as TATN (Tatneft) that Yahoo/other Western providers do
 * not serve from inside Russia.
 *
 * MOEX ISS natively offers intraday candles at 1 / 10 / 60 minutes
 * (interval codes 1 / 10 / 60). We bound each request with a recent "from"
 * window so a 1-minute fetch stays well under ISS's 500-row page limit.
 *
 * All network happens on the caller-provided background thread.
 */
object StockApi {

    private const val BASE =
        "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/"
    private const val TZ = "Europe/Moscow"

    /** how far back (ms) to request for each ISS interval code */
    private fun windowMs(code: Int): Long = when (code) {
        1 -> 150L * 60_000L      // 1-min: last 2.5 h keeps it < 500 rows
        10 -> 2L * 24L * 60L * 60_000L
        else -> 5L * 24L * 60L * 60_000L // 60-min: last 5 days
    }

    /**
     * Fetch the closing-price series (chronological, oldest -> newest) for
     * [symbol] at MOEX interval [code] (1, 10 or 60). Returns null on any
     * failure or when there is no data for the chosen window.
     */
    fun fetchSeries(symbol: String, code: Int): List<Double>? {
        return try {
            val from = Date(System.currentTimeMillis() - windowMs(code))
            val url = buildUrl(symbol, code, from)
            val body = httpGet(url) ?: return null
            parse(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildUrl(symbol: String, code: Int, from: Date): String {
        val sym = URLEncoder.encode(symbol.uppercase(Locale.US), "UTF-8")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(TZ)
        }
        val fromStr = URLEncoder.encode(fmt.format(from), "UTF-8")
        // till = now, also in Moscow time
        val tillStr = URLEncoder.encode(
            fmt.format(Calendar.getInstance(TimeZone.getTimeZone(TZ)).time),
            "UTF-8"
        )
        return BASE + sym + "/candles.json?interval=" + code +
            "&from=" + fromStr + "&till=" + tillStr +
            "&iss.meta=off&iss.only=candles"
    }

    private fun httpGet(url: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection)
        return try {
            c.requestMethod = "GET"
            c.connectTimeout = 9000
            c.readTimeout = 9000
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** Parse the ISS candles JSON into a flat chronological close series. */
    private fun parse(body: String?): List<Double>? {
        if (body.isNullOrBlank()) return null
        return try {
            val root = JSONObject(body)
            val candles = root.getJSONObject("candles")
            val data = candles.optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            val columns = candles.getJSONArray("columns")
            // close column index
            var closeIdx = -1
            for (i in 0 until columns.length()) {
                if (columns.getString(i).equals("close", ignoreCase = true)) {
                    closeIdx = i
                    break
                }
            }
            if (closeIdx < 0) return null

            val closes = ArrayList<Double>(data.length())
            for (i in 0 until data.length()) {
                val row = data.getJSONArray(i)
                if (row.isNull(closeIdx)) continue
                closes.add(row.getDouble(closeIdx))
            }
            if (closes.size < 2) null else closes
        } catch (_: Exception) {
            null
        }
    }
}
