package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.aidarbreeze.alwayson.weather.WeatherHour
import com.aidarbreeze.alwayson.weather.WeatherInfo
import com.aidarbreeze.alwayson.weather.WeatherLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Monochrome weather panel (white on black, OLED theme) with two selectable
 * styles:
 *
 * STYLE_CLASSIC — a single screen with three sections:
 *  1. Current weather — city, a large thin temperature, a condition glyph
 *     and a short word.
 *  2. Hourly strip — one column per upcoming hour: time, glyph, temperature.
 *  3. Daily list — one row per day: weekday + day number, a temperature
 *     range bar drawn on the shared week scale, and the min/max values.
 *
 * STYLE_CURVE — a glanceable "dashboard":
 *  1. Header: city (left) and the current temperature + condition (right).
 *  2. A smooth 24-hour temperature curve with the "now" dot, the day's min
 *     and max marked on the curve, and time labels under it.
 *  3. A dense two-column week list (weekday, day number, condition glyph and
 *     the min/max range).
 */
class WeatherPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val STYLE_CLASSIC = 0
        const val STYLE_CURVE = 1
    }

    private var info: WeatherInfo? = null
    private var statusText = ""
    private var style = STYLE_CLASSIC

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

    // ---- curve style ----
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpf(2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val curveLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = dpf(10f)
    }

    private val headerTempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(16f)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val nowTempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(13f)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val weekGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(12f)
    }

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    /** Show a plain status line (loading / no data) instead of a forecast. */
    fun setStatus(text: String) {
        info = null
        statusText = text
        invalidate()
    }

    /** Show the forecast in the requested style. */
    fun show(data: WeatherInfo, style: Int = STYLE_CLASSIC) {
        info = data
        statusText = ""
        this.style = style
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

        if (style == STYLE_CURVE) {
            drawCurveMode(canvas, w, h, data)
        } else {
            drawClassicMode(canvas, w, h, data)
        }
    }

    // ================================================================
    // CLASSIC STYLE
    // ================================================================

    private fun drawClassicMode(canvas: Canvas, w: Float, h: Float, data: WeatherInfo) {
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

    // ================================================================
    // CURVE STYLE
    // ================================================================

    private fun drawCurveMode(canvas: Canvas, w: Float, h: Float, data: WeatherInfo) {
        val pad = dpf(16f)

        // ---------- header: city left, "21° ясно" right ----------
        val city = data.city.trim()
        if (city.isNotEmpty()) {
            cityPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(city, pad, dpf(16f), cityPaint)
        }
        val cond = WeatherLabel.of(data.codeNow)
        if (cond.isNotEmpty()) {
            condPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(cond, w - pad, dpf(16f), condPaint)
        }
        headerTempPaint.textAlign = Paint.Align.RIGHT
        val condW = if (cond.isNotEmpty()) condPaint.measureText(cond) else 0f
        val tempRight = w - pad - condW - (if (cond.isNotEmpty()) dpf(8f) else 0f)
        canvas.drawText("${data.tempNowC}°", tempRight, dpf(17f), headerTempPaint)

        val headerBottom = dpf(26f)
        canvas.drawRect(pad, headerBottom, w - pad, headerBottom + dpf(1f), sepPaint)

        // ---------- vertical layout (week list first, curve above it) ----------
        val nowMs = System.currentTimeMillis()
        val rowH = dpf(22f)
        val rows = 4
        val weekH = rows * rowH
        val hasWeek = h >= dpf(228f)
        val weekTop = h - dpf(4f) - weekH
        val curveTop = dpf(36f)
        val curveBottom = if (hasWeek) weekTop - dpf(26f) else h - dpf(10f)
        val timeBaseY = weekTop - dpf(16f)
        if (hasWeek) {
            canvas.drawRect(pad, weekTop - dpf(10f), w - pad, weekTop - dpf(9f), sepPaint)
        }

        // ---------- 24 h temperature curve ----------
        val windowMs = 24L * 3600_000L
        val pts = ArrayList<WeatherHour>()
        pts.add(WeatherHour(nowMs, data.tempNowC, data.codeNow))
        for (hh in data.hours) {
            if (hh.timeMs > nowMs && hh.timeMs <= nowMs + windowMs) pts.add(hh)
        }
        if (pts.size >= 2) {
            var tMin = Int.MAX_VALUE
            var tMax = Int.MIN_VALUE
            for (p in pts) {
                if (p.tempC < tMin) tMin = p.tempC
                if (p.tempC > tMax) tMax = p.tempC
            }
            if (tMax - tMin < 1) {
                tMin -= 1
                tMax += 1
            }
            val padV = (tMax - tMin) * 0.18f
            val cLo = tMin - padV
            val cHi = tMax + padV

            fun xFor(t: Long): Float =
                pad + (t - nowMs).toFloat() / windowMs.toFloat() * (w - 2 * pad)
            fun yFor(v: Int): Float =
                curveBottom - (v - cLo) / (cHi - cLo) * (curveBottom - curveTop)

            // curve polyline
            val path = Path()
            for (i in pts.indices) {
                val x = xFor(pts[i].timeMs)
                val y = yFor(pts[i].tempC)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, curvePaint)

            // min / max marked on the curve
            var iMin = 0
            var iMax = 0
            for (i in pts.indices) {
                if (pts[i].tempC < pts[iMin].tempC) iMin = i
                if (pts[i].tempC > pts[iMax].tempC) iMax = i
            }
            curveLabelPaint.textAlign = Paint.Align.CENTER
            val marginX = dpf(14f)
            val xMax = xFor(pts[iMax].timeMs).coerceIn(pad + marginX, w - pad - marginX)
            canvas.drawText(
                "${pts[iMax].tempC}°", xMax, yFor(pts[iMax].tempC) - dpf(6f), curveLabelPaint
            )
            val xMin = xFor(pts[iMin].timeMs).coerceIn(pad + marginX, w - pad - marginX)
            canvas.drawText(
                "${pts[iMin].tempC}°", xMin, yFor(pts[iMin].tempC) + dpf(12f), curveLabelPaint
            )

            // "now" dot at the left edge + the current temperature
            val yNow = yFor(data.tempNowC)
            canvas.drawCircle(pad, yNow, dpf(3f), barSegPaint)
            nowTempPaint.textAlign = Paint.Align.LEFT
            val yLbl = (yNow + dpf(4.5f))
                .coerceIn(curveTop + dpf(10f), curveBottom - dpf(2f))
            canvas.drawText("${data.tempNowC}°", pad + dpf(8f), yLbl, nowTempPaint)

            // time labels under the curve
            if (hasWeek) {
                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                curveLabelPaint.textAlign = Paint.Align.CENTER
                for (frac in floatArrayOf(0.25f, 0.5f, 0.75f)) {
                    val t = nowMs + (windowMs * frac).toLong()
                    canvas.drawText(timeFmt.format(Date(t)), xFor(t), timeBaseY, curveLabelPaint)
                }
            }
        }

        // ---------- dense two-column week list ----------
        val days = data.days
        if (hasWeek && days.isNotEmpty()) {
            var start = 0
            while (start < days.size && days[start].timeMs < nowMs &&
                !isSameDay(days[start].timeMs, nowMs)
            ) start++
            if (start >= days.size) start = maxOf(0, days.size - 1)
            val count = maxOf(1, minOf(rows * 2, days.size - start))

            val locale = Locale.getDefault()
            val wf = SimpleDateFormat("E", locale)
            val df = SimpleDateFormat("d", locale)
            val colW = (w - 2 * pad) / 2f

            for (i in 0 until count) {
                val d = days[start + i]
                val col = if (i < rows) 0 else 1
                val row = i % rows
                val cx = pad + col * colW
                val cy = weekTop + rowH * row
                val isToday = isSameDay(d.timeMs, nowMs)

                val label = "${wf.format(Date(d.timeMs))} ${df.format(Date(d.timeMs))}"
                val range = "${d.tMin}°/${d.tMax}°"
                val labelPaint = if (isToday) dayLabelBold else dayLabelPaint
                val rangePaint = if (isToday) dayRangeBold else dayRangePaint
                labelPaint.textAlign = Paint.Align.LEFT
                rangePaint.textAlign = Paint.Align.RIGHT
                weekGlyphPaint.textAlign = Paint.Align.LEFT

                val baseline = cy + rowH * 0.66f
                canvas.drawText(label, cx, baseline, labelPaint)
                canvas.drawText(WeatherLabel.glyph(d.code), cx + dpf(42f), baseline, weekGlyphPaint)
                canvas.drawText(range, cx + colW - dpf(6f), baseline, rangePaint)
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
