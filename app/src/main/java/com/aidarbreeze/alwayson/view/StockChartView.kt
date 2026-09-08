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
 * Layers are kept in separate bands so nothing overlaps:
 *  - a top band: ticker (left) and the change in % vs the reference (right),
 *  - a left gutter: the price scale (3 labels, right-aligned),
 *  - the plot area: the close polyline or candlesticks, plus the dashed
 *    reference line at the user's price,
 *  - a thin bottom band: time (HH:MM) ticks in the middle, the short date
 *    (dd.MM) on the far left and the shown interval on the far right.
 * The value range always auto-fits the visible data (with the reference line).
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

    private fun fmtPrice(v: Double): String {
        val high = candles.maxOfOrNull { it.high } ?: 0.0
        val low = candles.minOfOrNull { it.low } ?: 0.0
        val span = high - low
        val dec = when {
            span >= 200 -> 0
            span >= 20 -> 1
            else -> 2
        }
        return String.format(Locale.US, "%." + dec + "f", v)
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
        val titleH = dpf(24f)         // top: symbol / change
        val gutterW = dpf(52f)        // left: price scale
        val bottomH = dpf(15f)        // bottom: date / time / interval
        val padT = titleH + dpf(2f)
        val padB = bottomH
        val padL = dpf(2f)
        val padR = dpf(2f)

        val plotL = gutterW
        val plotT = padT
        val plotB = h - padB
        val plotR = w - padR
        val plotW = plotR - plotL
        val plotH = plotB - plotT

        // ---- vertical range auto-fit (data + reference) ----
        var min = candles.minOfOrNull { it.low } ?: 0.0
        var max = candles.maxOfOrNull { it.high } ?: 0.0
        if (refPrice > 0.0) {
            if (refPrice < min) min = refPrice
            if (refPrice > max) max = refPrice
        }
        val span = (max - min).let { if (it <= 0.0) 1.0 else it }
        val padV = span * 0.12
        min -= padV
        max += padV
        val vspan = (max - min).let { if (it <= 0.0) 1.0 else it }
        fun yFor(v: Double): Float = plotB - ((v - min) / vspan).toFloat() * plotH

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

        // ---- title band (never overlaps the price gutter below) ----
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(symbol, padL, dpf(16f), textPaint)
        val pct = pctText()
        if (pct.isNotEmpty()) {
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(pct, w - padR, dpf(16f), labelPaint)
        }

        // ---- plot grid, reference, price labels ----
        labelPaint.textAlign = Paint.Align.RIGHT
        val ticksY = intArrayOf(0, 1, 2)
        for (i in ticksY) {
            val f = i / 2f
            val y = plotT + f * plotH
            canvas.drawLine(plotL, y, plotR, y, gridPaint)
            val value = max - (max - min) * f
            // price label right-aligned just inside the gutter, vertically near the line
            canvas.drawText(fmtPrice(value), plotL - dpf(4f), y + dpf(3f), labelPaint)
        }

        if (refPrice > 0.0) {
            canvas.drawLine(plotL, yFor(refPrice), plotR, yFor(refPrice), refPaint)
        }

        // ---- series ----
        if (chartType() == 1) {
            drawCandles(canvas, plotL, plotR, plotW, ::xFor, ::yFor)
        } else {
            drawLine(canvas, ::xFor, ::yFor)
        }

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
        // Float division on purpose: with Int arithmetic the slot collapses to
        // 0 once the candle count exceeds the plot width in px, and the bodies
        // shrink to the 1 dp minimum.
        val slot = if (n > 1) plotW.toFloat() / (n - 1) else plotW.toFloat()
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
