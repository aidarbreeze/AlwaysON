package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.aidarbreeze.alwayson.Prefs
import com.aidarbreeze.alwayson.stock.Candle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Monochrome (black & white) stock chart used by the calendar<->stocks mode.
 *
 * Layers are kept in separate bands so nothing overlaps:
 *  - a top band: ticker (left) and the last price + changes (right: day
 *    change vs the previous session close and change vs the reference),
 *  - a left gutter: the price scale — "nice" round values (1/2/5 steps) with
 *    grid lines,
 *  - the plot area: the close polyline or candlesticks. Candles are spaced
 *    by index (trading sessions compressed, like every real intraday chart);
 *    the polyline BREAKS across session gaps (night/weekend) instead of
 *    connecting them. A small marker on the right edge shows the last price,
 *    and the dashed reference line is drawn only when it is inside range.
 *  - a thin bottom band: short date (left), time (HH:MM) ticks in the
 *    middle, the shown interval + "closed" badge on the far right.
 *
 * The value range always auto-fits the visible DATA. The reference price is
 * deliberately excluded from the fit so a far-off reference cannot stretch
 * the axis and flatten the actual price movement.
 */
class StockChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var candles: List<Candle> = emptyList()
    private var symbol = ""
    private var refPrice = 0.0
    private var intervalLabel = ""
    private var intervalSec = 0
    private var statusText = ""

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpf(2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val wickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
    }
    private val bodyUpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val bodyDownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpf(1.5f)
    }
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
        pathEffect = DashPathEffect(floatArrayOf(dpf(6f), dpf(6f)), 0f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1EFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpf(0.6f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(12f)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }
    private val priceBigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(16f)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        textSize = dpf(10f)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        textSize = dpf(13f)
        textAlign = Paint.Align.CENTER
    }

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    /**
     * [intervalSec] is the nominal candle interval (60/600/3600): it decides
     * how big a time hole between two consecutive candles counts as a session
     * gap for the line-break.
     */
    fun setData(
        symbol: String,
        refPrice: Double,
        interval: String,
        candles: List<Candle>,
        intervalSec: Int = 0
    ) {
        this.symbol = symbol.uppercase()
        this.refPrice = refPrice
        this.intervalLabel = interval
        this.candles = candles
        this.intervalSec = intervalSec
        statusText = ""
        invalidate()
    }

    fun setStatus(text: String) {
        statusText = text
        candles = emptyList()
        invalidate()
    }

    private fun chartType(): Int = Prefs.stockType(context)

    private fun fmt(v: Double, dec: Int): String =
        String.format(Locale.US, "%.${dec}f", v)

    /** Decimals for the (precise) last-price readout, based on the data span. */
    private fun priceDecimals(span: Double): Int = when {
        span >= 200 -> 0
        span >= 20 -> 1
        else -> 2
    }

    /** Decimals for the scale tick labels, based on the tick step. */
    private fun tickDecimals(ticks: List<Double>): Int {
        if (ticks.size < 2) return 2
        val step = (ticks[1] - ticks[0]).let { if (it > 0) it else 1.0 }
        return when {
            step >= 1.0 -> 0
            step >= 0.1 -> 1
            else -> 2
        }
    }

    /** Round [x] to a "nice" 1/2/5 * 10^k number, for a readable tick step. */
    private fun niceNum(x: Double): Double {
        if (x <= 0.0) return 1.0
        val exp = Math.floor(Math.log10(x)).toInt()
        val f = x / Math.pow(10.0, exp.toDouble())
        val nf = when {
            f < 1.5 -> 1.0
            f < 3.0 -> 2.0
            f < 7.0 -> 5.0
            else -> 10.0
        }
        return nf * Math.pow(10.0, exp.toDouble())
    }

    /** Evenly spaced "nice" tick values covering [lo, hi], about [target] of them. */
    private fun priceTicks(lo: Double, hi: Double, target: Int): List<Double> {
        val span = hi - lo
        if (span <= 0.0) return listOf(lo)
        val step = niceNum(span / target)
        if (step <= 0.0) return listOf(lo)
        val out = ArrayList<Double>()
        var v = Math.ceil(lo / step) * step
        var guard = 0
        while (v <= hi + step * 1e-6 && guard++ < 50) {
            out.add(v)
            v += step
        }
        return out
    }

    private fun pct(v: Double): String {
        val sign = if (v > 0) "+" else ""
        return String.format(Locale.US, "%s%.2f%%", sign, v)
    }

    /**
     * Day change %: last close vs the close of the last candle of the
     * PREVIOUS trading session. Only computable when the fetched window spans
     * at least two sessions (10-min / 60-min windows do; a short 1-min
     * window does not).
     */
    private fun dayChangePct(): Double? {
        val list = candles
        if (list.size < 4) return null
        val msk = TimeZone.getTimeZone("Europe/Moscow")
        val cal = Calendar.getInstance(msk)
        val lastCal = cal.apply { timeInMillis = list.last().timeMs }
        val lastDay = lastCal.get(Calendar.DAY_OF_YEAR)
        var prevClose: Double? = null
        for (c in list.reversed()) {
            cal.timeInMillis = c.timeMs
            if (cal.get(Calendar.DAY_OF_YEAR) == lastDay) continue
            prevClose = c.close
            break
        }
        if (prevClose == null || prevClose <= 0.0) return null
        return (list.last().close - prevClose) / prevClose * 100.0
    }

    /** True when a MOEX TQBR session is open right now (morning 06:50
     *  through evening 23:50 MSK on weekdays). */
    private fun marketOpenNow(): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"))
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
        val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return mins in (6 * 60 + 50)..(23 * 60 + 50)
    }

    /**
     * Short label of the NEXT TQBR session open (weekdays 06:50 MSK,
     * no holiday calendar — weekends only): "06:50" when it opens today,
     * otherwise "пн 06:50" / "вт 06:50" / ...
     */
    private fun nextOpenLabel(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"))
        val openMins = 6 * 60 + 50
        val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val names = arrayOf("", "пн", "вт", "ср", "чт", "пт", "сб", "вс")
        for (ahead in 0..7) {
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, ahead)
            val d = c.get(Calendar.DAY_OF_WEEK)
            if (d == Calendar.SATURDAY || d == Calendar.SUNDAY) continue
            if (ahead == 0 && nowMins >= openMins) continue
            return if (ahead == 0) "06:50" else "${names[d]} 06:50"
        }
        return "06:50"
    }

    private fun shortDate(): String {
        val c = Calendar.getInstance()
        return String.format(Locale.US, "%02d.%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        if (candles.size < 2) {
            val msg = if (statusText.isNotEmpty()) statusText else "…"
            canvas.drawText(msg, w / 2f, h / 2f, statusPaint)
            return
        }

        // ---- geometry (fixed bands so labels never overlap) ----
        val gutterW = dpf(50f)        // left: price scale
        val bottomH = dpf(15f)        // bottom: date / time / interval
        val padT = dpf(38f)           // top: ticker / last price / change
        val padB = bottomH
        val padL = dpf(2f)
        val padR = dpf(2f)

        val plotL = gutterW
        val plotT = padT
        val plotB = h - padB
        val plotR = w - padR
        val plotW = plotR - plotL
        val plotH = plotB - plotT
        if (plotW < dpf(40f) || plotH < dpf(24f)) return // too small for a chart

        // ---- vertical range: fit the visible DATA only ----
        val dMin = candles.minOfOrNull { it.low } ?: 0.0
        val dMax = candles.maxOfOrNull { it.high } ?: 0.0
        val dSpan = (dMax - dMin).let { if (it <= 0.0) 1.0 else it }
        val padV = dSpan * 0.10
        val lo = dMin - padV
        val hi = dMax + padV
        val vspan = (hi - lo).let { if (it <= 0.0) 1.0 else it }
        fun yFor(v: Double): Float = plotB - ((v - lo) / vspan).toFloat() * plotH

        // x by INDEX: every candle gets an equal slot, so trading sessions
        // are compressed (no empty night/weekend regions) — the standard
        // intraday-chart layout.
        fun xFor(i: Int): Float =
            if (candles.size > 1) plotL + (i.toFloat() / (candles.size - 1)) * plotW else plotL

        // A time hole bigger than this between consecutive candles is a
        // session gap: the line must not be drawn across it.
        val gapThresholdMs = maxOf(2L * intervalSec * 1000L, 30L * 60L * 1000L)
        fun isGap(i: Int): Boolean = i > 0 &&
            candles[i].timeMs > 0 && candles[i - 1].timeMs > 0 &&
            candles[i].timeMs - candles[i - 1].timeMs > gapThresholdMs

        // ---- top band: ticker (left), last price + changes (right) ----
        val last = candles.last().close
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(symbol, padL, dpf(20f), textPaint)
        priceBigPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(fmt(last, priceDecimals(dSpan)), w - padR, dpf(21f), priceBigPaint)

        // "day" = vs the previous session close, "base" = vs your reference.
        val changes = buildString {
            val day = dayChangePct()
            if (day != null) append(pct(day)).append(" день")
            if (refPrice > 0.0) {
                if (isNotEmpty()) append("  ")
                append(pct((last - refPrice) / refPrice * 100.0)).append(" база")
            }
        }
        if (changes.isNotEmpty()) {
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(changes, w - padR, dpf(35f), labelPaint)
        }

        // ---- price grid: nice round tick values ----
        val ticks = priceTicks(lo, hi, 4)
        val tDecs = tickDecimals(ticks)
        labelPaint.textAlign = Paint.Align.RIGHT
        for (v in ticks) {
            val y = yFor(v)
            if (y < plotT - 1f || y > plotB + 1f) continue
            canvas.drawLine(plotL, y, plotR, y, gridPaint)
            canvas.drawText(fmt(v, tDecs), plotL - dpf(4f), y + dpf(3f), labelPaint)
        }

        // ---- reference line (only when it lies inside the visible range) ----
        if (refPrice > 0.0 && refPrice >= lo && refPrice <= hi) {
            val ry = yFor(refPrice)
            canvas.drawLine(plotL, ry, plotR, ry, refPaint)
        }

        // ---- series (line breaks across session gaps) ----
        if (chartType() == 1) {
            drawCandles(canvas, plotW, ::xFor, ::yFor)
        } else {
            drawLine(canvas, ::xFor, ::yFor, ::isGap)
        }

        // ---- last price marker on the right edge ----
        val yl = yFor(last)
        canvas.drawRect(plotR - dpf(1f), yl - dpf(2.5f), plotR + dpf(2f), yl + dpf(2.5f), bodyUpPaint)

        // ---- bottom band ----
        val axisBaseline = h - dpf(3f)
        // Three interior HH:MM labels (index positions) so the far edges stay
        // free for the date (left) and the interval (right).
        val hasTimes = candles.any { it.timeMs > 0 }
        if (hasTimes) {
            val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
            labelPaint.textAlign = Paint.Align.CENTER
            for (frac in floatArrayOf(0.25f, 0.5f, 0.75f)) {
                val idx = (frac * (candles.size - 1)).toInt()
                val t = candles[idx].timeMs
                if (t <= 0) continue
                canvas.drawText(timeFmt.format(Date(t)), xFor(idx), axisBaseline, labelPaint)
            }
        }

        // date (bottom-left) and interval + market state (bottom-right);
        // when closed, say when it opens again.
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(shortDate(), padL, axisBaseline, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        val intervalText = if (marketOpenNow()) intervalLabel
        else "$intervalLabel · закрыт · ${nextOpenLabel()}"
        canvas.drawText(intervalText, w - padR, axisBaseline, labelPaint)
    }

    private fun drawLine(
        canvas: Canvas,
        xFor: (Int) -> Float,
        yFor: (Double) -> Float,
        isGap: (Int) -> Boolean
    ) {
        val path = Path()
        for (i in candles.indices) {
            val x = xFor(i)
            val y = yFor(candles[i].close)
            if (i == 0 || isGap(i)) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawCandles(
        canvas: Canvas,
        plotW: Float,
        xFor: (Int) -> Float,
        yFor: (Double) -> Float
    ) {
        val n = candles.size
        val slot = if (n > 1) plotW / (n - 1) else plotW
        val bodyHalf = (slot * 0.32f).coerceIn(dpf(1f), dpf(14f))
        for (i in 0 until n) {
            val c = candles[i]
            val x = xFor(i)
            val up = c.close >= c.open
            canvas.drawLine(x, yFor(c.high), x, yFor(c.low), wickPaint)
            val body = RectF(
                x - bodyHalf,
                yFor(maxOf(c.open, c.close)),
                x + bodyHalf,
                yFor(minOf(c.open, c.close))
            )
            if (up) {
                canvas.drawRect(body, bodyUpPaint)
            } else {
                canvas.drawRect(body, bodyDownPaint)
            }
        }
    }
}
