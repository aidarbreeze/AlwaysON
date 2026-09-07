package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.aidarbreeze.alwayson.Prefs
import com.aidarbreeze.alwayson.stock.Candle
import java.util.Calendar
import java.util.Locale

/**
 * Monochrome (black & white) stock chart used by the calendar<->stocks
 * alternation mode. Draws:
 *  - a price scale down the left side,
 *  - the close-price polyline OR candlesticks (user choice),
 *  - a horizontal dashed reference line at the price the user entered,
 *  - the ticker top-left and the live change in % (relative to that reference,
 *    e.g. "+2%") top-right,
 *  - a thin time scale (a few HH:MM ticks) along the bottom and a short date
 *    label (dd.MM) at the bottom-left,
 *  - the shown interval (1М/10М/60М) at the bottom-right.
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
        color = Color.TRANSPARENT
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
    }
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
        pathEffect = DashPathEffect(floatArrayOf(dpf(6f), dpf(6f)), 0f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x18FFFFFF.toInt() // very thin, faint
        style = Paint.Style.STROKE
        strokeWidth = dpf(0.6f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(12f)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }
    private val dimTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        textSize = dpf(11f)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        textSize = dpf(13f)
        textAlign = Paint.Align.CENTER
    }
    private val intervalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        textSize = dpf(11f)
        textAlign = Paint.Align.RIGHT
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
        val span = (candles.maxOfOrNull { it.high } ?: 0.0) -
            (candles.minOfOrNull { it.low } ?: 0.0)
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
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%02d.%02d", d, m)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        val labelH = dpf(20f)
        val axisW = dpf(46f)   // room for the left price labels
        val bottomAxisH = dpf(16f)
        val padR = dpf(4f)
        val padT = labelH + dpf(4f)

        if (candles.size < 2) {
            val msg = if (statusText.isNotEmpty()) statusText else "…"
            canvas.drawText(msg, w / 2f, h / 2f, statusPaint)
            return
        }

        val plotL = axisW
        val plotT = padT
        val plotB = h - bottomAxisH
        val plotR = w - padR
        val plotW = plotR - plotL
        val plotH = plotB - plotT

        // ---- vertical range incl. reference ----
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

        // ---- x mapping by time (fall back to equal spacing) ----
        val t0 = candles.first().timeMs
        val t1 = candles.last().timeMs
        val tspan = (t1 - t0).toDouble()
        fun xFor(i: Int): Float {
            if (tspan > 0 && candles[i].timeMs > 0) {
                val t = (candles[i].timeMs - t0) / tspan
                return plotL + t.toFloat() * plotW
            }
            return if (candles.size > 1)
                plotL + (i.toFloat() / (candles.size - 1)) * plotW else plotL
        }

        // ---- title row ----
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(symbol, dpf(2f), labelH, textPaint)
        val pct = pctText()
        if (pct.isNotEmpty()) {
            dimTextPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(pct, w - dpf(2f), labelH, dimTextPaint)
        }

        // ---- reference line ----
        if (refPrice > 0.0) {
            canvas.drawLine(plotL, yFor(refPrice), plotR, yFor(refPrice), refPaint)
        }

        // ---- left price scale: 3 labels + faint horizontal grid ----
        dimTextPaint.textAlign = Paint.Align.LEFT
        for (i in 0..2) {
            val f = i / 2f
            val value = min + (max - min) * f
            val y = yFor(value)
            canvas.drawLine(plotL, y, plotR, y, gridPaint)
            canvas.drawText(fmtPrice(value), dpf(2f), y - dpf(3f), dimTextPaint)
        }

        // ---- draw series (line or candles) ----
        if (chartType() == 1) {
            drawCandles(canvas, plotL, plotR, plotW, ::xFor, ::yFor)
        } else {
            drawLine(canvas, ::xFor, ::yFor)
        }

        // ---- time scale: a few HH:MM ticks along the bottom ----
        if (tspan > 0) {
            val ticks = 4
            val timeFmt = java.text.SimpleDateFormat("HH:mm", Locale.US)
            for (i in 0..ticks) {
                val f = i.toFloat() / ticks
                val x = plotL + f * plotW
                val t = (t0 + (tspan * f).toLong())
                canvas.drawLine(x, plotT, x, plotB, gridPaint)
                val label = timeFmt.format(java.util.Date(t))
                dimTextPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(label, x, h - dpf(3f), dimTextPaint)
            }
        }

        // short date at the bottom-left
        dimTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(shortDate(), plotL, h - dpf(3f), dimTextPaint)

        // interval at the bottom-right
        canvas.drawText(intervalLabel, plotR, h - dpf(3f), intervalPaint)
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
        // average slot width for candle bodies
        val slot = if (n > 1) plotW / (n - 1) else plotW
        val bodyHalf = (slot * 0.35f).coerceIn(dpf(1.5f), dpf(16f))
        for (i in 0 until n) {
            val c = candles[i]
            val x = xFor(i)
            val up = c.close >= c.open
            val topY = yFor(maxOf(c.open, c.close))
            val botY = yFor(minOf(c.open, c.close))
            // wick
            canvas.drawLine(x, yFor(c.high), x, yFor(c.low), wickPaint)
            // body
            val body = android.graphics.RectF(
                x - bodyHalf, topY, x + bodyHalf, botY
            )
            if (up) {
                canvas.drawRect(body, bodyUpPaint)
            } else {
                bodyDownPaint.color = Color.WHITE
                canvas.drawRect(body, bodyDownPaint)
            }
        }
    }
}
