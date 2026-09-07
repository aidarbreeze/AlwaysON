package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/**
 * Monochrome (black & white) stock line chart used by the calendar<->stocks
 * alternation mode. Draws:
 *  - the close-price polyline in white,
 *  - a horizontal dashed reference line at the price the user entered,
 *  - the ticker at the top-left and the live change in % (relative to that
 *    reference value, e.g. "+2%") at the top-right.
 * Everything is white / light grey on the OLED-black background.
 */
class StockChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var prices: List<Double> = emptyList()
    private var symbol = ""
    private var refPrice = 0.0
    private var statusText = "" // e.g. loading / error / "no data"

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpf(2.2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt() // dim dashed reference line
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
        pathEffect = DashPathEffect(floatArrayOf(dpf(6f), dpf(6f)), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(12f)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }
    private val dimTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        textSize = dpf(12f)
        textAlign = Paint.Align.RIGHT
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        textSize = dpf(13f)
        textAlign = Paint.Align.CENTER
    }

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    /** Set the raw series; the symbol and the user reference come from prefs
     *  but are passed in for cleanliness. */
    fun setData(symbol: String, refPrice: Double, prices: List<Double>) {
        this.symbol = symbol.uppercase()
        this.refPrice = refPrice
        this.prices = prices
        statusText = ""
        invalidate()
    }

    /** Show a message (loading / no network / error) instead of a chart. */
    fun setStatus(text: String) {
        statusText = text
        prices = emptyList()
        invalidate()
    }

    private fun pctText(): String {
        val last = prices.lastOrNull() ?: return ""
        if (refPrice <= 0.0) return ""
        val pct = (last - refPrice) / refPrice * 100.0
        val sign = if (pct > 0) "+" else ""
        return String.format(Locale.US, "%s%.2f%%", sign, pct)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        val labelH = dpf(18f)
        val padL = dpf(6f)
        val padR = dpf(6f)
        val padT = labelH + dpf(6f)
        val padB = dpf(8f)

        if (prices.size < 2) {
            val msg = if (statusText.isNotEmpty()) statusText
            else if (symbol.isNotEmpty()) "$symbol — нет данных" else "…"
            statusPaint.color = if (statusText.isEmpty()) Color.WHITE else 0x88FFFFFF.toInt()
            canvas.drawText(msg, w / 2f, h / 2f, statusPaint)
            return
        }

        // ---- title row ----
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(symbol, padL, labelH, textPaint)
        val pct = pctText()
        if (pct.isNotEmpty()) {
            canvas.drawText(pct, w - padR, labelH, dimTextPaint)
        }

        // ---- chart region ----
        val top = padT
        val bottom = h - padB
        val chartH = bottom - top

        // include the reference line in the vertical range so it is always shown
        var min = prices.minOrNull() ?: 0.0
        var max = prices.maxOrNull() ?: 0.0
        if (refPrice > 0.0) {
            if (refPrice < min) min = refPrice
            if (refPrice > max) max = refPrice
        }
        val span = (max - min).let { if (it <= 0.0) 1.0 else it }
        // small padding so the line does not hug the top/bottom edges
        val padV = span * 0.1
        min -= padV
        max += padV
        val vspan = (max - min).let { if (it <= 0.0) 1.0 else it }

        val n = prices.size
        val plotW = w - padL - padR
        val stepX = if (n > 1) plotW / (n - 1) else plotW

        fun yFor(value: Double): Float {
            val t = ((value - min) / vspan).toFloat()
            return bottom - t * chartH
        }

        // reference line
        if (refPrice > 0.0) {
            val ry = yFor(refPrice)
            canvas.drawLine(padL, ry, w - padR, ry, refPaint)
        }

        // price polyline
        val path = Path()
        for (i in 0 until n) {
            val x = padL + i * stepX
            val y = yFor(prices[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
