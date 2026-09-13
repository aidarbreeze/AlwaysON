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

/** One MOEX ISS candle with its opening time in milliseconds. */
data class Candle(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val timeMs: Long
)

/**
 * Fetches intraday candles for a security listed on the Moscow Exchange from
 * the public, keyless MOEX ISS endpoint. This is the reliable source for
 * Russian tickers such as TATN (Tatneft) that Yahoo/other Western providers do
 * not serve from inside Russia.
 *
 * MOEX ISS natively offers intraday candles at 1 / 10 / 60 minutes
 * (interval codes 1 / 10 / 60). We bound each request with a recent "from"
 * window so a 1-minute fetch stays well under ISS's 500-row page limit.
 *
 * All network happens on the caller-provided background thread.
 * 
 * Security features:
 * - Input validation for ticker symbols (alphanumeric only)
 * - Rate limiting via cache (see StockRepository if exists)
 * - Data validation for OHLC values
 */
object StockApi {

    private const val BASE =
        "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/"
    private const val TZ = "Europe/Moscow"
    
    // Rate limiting: minimum interval between stock API calls (1 minute)
    private const val RATE_LIMIT_MS = 60L * 1000L
    private var lastFetchTime: Long = 0L
    
    // Valid ticker pattern: only letters, numbers, dots and underscores
    private val TICKER_PATTERN = Regex("^[A-Z0-9._-]+$")
    
    /** Validate ticker symbol format */
    private fun isValidTicker(symbol: String): Boolean {
        return symbol.isNotBlank() && symbol.length <= 20 && TICKER_PATTERN.matches(symbol.uppercase(Locale.US))
    }
    
    /** Validate OHLC data consistency */
    private fun isValidOHLC(open: Double, high: Double, low: Double, close: Double): Boolean {
        return open > 0 && close > 0 && high >= low && high >= open && high >= close && 
               low <= open && low <= close
    }

    /** how far back (ms) to request for each ISS interval code */
    private fun windowMs(code: Int): Long = when (code) {
        1 -> 150L * 60_000L      // 1-min: last 2.5 h keeps it < 500 rows
        10 -> 2L * 24L * 60L * 60_000L
        else -> 5L * 24L * 60L * 60_000L // 60-min: last 5 days
    }

    /**
     * Fetch candles (OHLC + time) for [symbol] at MOEX interval [code]
     * (1, 10 or 60), chronological oldest -> newest. Returns null on failure
     * or when there is no data in the chosen window.
     */
    fun fetchCandles(symbol: String, code: Int): List<Candle>? {
        // Security: Validate input
        if (!isValidTicker(symbol)) {
            return null
        }
        
        // Rate limiting check
        val now = System.currentTimeMillis()
        if (now - lastFetchTime < RATE_LIMIT_MS) {
            return null  // Rate limited
        }
        
        return try {
            val from = Date(System.currentTimeMillis() - windowMs(code))
            val url = buildUrl(symbol, code, from)
            val body = httpGet(url) ?: return null
            lastFetchTime = now
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

    /** Parse ISS candles JSON into a chronological list of [Candle]. */
    private fun parse(body: String?): List<Candle>? {
        if (body.isNullOrBlank()) return null
        return try {
            val root = JSONObject(body)
            val candles = root.getJSONObject("candles")
            val data = candles.optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            val columns = candles.getJSONArray("columns")
            fun idx(name: String): Int {
                for (i in 0 until columns.length()) {
                    if (columns.getString(i).equals(name, ignoreCase = true)) return i
                }
                return -1
            }
            val iOpen = idx("open")
            val iHigh = idx("high")
            val iLow = idx("low")
            val iClose = idx("close")
            val iBegin = idx("begin")
            if (iClose < 0) return null

            val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone(TZ)
            }
            val list = ArrayList<Candle>(data.length())
            for (i in 0 until data.length()) {
                val row = data.getJSONArray(i)
                if (row.isNull(iClose)) continue
                val close = row.getDouble(iClose)
                val open = if (iOpen >= 0 && !row.isNull(iOpen)) row.getDouble(iOpen) else close
                val high = if (iHigh >= 0 && !row.isNull(iHigh)) row.getDouble(iHigh) else close
                val low = if (iLow >= 0 && !row.isNull(iLow)) row.getDouble(iLow) else close
                
                // Security: Validate OHLC data consistency before adding
                if (!isValidOHLC(open, high, low, close)) {
                    continue  // Skip invalid data points
                }
                
                var timeMs = 0L
                if (iBegin >= 0 && !row.isNull(iBegin)) {
                    timeMs = try {
                        timeFmt.parse(row.getString(iBegin))?.time ?: 0L
                    } catch (_: Exception) {
                        0L
                    }
                }
                list.add(Candle(open, high, low, close, timeMs))
            }
            if (list.size < 2) null else list
        } catch (_: Exception) {
            null
        }
    }
}
