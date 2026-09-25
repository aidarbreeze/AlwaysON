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
 * - Rate limiting via the per-key cache below
 * - Data validation for OHLC values
 */
object StockApi {

    private const val ISS = "https://iss.moex.com/iss/engines/stock/markets/shares/boards/"
    // Boards to try in order: TQBR (stocks) first, then TQTF (ETFs/funds).
    private val BOARDS = arrayOf("TQBR", "TQTF")
    private const val TZ = "Europe/Moscow"
    
    // Rate limiting: minimum interval between stock API calls for the SAME
    // ticker+interval (1 minute). Per-key: the old single global timestamp
    // blocked every OTHER ticker/interval for a minute, so a rotation with
    // several windows showed "no data" for all but the first one.
    private const val RATE_LIMIT_MS = 60L * 1000L
    private val lastFetchByKey = HashMap<String, Long>()
    // The last SUCCESSFUL result per key. The limiter is process-global while
    // every StandbyController / mini-preview keeps its own cache: a freshly
    // created consumer hitting the limiter with no cache of its own would
    // otherwise sit on "Загрузка…" until the next cycle instead of getting
    // the (seconds-old) data another window already fetched.
    private val lastGoodByKey = HashMap<String, FetchResult>()
    private val rateLock = Any()
    
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
        else -> 21L * 24L * 60L * 60_000L // 60-min: last 21 days (long holidays)
    }

    /** Why a fetch produced no candles (null = success). */
    enum class FetchError {
        BAD_TICKER,   // rejected by the ticker format check
        RATE_LIMITED, // same key fetched <60 s ago: keep old UI, retry later
        NETWORK,      // transport failed or no HTTP success at all (see httpCode)
        NOT_FOUND,    // ISS knows no such security on any tried board
        CLOSED_EMPTY  // HTTP OK but zero candles anywhere (closed market / holiday)
    }

    /** One logical fetch: candles (possibly via fallback) or a reason. */
    class FetchResult(
        val candles: List<Candle>?,
        /** Interval the candles really are — a fallback may differ. */
        val actualCode: Int,
        val error: FetchError?,
        /** Last HTTP status seen (0 = none, -1 = transport failure). */
        val httpCode: Int = 0
    )

    /**
     * Human-readable one-line status for a failed fetch, shown under the
     * chart (Russian, like the other chart labels). null = stay silent and
     * keep "Загрузка…" — the retry comes with the next cycle. Single source
     * of truth: the overlay controller and the settings mini-preview used
     * to keep two copies of this mapping that had started to drift.
     */
    fun errorText(res: FetchResult): String? = when (res.error) {
        null, FetchError.RATE_LIMITED -> null
        FetchError.BAD_TICKER, FetchError.NOT_FOUND -> "тикер не найден"
        FetchError.CLOSED_EMPTY -> "торги закрыты"
        FetchError.NETWORK ->
            if (res.httpCode > 0) "нет сети (HTTP ${res.httpCode})" else "нет сети"
    }

    /**
     * Fetch candles (OHLC + time) for [symbol] at MOEX interval [code]
     * (1, 10 or 60), chronological oldest -> newest.
     *
     * A bare fetch fails whenever the market is closed (the 1-min window is
     * empty all night and all weekend), so on an empty window this falls
     * back to coarser intervals (1 -> 10 -> 60) and to the TQTF board
     * (ETFs), returning the interval that actually served ([actualCode]).
     * A transport failure aborts at once (retrying 5 more URLs on a dead
     * network only burns time); HTTP-level empties keep falling back.
     *
     * Within [RATE_LIMIT_MS] of a successful fetch for the same key this
     * returns the cached result (no network); with [force] the limiter is
     * skipped — for the explicit "Обновить данные" button.
     *
     * Never throws: every failure mode is a [FetchResult.error].
     */
    fun fetch(symbolRaw: String, code: Int, force: Boolean = false): FetchResult {
        // Security: Validate input
        val symbol = symbolRaw.uppercase(Locale.US)
        if (!isValidTicker(symbol)) return FetchResult(null, code, FetchError.BAD_TICKER)

        // Requested interval first, then coarser ones.
        val codes = when (code) {
            1 -> intArrayOf(1, 10, 60)
            10 -> intArrayOf(10, 60)
            else -> intArrayOf(60)
        }
        // One limiter key per LOGICAL fetch: the fallback attempts are part
        // of it, and only a non-empty success arms the limiter, so an empty
        // window never blocks its own retry (callers space attempts anyway).
        val key = "$symbol|$code"
        val now = System.currentTimeMillis()
        if (!force) synchronized(rateLock) {
            val last = lastFetchByKey[key] ?: 0L
            if (now - last < RATE_LIMIT_MS) {
                // Rate-limited, but the last successful candles for this key
                // are better than a blank "Загрузка…" for a fresh consumer.
                return lastGoodByKey[key]
                    ?: FetchResult(null, code, FetchError.RATE_LIMITED)
            }
        }

        var attempts = 0
        var notFounds = 0
        var sawOk = false
        var lastHttp = 0
        for (c in codes) {
            for (board in BOARDS) {
                attempts++
                val resp = try {
                    httpGet(buildUrl(symbol, c, board))
                } catch (_: Exception) {
                    // Dead network (DNS/timeout/...): further attempts would
                    // fail the same way, so stop immediately.
                    return FetchResult(null, code, FetchError.NETWORK, -1)
                }
                lastHttp = resp.code
                if (resp.code == 404) {
                    notFounds++
                    continue
                }
                if (resp.code !in 200..299) continue
                sawOk = true
                if (resp.body.isNullOrBlank()) continue
                val candles = try {
                    parse(resp.body)
                } catch (_: Exception) {
                    null
                }
                if (candles != null && candles.size >= 2) {
                    val ok = FetchResult(candles, c, null, resp.code)
                    synchronized(rateLock) {
                        lastFetchByKey[key] = now
                        lastGoodByKey[key] = ok
                    }
                    return ok
                }
                // HTTP OK but empty: fall through to the next fallback.
            }
        }
        if (attempts > 0 && notFounds == attempts) {
            return FetchResult(null, code, FetchError.NOT_FOUND, 404)
        }
        if (!sawOk) return FetchResult(null, code, FetchError.NETWORK, lastHttp)
        // Some endpoint answered HTTP OK yet nobody had candles: the market
        // is closed (night/weekend/holiday) for every tried window.
        return FetchResult(null, code, FetchError.CLOSED_EMPTY, lastHttp)
    }

    private fun buildUrl(symbol: String, code: Int, board: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(TZ)
        }
        val fromStr = URLEncoder.encode(
            fmt.format(Date(System.currentTimeMillis() - windowMs(code))), "UTF-8"
        )
        val tillStr = URLEncoder.encode(
            fmt.format(Calendar.getInstance(TimeZone.getTimeZone(TZ)).time),
            "UTF-8"
        )
        val sym = URLEncoder.encode(symbol, "UTF-8") // already uppercased
        return ISS + board + "/securities/" + sym +
            "/candles.json?interval=" + code +
            "&from=" + fromStr + "&till=" + tillStr +
            "&iss.meta=off&iss.only=candles"
    }

    /** One HTTP attempt. Returns the status + body, or throws on transport
     *  failure (DNS, timeout, ...). Non-2xx is a VALUE, not an exception. */
    private class Resp(val code: Int, val body: String?)

    private fun httpGet(url: String): Resp {
        val c = (URL(url).openConnection() as HttpURLConnection)
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 9000
            c.readTimeout = 9000
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code !in 200..299) {
                // Drain the error stream so the platform can reuse the
                // connection instead of dropping it after every failure.
                try {
                    c.errorStream?.use { it.readBytes() }
                } catch (_: Exception) {
                }
                return Resp(code, null)
            }
            return Resp(code, c.inputStream.bufferedReader().use { it.readText() })
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
                // A row whose open time failed to parse is dropped, not kept
                // with timeMs=0: an epoch-1970 candle would stretch the chart's
                // time axis by 55+ years and squash the real data to nothing.
                if (timeMs <= 0L) continue
                list.add(Candle(open, high, low, close, timeMs))
            }
            if (list.size < 2) null else list
        } catch (_: Exception) {
            null
        }
    }
}
