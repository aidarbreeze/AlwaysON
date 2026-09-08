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
import java.util.Calendar
import java.util.Locale

/**
 * Monochrome (black & white) stock chart used by the calendar<->stocks mode.
 *
 * Layers are kept in separate bands so nothing overlaps:
 *  - a top band: ticker (left) and the last price + change % vs the reference
 *    (right, last price in large bold),
 *  - a left gutter: the price scale — "nice" round values (1/2/5 steps) with
 *    grid lines,
 *  - the plot area: the close polyline or candlesticks, a small marker on the
 *    right edge at the last price, and the dashed reference line (drawn only
 *    when the reference falls inside the visible range),
 *  - a thin bottom band: short date (left), time (HH:MM) ticks in the middle,
 *    the shown interval on the far right.
 *
 * The value range always auto-fits the visible DATA. The reference price is
 * deliberately excluded from the fit so a far-off reference cannot stretch the
 * axis and flatten the actual price movement.
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

    fun setData(
        symbol: String,
        refPrice: Double,
        interval: String,
        candles: List<Candle>
    ) {
        this.symbol = symbol.uppercase()
        this.refPrice = refPrice
        this.intervalLabel = interval
        this.candles = candles
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

    private fun pctText(): String {
        val last = candles.lastOrNull()?.close ?: return ""
        if (refPrice <= 0.0) return ""
        val pct = (last - refPrice) / refPrice * 100.0
        val sign = if (pct > 0) "+" else ""
        return String.format(Locale.US, "%s%.2f%%", sign, pct)
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

        // ---- vertical range: fit the visible DATA only ----
        // The reference price is intentionally excluded here; forcing it into
        // the range would stretch the axis and flatten the price movement.
        val dMin = candles.minOfOrNull { it.low } ?: 0.0
        val dMax = candles.maxOfOrNull { it.high } ?: 0.0
        val dSpan = (dMax - dMin).let { if (it <= 0.0) 1.0 else it }
        val padV = dSpan * 0.10
        val lo = dMin - padV
        val hi = dMax + padV
        val vspan = (hi - lo).let { if (it <= 0.0) 1.0 else it }
        fun yFor(v: Double): Float = plotB - ((v - lo) / vspan).toFloat() * plotH

        // x by time — but only when EVERY candle has a valid timestamp. If a
        // single time failed to parse (timeMs == 0) we fall back to index
        // spacing for the whole series; mixing the two scales per point would
        // shoot the line to the wrong edge of the plot.
        val t0 = candles.first().timeMs
        val t1 = candles.last().timeMs
        val tspan = (t1 - t0).toDouble()
        val timeScale = tspan > 0 && candles.all { it.timeMs > 0 }
        fun xFor(i: Int): Float {
            if (timeScale) {
                return plotL + ((candles[i].timeMs - t0) / tspan).toFloat() * plotW
            }
            return if (candles.size > 1) plotL + (i.toFloat() / (candles.size - 1)) * plotW else plotL
        }

        // ---- top band: ticker (left), last price + change (right) ----
        val last = candles.last().close
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(symbol, padL, dpf(20f), textPaint)
        priceBigPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(fmt(last, priceDecimals(dSpan)), w - padR, dpf(21f), priceBigPaint)
        val pct = pctText()
        if (pct.isNotEmpty()) {
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(pct, w - padR, dpf(35f), labelPaint)
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

        // ---- series ----
        if (chartType() == 1) {
            drawCandles(canvas, plotL, plotR, plotW, ::xFor, ::yFor)
        } else {
            drawLine(canvas, ::xFor, ::yFor)
        }

        // ---- last price marker on the right edge ----
        val yl = yFor(last)
        canvas.drawRect(plotR - dpf(1f), yl - dpf(2.5f), plotR + dpf(2f), yl + dpf(2.5f), bodyUpPaint)

        // ---- bottom band ----
        val axisBaseline = h - dpf(3f)
        // time HH:MM ticks: only interior ticks so edges stay free for date/interval.
        // Only meaningful when the series is actually plotted by time.
        if (timeScale) {
            // Three interior HH:MM labels at 25/50/75% so the far edges stay free
            // for the date (left) and the interval (right).
            val timeFmt = java.text.SimpleDateFormat("HH:mm", Locale.US)
            labelPaint.textAlign = Paint.Align.CENTER
            for (frac in floatArrayOf(0.25f, 0.5f, 0.75f)) {
                val x = plotL + frac * plotW
                val t = (t0 + (tspan * frac).toLong())
                canvas.drawText(timeFmt.format(java.util.Date(t)), x, axisBaseline, labelPaint)
            }
        }

        // date (bottom-left) and interval (bottom-right)
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(shortDate(), padL, axisBaseline, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(intervalLabel, w - padR, axisBaseline, labelPaint)
    }

    private fun drawLine(canvas: Canvas, xFor: (Int) -> Float, yFor: (Double) -> Float) {
        val path = Path()
        for (i in candles.indices) {
            val x = xFor(i)
            val y = yFor(candles[i].close)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawCandles(
        canvas: Canvas,
        plotL: Float,
        plotR: Float,
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
