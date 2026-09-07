package com.aidarbreeze.alwayson.stock

import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal, dependency-free client for Yahoo Finance's unofficial chart JSON
 * endpoint (the "lightweight" option the design referenced). It returns a
 * closing-price series for a symbol at a given intraday interval (e.g. 5m,
 * 15m, 30m). Yahoo sometimes requires a cookie + crumb, so we obtain both and
 * send them along; if that fails we retry the plain query once. Runs on a
 * caller-provided background thread (never on the UI thread).
 */
object StockApi {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val CHART =
        "https://query1.finance.yahoo.com/v8/finance/chart/"

    /** Fetch the intraday close series for [symbol] at [interval]. Returns null
     *  on any failure (no network, symbol unknown, blocked endpoint, parse). */
    fun fetchSeries(symbol: String, interval: String): List<Double>? {
        return try {
            // Auto-manage cookies across the crumb + chart calls.
            val manager = CookieManager()
            manager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
            CookieHandler.setDefault(manager)

            val crumb = obtainCrumb()
            val url = buildUrl(symbol, interval, crumb)
            val body = httpGet(url)
            parse(body) ?: return null
        } catch (_: Exception) {
            null
        }
    }

    /** Returns the crumb string, or null when the endpoint is not reachable.
     *  The cookie needed for it is stored in the shared CookieManager. */
    private fun obtainCrumb(): String? {
        return try {
            // Hit the home host so it sets its A1/A3 cookies in our store.
            val home = httpGetRaw("https://fc.yahoo.com")
            // discard body, we only care about cookies being stored.
            home
            val text = httpGet(
                "https://query1.finance.yahoo.com/v1/test/getcrumb"
            )
            text?.trim()?.takeIf { it.isNotBlank() && it.length < 64 }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildUrl(symbol: String, interval: String, crumb: String?): String {
        val sym = URLEncoder.encode(symbol, "UTF-8")
        var url = CHART + sym +
            "?interval=" + interval +
            "&range=5d&includePrePost=false&events=div%2Csplit"
        if (!crumb.isNullOrBlank()) {
            url += "&crumb=" + URLEncoder.encode(crumb, "UTF-8")
        }
        return url
    }

    private fun httpGet(url: String): String? {
        val conn = open(url)
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetRaw(url: String): Unit {
        val conn = open(url)
        try {
            conn.inputStream.bufferedReader().use { it.readLines() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = (URL(url).openConnection() as HttpURLConnection)
        c.requestMethod = "GET"
        c.connectTimeout = 9000
        c.readTimeout = 9000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "*/*")
        return c
    }

    /** Parse the v8 chart JSON into a flat closing-price series. */
    private fun parse(body: String?): List<Double>? {
        if (body.isNullOrBlank()) return null
        val root = JSONObject(body)
        val chart = root.getJSONObject("chart")
        if (chart.has("error") && !chart.isNull("error")) {
            return null
        }
        if (!chart.has("result") || chart.isNull("result")) return null
        val result = chart.getJSONArray("result").getJSONObject(0)
        val timestamps = result.optJSONArray("timestamp") ?: return null
        if (timestamps.length() == 0) return null

        val indicators = result.getJSONObject("indicators")
        val quote = indicators.getJSONArray("quote").getJSONObject(0)
        val closes = quote.optJSONArray("close")

        val prices = ArrayList<Double>(timestamps.length())
        for (i in 0 until timestamps.length()) {
            if (closes == null) break
            if (closes.isNull(i)) continue
            prices.add(closes.getDouble(i))
        }
        return if (prices.size >= 2) prices else null
    }
}
