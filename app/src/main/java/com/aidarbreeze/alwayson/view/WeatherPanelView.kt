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
import com.aidarbreeze.alwayson.Prefs
import com.aidarbreeze.alwayson.R
import com.aidarbreeze.alwayson.weather.WeatherHour
import com.aidarbreeze.alwayson.weather.WeatherInfo
import com.aidarbreeze.alwayson.weather.WeatherLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * Monochrome weather panel (white on black, OLED theme) with ten selectable
 * styles (0-9, see the companion constants):
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
        const val STYLE_MINIMAL = 2
        const val STYLE_FORECAST = 3 // enlarged hourly strip
        const val STYLE_DAILY = 4    // enlarged 7-day list
        const val STYLE_HERO = 5     // big temperature
        const val STYLE_MONO = 6     // strict white/gray, numbers only
        const val STYLE_SUN = 7      // weather + sun cycle
        const val STYLE_SPLIT = 8    // current | next hours
        const val STYLE_PREMIUM = 9  // compact AMOLED card
    }

    private var info: WeatherInfo? = null
    private var statusText = ""
    private var style = STYLE_CLASSIC
    private var stale = false

    private val stalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt()
        textSize = dpf(9f)
        textAlign = Paint.Align.RIGHT
    }

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

    // Small meta line: "feels like" and sunrise/sunset.
    private val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = dpf(10f)
    }

    // ---- premium card (style 9) ----
    private val premCardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF11151B.toInt()
        style = Paint.Style.FILL
    }
    private val premCardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF26303B.toInt()
        style = Paint.Style.STROKE
    }
    private val premTempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F7FA.toInt()
        textSize = dpf(26f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val premCityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F7FA.toInt()
        textSize = dpf(12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
    }
    private val premCondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B93A1.toInt()
        textSize = dpf(11f)
        textAlign = Paint.Align.RIGHT
    }
    private val premDetailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF59616D.toInt()
        textSize = dpf(10f)
        textAlign = Paint.Align.LEFT
    }

    // Temperature unit resolved once per frame (see onDraw); the old code
    // queried Prefs for EVERY temperature string of EVERY draw.
    private var unitF = false

    // Reused scratch paints so the hero / sun / split modes never allocate
    // Paint objects inside onDraw (GC churn = jank).
    private val heroBig = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heroGlyph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(18f)
        textAlign = Paint.Align.CENTER
    }
    private val sunDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFBBF24.toInt()
        style = Paint.Style.FILL
    }
    private val sunArcRect = RectF()
    private val splitBig = Paint(Paint.ANTI_ALIAS_FLAG)
    private val splitCond = Paint(Paint.ANTI_ALIAS_FLAG)
    private val splitGlyph = Paint(Paint.ANTI_ALIAS_FLAG)

    // Premium-mode weather icon, reloaded only when the condition changes
    // (resources.getDrawable on every frame is needlessly expensive).
    private var premIconCode = -1
    private var premIcon: android.graphics.drawable.Drawable? = null

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    /** Display a temperature with the user's unit (C default, F optional).
     *  Conversion is display-only; the stored data stays Celsius. Rounded to
     *  the nearest degree (truncation would be off by 1° in some cases). */
    private fun t(c: Int): String {
        if (!unitF) return "$c°"
        val fahrenheit = round(c * 9f / 5f + 32f).toInt()
        return "${fahrenheit}°"
    }

    /** The forecast point's timezone. All human-readable times (hour labels,
     *  "today" detection, sun times) are formatted in THIS zone, because the
     *  API returns local times for the forecast location (timezone=auto). */
    private fun zone(data: WeatherInfo): TimeZone =
        if (data.timezoneId.isNotBlank()) TimeZone.getTimeZone(data.timezoneId)
        else TimeZone.getDefault()

    /** Russian UI strings when the device locale is Russian (the weather
     *  panel historically hardcoded Russian everywhere). */
    private fun isRu(): Boolean =
        Locale.getDefault().language.equals("ru", ignoreCase = true)

    /** "↑ 06:12 ↓ 20:41" (empty when the API gave no sun times). Times are in
     *  the forecast city's zone, not the device zone. */
    private fun sunLine(data: WeatherInfo): String {
        if (data.sunriseMs <= 0L && data.sunsetMs <= 0L) return ""
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = zone(data)
        }
        val sb = StringBuilder()
        if (data.sunriseMs > 0L) sb.append("↑ ").append(tf.format(Date(data.sunriseMs)))
        if (data.sunsetMs > 0L) {
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append("↓ ").append(tf.format(Date(data.sunsetMs)))
        }
        return sb.toString()
    }

    /** "feels like" fragment, empty when not provided or equal to the real
     *  temp. 0° is a real value, so absence is null (not 0). */
    private fun feelsText(data: WeatherInfo): String {
        val feels = data.feelsNowC ?: return ""
        if (feels == data.tempNowC) return ""
        return if (isRu()) "ощущ. ${t(feels)}" else "feels ${t(feels)}"
    }

    /** Small meta line: "ощущ. N°" + "↑ HH:MM ↓ HH:MM" (either may be empty). */
    private fun buildMetaLine(data: WeatherInfo): String = buildString {
        val feels = feelsText(data)
        if (feels.isNotEmpty()) append(feels)
        val sun = sunLine(data)
        if (sun.isNotEmpty()) {
            if (isNotEmpty()) append("  ")
            append(sun)
        }
    }

    /** Show a plain status line (loading / no data) instead of a forecast. */
    fun setStatus(text: String) {
        info = null
        statusText = text
        invalidate()
    }

    /** Show the forecast in the requested style. [stale] marks a last-known
     *  forecast that is shown while a background refresh failed. */
    fun show(data: WeatherInfo, style: Int = STYLE_CLASSIC, stale: Boolean = false) {
        info = data
        statusText = ""
        this.style = style
        this.stale = stale
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

        unitF = Prefs.tempUnit(context) == 1
        // The forecast / daily / mono modes resize the SHARED paints below;
        // restore the defaults on every frame so switching styles never
        // inherits another mode's text sizes (previously e.g. daily -> classic
        // left the classic panel with the wrong sizes).
        timePaint.textSize = dpf(10.5f)
        hourlyGlyphPaint.textSize = dpf(16f)
        hourlyTempPaint.textSize = dpf(12f)
        dayLabelPaint.textSize = dpf(13f)
        dayLabelBold.textSize = dpf(13f)
        dayRangePaint.textSize = dpf(12f)
        dayRangeBold.textSize = dpf(12f)
        weekGlyphPaint.textSize = dpf(12f)

        when (style) {
            STYLE_CURVE -> drawCurveMode(canvas, w, h, data)
            STYLE_MINIMAL -> drawMinimalMode(canvas, w, h, data)
            STYLE_FORECAST -> drawForecastMode(canvas, w, h, data)
            STYLE_DAILY -> drawDailyMode(canvas, w, h, data)
            STYLE_HERO -> drawHeroMode(canvas, w, h, data)
            STYLE_MONO -> drawMonoMode(canvas, w, h, data)
            STYLE_SUN -> drawSunMode(canvas, w, h, data)
            STYLE_SPLIT -> drawSplitMode(canvas, w, h, data)
            STYLE_PREMIUM -> drawPremiumMode(canvas, w, h, data)
            else -> drawClassicMode(canvas, w, h, data)
        }

        // Last-known forecast shown after a failed refresh — a subtle note.
        if (stale) {
            val staleMsg = if (isRu()) "обновлено ранее" else "updated earlier"
            canvas.drawText(staleMsg, w - dpf(16f), h - dpf(6f), stalePaint)
        }
    }

    // ================================================================
    // CLASSIC STYLE
    // ================================================================

    private fun drawClassicMode(canvas: Canvas, w: Float, h: Float, data: WeatherInfo) {
        val pad = dpf(16f)

        // ---------- 1. current weather ----------
        // Row 1: city (left) and small meta (right): feels-like + sun times.
        // Row 2: glyph + temperature + condition word (unchanged layout).
        val city = data.city.trim()
        if (city.isNotEmpty()) {
            cityPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(city, pad, dpf(15f), cityPaint)
        }
        val meta = buildMetaLine(data)
        if (meta.isNotEmpty()) {
            metaPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(meta, w - pad, dpf(15f), metaPaint)
        }

        bigPaint.textAlign = Paint.Align.LEFT
        glyphPaint.textAlign = Paint.Align.LEFT
        condPaint.textAlign = Paint.Align.LEFT

        val tempStr = "${t(data.tempNowC)}"
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

        val cal = Calendar.getInstance(zone(data))
        for (i in 0 until n) {
            val cx = dpf(4f) + colW * (i + 0.5f)
            val hour = candidates[i]
            cal.timeInMillis = hour.timeMs
            val hh = String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY))
            canvas.drawText(hh, cx, top + dpf(11f), timePaint)
            canvas.drawText(WeatherLabel.glyph(hour.code), cx, top + dpf(33f), hourlyGlyphPaint)
            canvas.drawText("${t(hour.tempC)}", cx, top + dpf(49f), hourlyTempPaint)
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
            !isSameDay(days[start].timeMs, nowMs, zone(data))
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
        val wf = SimpleDateFormat("E", locale).apply { timeZone = zone(data) }
        val df = SimpleDateFormat("d", locale).apply { timeZone = zone(data) }
        val pad = dpf(16f)

        for (i in 0 until count) {
            val d = days[start + i]
            val rowTop = top + rowH * i
            val midY = rowTop + rowH / 2f
            val isToday = isSameDay(d.timeMs, nowMs, zone(data))

            val label = "${wf.format(Date(d.timeMs))} ${df.format(Date(d.timeMs))}"
            val range = "${t(d.tMin)}/${t(d.tMax)}"
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
        canvas.drawText("${t(data.tempNowC)}", tempRight, dpf(17f), headerTempPaint)

        // Row 2 (centered, small): "feels like" + sunrise/sunset.
        val meta = buildMetaLine(data)
        if (meta.isNotEmpty()) {
            metaPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(meta, w / 2f, dpf(32f), metaPaint)
        }

        val headerBottom = dpf(40f)
        canvas.drawRect(pad, headerBottom, w - pad, headerBottom + dpf(1f), sepPaint)

        // ---------- vertical layout (week list first, curve above it) ----------
        val nowMs = System.currentTimeMillis()
        val rowH = dpf(22f)
        val rows = 4
        val weekH = rows * rowH
        val hasWeek = h >= dpf(228f)
        val weekTop = h - dpf(4f) - weekH
        val curveTop = dpf(50f)
        val curveBottom = if (hasWeek) weekTop - dpf(26f) else h - dpf(10f)
        val curveOk = curveBottom - curveTop >= dpf(30f)
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
        if (pts.size >= 2 && curveOk) {
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
                "${t(pts[iMax].tempC)}", xMax, yFor(pts[iMax].tempC) - dpf(6f), curveLabelPaint
            )
            val xMin = xFor(pts[iMin].timeMs).coerceIn(pad + marginX, w - pad - marginX)
            // Clamp the label INSIDE the plot too: on a short panel the
            // unclamped "+12dp below the point" dipped under curveBottom
            // into the week list.
            val yMin = (yFor(pts[iMin].tempC) + dpf(12f))
                .coerceAtMost(curveBottom - dpf(2f))
            canvas.drawText(
                "${t(pts[iMin].tempC)}", xMin, yMin, curveLabelPaint
            )

            // "now" dot at the left edge + the current temperature
            val yNow = yFor(data.tempNowC)
            canvas.drawCircle(pad, yNow, dpf(3f), barSegPaint)
            nowTempPaint.textAlign = Paint.Align.LEFT
            val yLbl = (yNow + dpf(4.5f))
                .coerceIn(curveTop + dpf(10f), curveBottom - dpf(2f))
            canvas.drawText("${t(data.tempNowC)}", pad + dpf(8f), yLbl, nowTempPaint)

            // time labels under the curve
            if (hasWeek) {
                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = zone(data) }
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
                !isSameDay(days[start].timeMs, nowMs, zone(data))
            ) start++
            if (start >= days.size) start = maxOf(0, days.size - 1)
            val count = maxOf(1, minOf(rows * 2, days.size - start))

            val locale = Locale.getDefault()
            val wf = SimpleDateFormat("E", locale).apply { timeZone = zone(data) }
            val df = SimpleDateFormat("d", locale).apply { timeZone = zone(data) }
            val colW = (w - 2 * pad) / 2f

            for (i in 0 until count) {
                val d = days[start + i]
                val col = if (i < rows) 0 else 1
                val row = i % rows
                val cx = pad + col * colW
                val cy = weekTop + rowH * row
                val isToday = isSameDay(d.timeMs, nowMs, zone(data))

                val label = "${wf.format(Date(d.timeMs))} ${df.format(Date(d.timeMs))}"
                val range = "${t(d.tMin)}/${t(d.tMax)}"
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

    // ================================================================
    // MINIMAL — city, glyph + temperature, meta. Nothing else.
    // ================================================================

    private fun drawMinimalMode(canvas: Canvas, w: Float, h: Float, data: WeatherInfo) {
        val city = data.city.trim()
        if (city.isNotEmpty()) {
            cityPaint.textAlign = Paint.Align.CENTER
            val cityBase = (h / 2f - dpf(54f)).coerceAtLeast(dpf(14f))
            canvas.drawText(city, w / 2f, cityBase, cityPaint)
        }
        bigPaint.textAlign = Paint.Align.LEFT
        glyphPaint.textAlign = Paint.Align.LEFT
        val tempStr = "${t(data.tempNowC)}"
        val glyph = WeatherLabel.glyph(data.codeNow)
        val gap = dpf(10f)
        val tempW = bigPaint.measureText(tempStr)
        val glyphW = if (glyph.isNotEmpty()) glyphPaint.measureText(glyph) else 0f
        val total = tempW + glyphW + (if (glyph.isNotEmpty()) gap else 0f)
        var x = (w - total) / 2f
        val baseY = h / 2f + dpf(8f)
        if (glyph.isNotEmpty()) {
            canvas.drawText(glyph, x, baseY, glyphPaint)
            x += glyphW + gap
        }
        canvas.drawText(tempStr, x, baseY, bigPaint)
        val meta = buildMetaLine(data)
        if (meta.isNotEmpty()) {
            metaPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(meta, w / 2f, h / 2f + dpf(36f), metaPaint)
        }
    }

    // ================================================================
    // FORECAST STRIP — small header + an enlarged hourly strip.
    // ================================================================

    private fun drawForecastMode(canvas: Canvas, w: Float, h: Float, data: WeatherInfo) {
        val pad = dpf(16f)
        val city = data.city.trim()
        if (city.isNotEmpty()) {
            cityPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(city, pad, dpf(18f), cityPaint)
        }
        headerTempPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${t(data.tempNowC)}", w - pad, dpf(19f), headerTempPaint)

        val hours = data.hours
        if (hours.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        var start = 0
        while (start < hours.size && hours[start].timeMs <= nowMs) start++
        if (start >= hours.size) start = hours.size - 1
        val candidates = hours.subList(start, hours.size)
        if (candidates.isEmpty()) return

        val usable = w - dpf(8f)
        val maxCols = maxOf(4, (usable / dpf(34f)).toInt())
        val n = minOf(candidates.size, maxCols)
        val colW = usable / n

        val top = dpf(42f)
        val ch = h - top - dpf(12f)
        if (ch < dpf(48f)) return // header drawn; no room for the strip
        timePaint.textSize = ch * 0.16f
        timePaint.textAlign = Paint.Align.CENTER
        hourlyGlyphPaint.textSize = ch * 0.36f
        hourlyGlyphPaint.textAlign = Paint.Align.CENTER
        hourlyTempPaint.textSize = ch * 0.17f
        hourlyTempPaint.textAlign = Paint.Align.CENTER

        val cal = Calendar.getInstance(zone(data))
        for (i in 0 until n) {
            val cx = dpf(4f) + colW * (i + 0.5f)
            val hour = candidates[i]
            cal.timeInMillis = hour.timeMs
            val hh = String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY))
            canvas.drawText(hh, cx, top + ch * 0.18f, timePaint)
            canvas.drawText(WeatherLabel.glyph(hour.code), cx, top + ch * 0.55f, hourlyGlyphPaint)
            canvas.drawText("${t(hour.tempC)}", cx, top + ch * 0.84f, hourlyTempPaint)
        }
    }

    // ================================================================
    // DAILY — an enlarged 7-day list with range bars.
    // ================================================================

    private fun drawDailyMode(canvas: Canvas, w: Float, h: Float, data: WeatherInfo) {
        val days = data.days
        if (days.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        var start = 0
        while (start < days.size && days[start].timeMs < nowMs &&
            !isSameDay(days[start].timeMs, nowMs, zone(data))
        ) start++
        if (start >= days.size) start = maxOf(0, days.size - 1)
        // Never squeeze rows below 20dp: show fewer days instead of overlap.
        val fitRows = ((h - dpf(28f)) / dpf(20f)).toInt().coerceAtLeast(1)
        val count = maxOf(1, minOf(7, days.size - start, fitRows))

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
        val wf = SimpleDateFormat("E", locale).apply { timeZone = zone(data) }
        val df = SimpleDateFormat("d", locale).apply { timeZone = zone(data) }
        val pad = dpf(16f)
        val top = dpf(16f)
        val rowH = (h - dpf(28f)) / count
        val labelSize = (rowH * 0.42f).coerceIn(12f, 24f)
        val glyphSize = (rowH * 0.5f).coerceIn(14f, 28f)
        val rangeSize = (rowH * 0.4f).coerceIn(12f, 22f)
        dayLabelPaint.textSize = labelSize
        dayLabelBold.textSize = labelSize
        dayRangePaint.textSize = rangeSize
        dayRangeBold.textSize = rangeSize
        weekGlyphPaint.textSize = glyphSize

        for (i in 0 until count) {
            val d = days[start + i]
            val midY = top + rowH * i + rowH / 2f
            val isToday = isSameDay(d.timeMs, nowMs, zone(data))
            val label = "${wf.format(Date(d.timeMs))} ${df.format(Date(d.timeMs))}"
            val range = "${t(d.tMin)}/${t(d.tMax)}"
            val lp = if (isToday) dayLabelBold else dayLabelPaint
            val rp = if (isToday) dayRangeBold else dayRangePaint
            lp.textAlign = Paint.Align.LEFT
            rp.textAlign = Paint.Align.RIGHT
            weekGlyphPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(label, pad, midY + labelSize * 0.35f, lp)
            canvas.drawText(
                WeatherLabel.glyph(d.code),
                pad + dpf(78f),
                midY + glyphSize * 0.35f,
                weekGlyphPaint
            )
            canvas.drawText(range, w - pad, midY + rangeSize * 0.35f, rp)
            val barX0 = pad + dpf(78f) + glyphSize + dpf(10f)
            val barX1 = w - pad - rp.measureText(range) - dpf(10f)
            if (barX1 - barX0 >= dpf(24f)) {
                val barY = midY - dpf(1f)
                canvas.drawRoundRect(
                    RectF(barX0, barY, barX1, barY + dpf(2.5f)), dpf(1.25f), dpf(1.25f),
                    barTrackPaint
                )
                val span = (tHi - tLo).toFloat()
                val f0 = ((d.tMin - tLo).toFloat() / span).coerceIn(0f, 1f)
                val f1 = ((d.tMax - tLo).toFloat() / span).coerceIn(0f, 1f)
                val s0 = barX0 + (barX1 - barX0) * f0
                val s1 = barX0 + (barX1 - barX0) * f1
                if (s1 - s0 >= dpf(4f)) {
                    canvas.drawRoundRect(
                        RectF(s0, barY - dpf(0.5f), s1, barY + dpf(3f)),
                        dpf(1.25f), dpf(1.25f), barSegPaint
                    )
                }
            }
        }
    }

    // ================================================================
    // HERO — a giant temperature with today's range and an hourly
    // mini-strip. (Minimal owns the quiet centred row; Hero goes big.)
    // ================================================================

    private fun drawHeroMode(canvas: Canvas, w: Float, h: Float, data: WeatherInfo) {
        val pad = dpf(16f)
        val city = data.city.trim()
        if (city.isNotEmpty()) {
            cityPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(city, pad, dpf(18f), cityPaint)
        }
        val cond = WeatherLabel.of(data.codeNow)
        if (cond.isNotEmpty()) {
            condPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(cond, w - pad, dpf(18f), condPaint)
        }

        val big = heroBig
        big.set(bigPaint)
        big.textSize = min(w * 0.52f, h * 0.58f).coerceAtMost(dpf(120f))
        big.color = Color.WHITE
        val tempStr = t(data.tempNowC)
        // Shrink until the number fits the width (a few iterations max).
        var guard = 0
        while (guard++ < 12 && big.measureText(tempStr) > w - pad * 2f &&
            big.textSize > dpf(24f)
        ) {
            big.textSize -= dpf(4f)
        }
        val glyph = WeatherLabel.glyph(data.codeNow)
        val midY = h * 0.44f
        if (glyph.isNotEmpty()) {
            val g = heroGlyph
            g.set(glyphPaint)
            g.textSize = big.textSize * 0.36f
            val gap = dpf(14f)
            val tempW = big.measureText(tempStr)
            val glyphW = g.measureText(glyph)
            val total = glyphW + gap + tempW
            var x = (w - total) / 2f
            g.textAlign = Paint.Align.LEFT
            big.textAlign = Paint.Align.LEFT
            val gf = g.fontMetrics
            val bf = big.fontMetrics
            canvas.drawText(glyph, x, midY - (gf.ascent + gf.descent) / 2f, g)
            canvas.drawText(tempStr, x + glyphW + gap, midY - (bf.ascent + bf.descent) / 2f, big)
        } else {
            big.textAlign = Paint.Align.CENTER
            val bf = big.fontMetrics
            canvas.drawText(tempStr, w / 2f, midY - (bf.ascent + bf.descent) / 2f, big)
        }

        // Today's range centred under the giant number.
        val nowMs = System.currentTimeMillis()
        val today = data.days.firstOrNull { isSameDay(it.timeMs, nowMs, zone(data)) }
        if (today != null) {
            dayRangePaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "${t(today.tMin)}/${t(today.tMax)}", w / 2f,
                midY + big.textSize * 0.42f, dayRangePaint
            )
        }

        // Hourly mini-strip along the bottom: the next 6 temperatures.
        val hours = data.hours
        if (hours.isNotEmpty()) {
            var start = 0
            while (start < hours.size && hours[start].timeMs <= nowMs) start++
            if (start >= hours.size) start = hours.size - 1
            val n = minOf(6, hours.size - start)
            if (n > 0) {
                val usable = w - pad * 2f
                hourlyTempPaint.textAlign = Paint.Align.CENTER
                timePaint.textAlign = Paint.Align.CENTER
                val cal = Calendar.getInstance(zone(data))
                for (i in 0 until n) {
                    val cx = pad + usable * (i + 0.5f) / n
                    val hour = hours[start + i]
                    cal.timeInMillis = hour.timeMs
                    val hh = String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY))
                    canvas.drawText(hh, cx, h - dpf(30f), timePaint)
                    canvas.drawText(t(hour.tempC), cx, h - dpf(12f), hourlyTempPaint)
                }
            }
        }
    }

    // ================================================================
    // MONOCHROME — a dense numbers-only dashboard: no words, no glyphs.
    // A two-row hourly grid on top, compact day rows below.
    // ================================================================

    private fun drawMonoMode(canvas: Canvas, w: Float, h: Float, data: WeatherInfo) {
        val pad = dpf(16f)
        val city = data.city.trim()
        if (city.isNotEmpty()) {
            cityPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(city, pad, dpf(18f), cityPaint)
        }
        headerTempPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(t(data.tempNowC), w - pad, dpf(19f), headerTempPaint)
        canvas.drawRect(pad, dpf(30f), w - pad, dpf(31f), sepPaint)

        // Hourly grid: up to 12 "HH temp" cells in rows of 6.
        val hours = data.hours
        var gridBottom = dpf(40f)
        if (hours.isNotEmpty() && h > dpf(120f)) {
            val nowMs = System.currentTimeMillis()
            var start = 0
            while (start < hours.size && hours[start].timeMs <= nowMs) start++
            if (start >= hours.size) start = hours.size - 1
            val n = minOf(12, hours.size - start)
            if (n > 0) {
                val cols = minOf(6, n)
                val usable = w - dpf(8f)
                val colW = usable / cols
                val rowH = dpf(20f)
                val rows = (n + cols - 1) / cols
                hourlyTempPaint.textAlign = Paint.Align.CENTER
                val cal = Calendar.getInstance(zone(data))
                for (i in 0 until n) {
                    val cx = dpf(4f) + colW * (i % cols + 0.5f)
                    val cy = dpf(46f) + rowH * (i / cols)
                    val hour = hours[start + i]
                    cal.timeInMillis = hour.timeMs
                    val hh = String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY))
                    canvas.drawText("$hh ${t(hour.tempC)}", cx, cy, hourlyTempPaint)
                }
                gridBottom = dpf(46f) + rowH * (rows - 1) + dpf(14f)
            }
        }

        // Compact day rows fill the rest (up to 7, shrinking to fit).
        val days = data.days
        if (days.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        var start = 0
        while (start < days.size && days[start].timeMs < nowMs &&
            !isSameDay(days[start].timeMs, nowMs, zone(data))
        ) start++
        if (start >= days.size) start = maxOf(0, days.size - 1)
        val top = gridBottom + dpf(8f)
        val fitRows = ((h - dpf(8f) - top) / dpf(16f)).toInt().coerceAtLeast(1)
        val count = maxOf(1, minOf(7, days.size - start, fitRows))
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
        val wf = SimpleDateFormat("E", locale).apply { timeZone = zone(data) }
        val df = SimpleDateFormat("d", locale).apply { timeZone = zone(data) }
        val rowH = (h - dpf(8f) - top) / count
        dayLabelPaint.textSize = (rowH * 0.52f).coerceIn(9f, 13f)
        dayLabelBold.textSize = dayLabelPaint.textSize
        dayRangePaint.textSize = (rowH * 0.48f).coerceIn(9f, 12f)
        dayRangeBold.textSize = dayRangePaint.textSize
        for (i in 0 until count) {
            val d = days[start + i]
            val midY = top + rowH * i + rowH / 2f
            val isToday = isSameDay(d.timeMs, nowMs, zone(data))
            val label = "${wf.format(Date(d.timeMs))} ${df.format(Date(d.timeMs))}"
            val range = "${t(d.tMin)}/${t(d.tMax)}"
            val lp = if (isToday) dayLabelBold else dayLabelPaint
            val rp = if (isToday) dayRangeBold else dayRangePaint
            lp.textAlign = Paint.Align.LEFT
            rp.textAlign = Paint.Align.RIGHT
            canvas.drawText(label, pad, midY + rowH * 0.18f, lp)
            canvas.drawText(range, w - pad, midY + rowH * 0.18f, rp)
            val barX0 = pad + lp.measureText(label) + dpf(12f)
            val barX1 = w - pad - rp.measureText(range) - dpf(12f)
            if (barX1 - barX0 >= dpf(24f)) {
                val barY = midY - dpf(1f)
                canvas.drawRoundRect(
                    RectF(barX0, barY, barX1, barY + dpf(2.5f)), dpf(1.25f), dpf(1.25f),
                    barTrackPaint
                )
                val span = (tHi - tLo).toFloat()
                val f0 = ((d.tMin - tLo).toFloat() / span).coerceIn(0f, 1f)
                val f1 = ((d.tMax - tLo).toFloat() / span).coerceIn(0f, 1f)
                val s0 = barX0 + (barX1 - barX0) * f0
                val s1 = barX0 + (barX1 - barX0) * f1
                if (s1 - s0 >= dpf(4f)) {
                    canvas.drawRoundRect(
                        RectF(s0, barY - dpf(0.5f), s1, barY + dpf(3f)),
                        dpf(1.25f), dpf(1.25f), barSegPaint
                    )
                }
            }
        }
    }

    // ================================================================
    // WEATHER + SUN CYCLE — temperature plus a real sun-path arc with
    // sunrise/sunset and day length (a plain summary when the forecast
    // carries no sun times).
    // ================================================================

    private fun drawSunMode(canvas: Canvas, w: Float, h: Float, data: WeatherInfo) {
        val pad = dpf(16f)
        val city = data.city.trim()
        if (city.isNotEmpty()) {
            cityPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(city, pad, dpf(18f), cityPaint)
        }
        headerTempPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(t(data.tempNowC), w - pad, dpf(19f), headerTempPaint)

        val riseMs = data.sunriseMs
        val setMs = data.sunsetMs
        if (riseMs <= 0L || setMs <= riseMs || h < dpf(150f)) {
            // No sun times (or no room for the arc): a plain centred
            // summary instead.
            bigPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(t(data.tempNowC), w / 2f, h * 0.44f, bigPaint)
            val cond = WeatherLabel.of(data.codeNow)
            if (cond.isNotEmpty()) {
                condPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(cond, w / 2f, h * 0.44f + dpf(26f), condPaint)
            }
            val feels = feelsText(data)
            if (feels.isNotEmpty()) {
                metaPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(feels, w / 2f, h * 0.44f + dpf(48f), metaPaint)
            }
            return
        }

        // Sun-path arc: sunrise (left) to sunset (right) over a horizon.
        val top = dpf(34f)
        val arcH = h - top - dpf(64f)
        val arcBase = top + arcH
        val arcL = pad
        val arcR = w - pad
        val arcW = arcR - arcL
        canvas.drawLine(arcL, arcBase, arcR, arcBase, sepPaint)
        sunArcRect.set(arcL, arcBase - arcH, arcR, arcBase + arcH)
        canvas.drawArc(sunArcRect, 180f, 180f, false, curvePaint)
        // The travelling sun dot (dimmed and parked at night).
        val nowMs = System.currentTimeMillis()
        val p = ((nowMs - riseMs).toFloat() / (setMs - riseMs).toFloat())
            .coerceIn(0f, 1f)
        val day = nowMs in riseMs..setMs
        val ang = (PI * (1.0 - p)).toFloat()
        val sunX = (arcL + arcR) / 2f + cos(ang) * arcW / 2f
        val sunY = arcBase - sin(ang) * arcH
        sunDotPaint.alpha = if (day) 255 else 90
        canvas.drawCircle(sunX, sunY, dpf(if (day) 6f else 4.5f), sunDotPaint)
        sunDotPaint.alpha = 255
        // Sunrise/sunset under the horizon ends, day length centred below.
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = zone(data)
        }
        metaPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("↑ " + tf.format(Date(riseMs)), arcL, arcBase + dpf(18f), metaPaint)
        metaPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("↓ " + tf.format(Date(setMs)), arcR, arcBase + dpf(18f), metaPaint)
        val dayLen = setMs - riseMs
        val dlStr = String.format(
            Locale.US, "%d:%02d",
            (dayLen / 3600_000L).toInt(), ((dayLen % 3600_000L) / 60_000L).toInt()
        )
        metaPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            (if (isRu()) "день " else "day ") + dlStr,
            w / 2f, arcBase + dpf(36f), metaPaint
        )
        // Condition + feels-like along the bottom.
        val bottom = buildString {
            val cond = WeatherLabel.of(data.codeNow)
            if (cond.isNotEmpty()) append(cond)
            val feels = feelsText(data)
            if (feels.isNotEmpty()) {
                if (isNotEmpty()) append("  ·  ")
                append(feels)
            }
        }
        if (bottom.isNotEmpty()) {
            condPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(bottom, w / 2f, h - dpf(10f), condPaint)
        }
    }

    // ================================================================
    // COMPACT SPLIT — current weather left, next hours right.
    // ================================================================

    private fun drawSplitMode(canvas: Canvas, w: Float, h: Float, data: WeatherInfo) {
        val pad = dpf(16f)
        val midX = w * 0.5f
        // Vertical divider.
        canvas.drawLine(midX, pad, midX, h - pad, sepPaint)

        // ---- left: current ----
        val city = data.city.trim()
        if (city.isNotEmpty()) {
            cityPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(city, pad, dpf(20f), cityPaint)
        }
        val big2 = splitBig
        big2.set(bigPaint)
        big2.textSize = dpf(30f)
        big2.textAlign = Paint.Align.LEFT
        val glyph = WeatherLabel.glyph(data.codeNow)
        val cond = WeatherLabel.of(data.codeNow)
        val cond2 = splitCond
        cond2.set(condPaint)
        cond2.textSize = dpf(13f)
        var y = h / 2f - dpf(4f)
        canvas.drawText("${t(data.tempNowC)}", pad, y, big2)
        var tx = pad + big2.measureText("${t(data.tempNowC)}") + dpf(8f)
        if (glyph.isNotEmpty()) {
            val g2 = splitGlyph
            g2.set(glyphPaint)
            g2.textSize = dpf(20f)
            g2.textAlign = Paint.Align.LEFT
            canvas.drawText(glyph, tx, y, g2)
            tx += g2.measureText(glyph) + dpf(8f)
        }
        if (cond.isNotEmpty()) {
            cond2.textAlign = Paint.Align.LEFT
            // The left half is narrow: skip a condition word that would run
            // past the middle divider into the hours column.
            if (tx + cond2.measureText(cond) <= midX - dpf(4f)) {
                canvas.drawText(cond, tx, y - dpf(2f), cond2)
            }
        }
        val meta = buildMetaLine(data)
        if (meta.isNotEmpty()) {
            metaPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(meta, pad, y + dpf(20f), metaPaint)
        }

        // ---- right: next hours, compact rows ----
        val hours = data.hours
        if (hours.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        var start = 0
        while (start < hours.size && hours[start].timeMs <= nowMs) start++
        if (start >= hours.size) start = hours.size - 1
        val candidates = hours.subList(start, hours.size)
        val fitRows = ((h - dpf(28f)) / dpf(20f)).toInt().coerceIn(0, 4)
        val n = minOf(fitRows, candidates.size)
        if (n == 0) return
        val rightX = midX + dpf(12f)
        val rowH = (h - dpf(24f)) / 4f
        timePaint.textAlign = Paint.Align.LEFT
        hourlyTempPaint.textAlign = Paint.Align.RIGHT
        val cal = Calendar.getInstance(zone(data))
        for (i in 0 until n) {
            val hour = candidates[i]
            val cy = dpf(14f) + rowH * i + rowH * 0.5f
            cal.timeInMillis = hour.timeMs
            val hh = String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY))
            canvas.drawText(hh, rightX, cy + dpf(4.5f), timePaint)
            canvas.drawText(
                WeatherLabel.glyph(hour.code),
                rightX + dpf(46f),
                cy + dpf(5f),
                weekGlyphPaint
            )
            canvas.drawText("${t(hour.tempC)}", w - pad, cy + dpf(4.5f), hourlyTempPaint)
        }
    }

    // ================================================================
    // PREMIUM CARD — compact AMOLED card: icon left, temperature +
    // feels-like beside it, city / condition / details right-aligned.
    // Only real values are shown; nothing empty is ever drawn.
    // ================================================================

    /** Vector icon for a WMO code, or null when nothing fits (unknown code). */
    private fun weatherIconRes(code: Int): Int? = when (code) {
        0, 1 -> R.drawable.ic_weather_sunny
        2, 3 -> R.drawable.ic_weather_cloudy
        45, 48 -> R.drawable.ic_weather_fog
        51, 53, 55, 61, 63, 65, 80, 81, 82 -> R.drawable.ic_weather_rain
        56, 57, 66, 67, 71, 73, 75, 77, 85, 86 -> R.drawable.ic_weather_snow
        95, 96, 99 -> R.drawable.ic_weather_storm
        else -> null
    }

    private fun drawPremiumMode(canvas: Canvas, w: Float, h: Float, data: WeatherInfo) {
        val inset = dpf(10f)
        val cardL = inset
        val cardT = inset
        val cardR = w - inset
        val cardB = h - inset
        if (cardR - cardL < dpf(80f) || cardB - cardT < dpf(56f)) return

        val radius = dpf(22f)
        premCardStroke.strokeWidth = dpf(1f)
        canvas.drawRoundRect(cardL, cardT, cardR, cardB, radius, radius, premCardFill)
        canvas.drawRoundRect(cardL, cardT, cardR, cardB, radius, radius, premCardStroke)

        val pad = dpf(16f)
        val cx0 = cardL + pad
        val cx1 = cardR - pad
        if (cx1 - cx0 < dpf(40f)) return

        // Vertical split: an upper "main" zone (icon/temp | city/condition)
        // and a lower single details line. Two zones guarantee the groups
        // never collide even on a narrow (320dp) panel.
        val detailsH = dpf(14f)
        val mainTop = cardT + pad
        val mainBottom = cardB - pad - detailsH
        val mainMid = (mainTop + mainBottom) / 2f

        // ---------- left group: icon + temperature (+feels-like) ----------
        val iconSize = dpf(42f)
        val iconRes = weatherIconRes(data.codeNow)
        var contentX = cx0
        if (iconRes != null) {
            var d = if (data.codeNow == premIconCode) premIcon else null
            if (d == null) {
                d = try {
                    resources.getDrawable(iconRes, context.theme)
                } catch (_: Exception) {
                    null
                }
                premIcon = d
                premIconCode = data.codeNow
            }
            if (d != null) {
                val top = mainMid - iconSize / 2f
                d.setBounds(
                    cx0.toInt(), top.toInt(),
                    (cx0 + iconSize).toInt(), (top + iconSize).toInt()
                )
                d.draw(canvas)
                contentX = cx0 + iconSize + dpf(14f)
            }
        }

        // "Feels like" is shown whenever the API provided it (even when equal
        // to the real temperature); absence is null, never 0.
        val tempStr = t(data.tempNowC)
        val feels = data.feelsNowC?.let {
            if (isRu()) "ощущается как ${t(it)}" else "feels like ${t(it)}"
        } ?: ""
        premTempPaint.textAlign = Paint.Align.LEFT
        val gap = dpf(4f)
        val tempH = premTempFontExtent()
        val feelsH = if (feels.isNotEmpty()) premMetaFontExtent() + gap else 0f
        val blockTop = mainMid - (tempH + feelsH) / 2f
        val tempBase = blockTop + tempH * 0.78f
        canvas.drawText(tempStr, contentX, tempBase, premTempPaint)
        if (feels.isNotEmpty()) {
            metaPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(
                feels, contentX,
                tempBase + gap + premMetaFontExtent() * 0.78f, metaPaint
            )
        }

        // ---------- right group: city + condition (right-aligned) ----------
        // Long names ("Petropavlovsk-Kamchatsky, Russia") would run left past
        // the temperature block, so both lines are ellipsized to the free
        // width between the temp block and the card edge (the temperature
        // always wins; on an absurdly narrow card the city is skipped).
        val city = data.city.trim()
        val cond = WeatherLabel.of(data.codeNow)
        val tempW = premTempPaint.measureText(tempStr)
        val feelsW = if (feels.isNotEmpty()) metaPaint.measureText(feels) else 0f
        val maxRightW = cx1 - contentX - maxOf(tempW, feelsW) - dpf(12f)
        val cityFit = ellipsizeEnd(premCityPaint, city, maxRightW)
        val condFit = ellipsizeEnd(premCondPaint, cond, maxRightW)
        val lineHeights = floatArrayOf(dpf(15f), dpf(14f))
        var rightH = 0f
        if (cityFit.isNotEmpty()) rightH += lineHeights[0]
        if (condFit.isNotEmpty()) rightH += lineHeights[1]
        var y = mainMid - rightH / 2f
        if (cityFit.isNotEmpty()) {
            canvas.drawText(cityFit, cx1, y + lineHeights[0] * 0.82f, premCityPaint)
            y += lineHeights[0]
        }
        if (condFit.isNotEmpty()) {
            canvas.drawText(condFit, cx1, y + lineHeights[1] * 0.82f, premCondPaint)
        }

        // ---------- bottom details line (real values only) ----------
        val details = buildPremiumDetails(data)
        if (details.isNotEmpty()) {
            val baseY = cardB - pad - dpf(2f)
            // Right-align the short line; if it is wider than the card, let it
            // run to the left edge instead of clipping.
            val dw = premDetailPaint.measureText(details)
            var x = cx1 - dw
            if (x < cx0) x = cx0
            canvas.drawText(details, x, baseY, premDetailPaint)
        }
    }

    /** Truncate [text] with "…" so it fits [maxW] ("" when not even one
     *  char fits). Binary search over short-lived prefixes; only runs while
     *  truncating, so the common case costs a single measureText. */
    private fun ellipsizeEnd(p: Paint, text: String, maxW: Float): String {
        if (maxW <= 0f || text.isEmpty()) return ""
        if (p.measureText(text) <= maxW) return text
        val ell = "…"
        if (p.measureText(ell) > maxW) return ""
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (p.measureText(text.substring(0, mid) + ell) <= maxW) lo = mid
            else hi = mid - 1
        }
        // Never split a surrogate pair (emoji/city names in astral planes).
        var cut = lo
        while (cut > 0 && text[cut - 1].isLowSurrogate()) cut--
        return if (cut == 0) "" else text.substring(0, cut) + ell
    }

    private fun premTempFontExtent(): Float {
        val fm = premTempPaint.fontMetrics
        return fm.descent - fm.ascent
    }

    private fun premMetaFontExtent(): Float {
        val fm = metaPaint.fontMetrics
        return fm.descent - fm.ascent
    }

    /** "18°/25°  ·  ↑ 06:12 ↓ 20:41" — today's range + sun times, from real
     *  values only (empty pieces are skipped, no placeholders). */
    private fun buildPremiumDetails(data: WeatherInfo): String = buildString {
        val nowMs = System.currentTimeMillis()
        val today = data.days.firstOrNull {
            isSameDay(it.timeMs, nowMs, zone(data))
        }
        if (today != null) {
            append("${t(today.tMin)}/${t(today.tMax)}")
        }
        val sun = sunLine(data)
        if (sun.isNotEmpty()) {
            if (isNotEmpty()) append("  ·  ")
            append(sun)
        }
    }

    /** Both instants compared in the forecast city's zone, so the "today"
     *  highlight matches the times the panel actually shows. */
    private fun isSameDay(a: Long, b: Long, tz: TimeZone): Boolean {
        val ca = Calendar.getInstance(tz).apply { timeInMillis = a }
        val cb = Calendar.getInstance(tz).apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}
