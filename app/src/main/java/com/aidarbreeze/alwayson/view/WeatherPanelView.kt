package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.aidarbreeze.alwayson.weather.WeatherInfo
import com.aidarbreeze.alwayson.weather.WeatherLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Minimalist monochrome weather panel (white on black, OLED theme).
 *
 * A single screen with three sections:
 *  1. Current weather — city, a large thin temperature, a condition glyph
 *     and a short word.
 *  2. Hourly strip — one column per upcoming hour: time, glyph, temperature.
 *  3. Daily list — one row per day: weekday + day number, a temperature
 *     range bar drawn on the shared week scale, and the min/max values.
 */
class WeatherPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var info: WeatherInfo? = null
    private var statusText = ""

    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        textSize = dpf(13f)
        textAlign = Paint.Align.CENTER
    }

    private val cityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = dpf(12f)
    }

    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(38f)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(22f)
    }

    private val condPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = dpf(14f)
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = dpf(10.5f)
    }

    private val hourlyGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(16f)
    }

    private val hourlyTempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(12f)
    }

    private val dayLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textSize = dpf(13f)
    }

    private val dayLabelBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(13f)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val dayRangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = dpf(12f)
    }

    private val dayRangeBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(12f)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF.toInt()
    }

    private val barSegPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2EFFFFFF.toInt()
    }

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    /** Show a plain status line (loading / no data) instead of a forecast. */
    fun setStatus(text: String) {
        info = null
        statusText = text
        invalidate()
    }

    /** Show the forecast. */
    fun show(data: WeatherInfo) {
        info = data
        statusText = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        val data = info
        if (data == null) {
            val msg = if (statusText.isNotEmpty()) statusText else "…"
            canvas.drawText(msg, w / 2f, h / 2f, statusPaint)
            return
        }

        val pad = dpf(16f)

        // ---------- 1. current weather ----------
        val city = data.city.trim()
        if (city.isNotEmpty()) {
            cityPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(city, w / 2f, dpf(15f), cityPaint)
        }

        bigPaint.textAlign = Paint.Align.LEFT
        glyphPaint.textAlign = Paint.Align.LEFT
        condPaint.textAlign = Paint.Align.LEFT

        val tempStr = "${data.tempNowC}°"
        val glyph = WeatherLabel.glyph(data.codeNow)
        val cond = WeatherLabel.of(data.codeNow)
        val gap = dpf(9f)

        val tempW = bigPaint.measureText(tempStr)
        val glyphW = if (glyph.isNotEmpty()) glyphPaint.measureText(glyph) else 0f
        val condW = if (cond.isNotEmpty()) condPaint.measureText(cond) else 0f
        val totalW = tempW + glyphW + (if (glyph.isNotEmpty()) gap else 0f) +
            condW + (if (cond.isNotEmpty()) gap else 0f)
        var x = (w - totalW) / 2f
        val baseY = dpf(52f)
        if (glyph.isNotEmpty()) {
            canvas.drawText(glyph, x, baseY, glyphPaint)
            x += glyphW + gap
        }
        canvas.drawText(tempStr, x, baseY, bigPaint)
        x += tempW + (if (cond.isNotEmpty()) gap else 0f)
        if (cond.isNotEmpty()) canvas.drawText(cond, x, baseY, condPaint)

        val headerBottom = dpf(64f)
        if (h <= headerBottom + dpf(12f)) return // too short for more sections

        canvas.drawRect(pad, headerBottom, w - pad, headerBottom + dpf(1f), sepPaint)

        // ---------- 2. hourly strip ----------
        val hourlyTop = headerBottom + dpf(12f)
        drawHourly(canvas, w, hourlyTop, data)
        val hourlyBottom = hourlyTop + dpf(54f)
        if (h <= hourlyBottom + dpf(26f)) return

        val daysTop = hourlyBottom + dpf(18f)
        canvas.drawRect(pad, daysTop - dpf(9f), w - pad, daysTop - dpf(8f), sepPaint)

        // ---------- 3. daily list ----------
        val availH = h - dpf(4f) - daysTop
        var rows = 7
        if (availH / 7f < dpf(22f)) rows = 5
        drawDaily(canvas, w, daysTop, availH / rows, rows, data)
    }

    // ---------- hourly strip ----------

    private fun drawHourly(canvas: Canvas, w: Float, top: Float, data: WeatherInfo) {
        val hours = data.hours
        if (hours.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        // Start with the next hour: the current temperature is already shown
        // large in the header, so repeating it would be noise.
        var start = 0
        while (start < hours.size && hours[start].timeMs <= nowMs) start++
        if (start >= hours.size) start = hours.size - 1
        val candidates = hours.subList(start, hours.size)
        if (candidates.isEmpty()) return

        // As many columns as fit, keeping each one readable.
        val usable = w - dpf(8f)
        val maxCols = maxOf(3, (usable / dpf(38f)).toInt())
        val n = minOf(candidates.size, maxCols)
        val colW = usable / n

        timePaint.textAlign = Paint.Align.CENTER
        hourlyGlyphPaint.textAlign = Paint.Align.CENTER
        hourlyTempPaint.textAlign = Paint.Align.CENTER

        val cal = Calendar.getInstance()
        for (i in 0 until n) {
            val cx = dpf(4f) + colW * (i + 0.5f)
            val hour = candidates[i]
            cal.timeInMillis = hour.timeMs
            val hh = String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY))
            canvas.drawText(hh, cx, top + dpf(11f), timePaint)
            canvas.drawText(WeatherLabel.glyph(hour.code), cx, top + dpf(33f), hourlyGlyphPaint)
            canvas.drawText("${hour.tempC}°", cx, top + dpf(49f), hourlyTempPaint)
        }
    }

    // ---------- daily list ----------

    private fun drawDaily(
        canvas: Canvas,
        w: Float,
        top: Float,
        rowH: Float,
        maxRows: Int,
        data: WeatherInfo
    ) {
        val days = data.days
        if (days.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        // Skip fully past days (stale cache); today and future stay.
        var start = 0
        while (start < days.size && days[start].timeMs < nowMs &&
            !isSameDay(days[start].timeMs, nowMs)
        ) start++
        if (start >= days.size) start = maxOf(0, days.size - 1)
        val count = maxOf(1, minOf(maxRows, days.size - start))

        // Shared temperature scale of the visible week, used by the range bars.
        var tLo = Int.MAX_VALUE
        var tHi = Int.MIN_VALUE
        for (i in start until start + count) {
            if (days[i].tMin < tLo) tLo = days[i].tMin
            if (days[i].tMax > tHi) tHi = days[i].tMax
        }
        if (tHi - tLo < 1) {
            tLo -= 1
            tHi += 1
        }

        val locale = Locale.getDefault()
        val wf = SimpleDateFormat("E", locale)
        val df = SimpleDateFormat("d", locale)
        val pad = dpf(16f)

        for (i in 0 until count) {
            val d = days[start + i]
            val rowTop = top + rowH * i
            val midY = rowTop + rowH / 2f
            val isToday = isSameDay(d.timeMs, nowMs)

            val label = "${wf.format(Date(d.timeMs))} ${df.format(Date(d.timeMs))}"
            val range = "${d.tMin}°/${d.tMax}°"
            val labelPaint = if (isToday) dayLabelBold else dayLabelPaint
            val rangePaint = if (isToday) dayRangeBold else dayRangePaint
            labelPaint.textAlign = Paint.Align.LEFT
            rangePaint.textAlign = Paint.Align.RIGHT

            canvas.drawText(label, pad, midY + dpf(4.5f), labelPaint)
            canvas.drawText(range, w - pad, midY + dpf(4f), rangePaint)

            // Thin range bar between the label and the numbers: the full track
            // is the week span, the lit segment is this day's min..max.
            val barX0 = pad + labelPaint.measureText(label) + dpf(12f)
            val barX1 = w - pad - rangePaint.measureText(range) - dpf(12f)
            if (barX1 - barX0 >= dpf(28f)) {
                val barY = midY - dpf(1f)
                val track = RectF(barX0, barY, barX1, barY + dpf(2.5f))
                canvas.drawRoundRect(track, dpf(1.25f), dpf(1.25f), barTrackPaint)
                val span = (tHi - tLo).toFloat()
                val f0 = ((d.tMin - tLo).toFloat() / span).coerceIn(0f, 1f)
                val f1 = ((d.tMax - tLo).toFloat() / span).coerceIn(0f, 1f)
                val s0 = barX0 + (barX1 - barX0) * f0
                val s1 = barX0 + (barX1 - barX0) * f1
                if (s1 - s0 >= dpf(4f)) {
                    val seg = RectF(s0, barY - dpf(0.5f), s1, barY + dpf(3f))
                    canvas.drawRoundRect(seg, dpf(1.25f), dpf(1.25f), barSegPaint)
                }
            }
        }
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}
