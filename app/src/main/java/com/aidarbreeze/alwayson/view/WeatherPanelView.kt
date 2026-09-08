package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.aidarbreeze.alwayson.weather.WeatherDay
import com.aidarbreeze.alwayson.weather.WeatherHour
import com.aidarbreeze.alwayson.weather.WeatherInfo
import com.aidarbreeze.alwayson.weather.WeatherLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Monochrome weather panel that shows the forecast in two modes that rotate as
 * separate windows: HOURLY (next hours as columns) and DAILY (one row per day).
 * Draws in white on black to match the other panels and the OLED theme.
 */
class WeatherPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var info: WeatherInfo? = null
    private var mode = MODE_DAILY
    private var statusText = ""

    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(13f)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        textSize = dpf(10f)
    }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(15f)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        textSize = dpf(13f)
        textAlign = Paint.Align.CENTER
    }

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        const val MODE_HOURLY = 0
        const val MODE_DAILY = 1
    }

    fun setStatus(text: String) {
        info = null
        statusText = text
        invalidate()
    }

    fun showHourly(data: WeatherInfo) {
        info = data
        mode = MODE_HOURLY
        statusText = ""
        invalidate()
    }

    fun showDaily(data: WeatherInfo) {
        info = data
        mode = MODE_DAILY
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

        // Top summary line: location + current temperature/condition.
        val place = if (data.city.isNotBlank()) data.city else condFallbackPlace()
        val nowCond = WeatherLabel.of(data.codeNow)
        val summary = when {
            place.isNotBlank() && nowCond.isNotEmpty() ->
                "$place · ${data.tempNowC}° · $nowCond"
            place.isNotBlank() -> "$place · ${data.tempNowC}°"
            nowCond.isNotEmpty() -> "${data.tempNowC}° · $nowCond"
            else -> "${data.tempNowC}°"
        }
        headPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(summary, w / 2f, dpf(14f), headPaint)

        if (mode == MODE_HOURLY) {
            drawHourly(canvas, w, h, data)
        } else {
            drawDaily(canvas, w, h, data)
        }
    }

    private fun condFallbackPlace(): String = ""

    // ---------- hourly ----------

    private fun drawHourly(canvas: Canvas, w: Float, h: Float, data: WeatherInfo) {
        val hours = data.hours
        val nowMs = System.currentTimeMillis()

        if (hours.isEmpty()) return
        // Next few hours starting from the current hour.
        var start = 0
        while (start < hours.size && hours[start].timeMs < nowMs - 30 * 60_000L) start++
        if (start >= hours.size) start = hours.size - 1

        val candidates = hours.subList(start, hours.size)
        if (candidates.isEmpty()) return
        // Number of columns that fit, keeping a readable min width.
        val colW = dpf(34f)
        val n = maxOf(1, minOf(candidates.size, (w / colW).toInt()))

        val topY = dpf(34f)
        val bottomY = h - dpf(4f)
        val bodyH = (bottomY - topY).coerceAtLeast(dpf(4f))

        // Temperature range of the shown set, for scaling the bars.
        var tMin = Int.MAX_VALUE
        var tMax = Int.MIN_VALUE
        for (i in 0 until n) {
            val t = candidates[i].tempC
            if (t < tMin) tMin = t
            if (t > tMax) tMax = t
        }
        if (tMax - tMin < 1) { tMin -= 1; tMax += 1 }

        val colStep = w / n
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
        bigPaint.textAlign = Paint.Align.CENTER

        for (i in 0 until n) {
            val cx = colStep * i + colStep / 2f
            val hour = candidates[i]

            // temp at the top of the column
            canvas.drawText(
                "${hour.tempC}°", cx, topY + dpf(6f), bigPaint
            )

            // vertical bar scaled within the temp range
            val f = (hour.tempC - tMin).toFloat() / (tMax - tMin).toFloat()
            val barH = bodyH * 0.45f
            val topBar = topY + dpf(18f)
            val y0 = topBar + barH * (1f - f)
            val y1 = topBar + barH
            if (y1 - y0 >= 0.5f) {
                canvas.drawRect(cx - dpf(2f), y0, cx + dpf(2f), y1, bigPaint)
            }

            // time below
            subPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(tf.format(Date(hour.timeMs)), cx, h - dpf(3f), subPaint)
        }
    }

    // ---------- daily ----------

    private fun drawDaily(canvas: Canvas, w: Float, h: Float, data: WeatherInfo) {
        val days = data.days
        if (days.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        // Keep today onwards (API already returns from today).
        var start = 0
        while (start < days.size && days[start].timeMs < nowMs - 12L * 60 * 60_000L) start++
        if (start >= days.size) start = days.size - 1

        val availTop = dpf(26f)
        val availBot = h - dpf(2f)
        val avail = availBot - availTop
        val rowMin = dpf(18f)
        val showCount = maxOf(1, minOf(days.size - start, (avail / rowMin).toInt()))
        val rowH = avail / showCount

        val wf = SimpleDateFormat("E", Locale.getDefault())

        for (i in 0 until showCount) {
            val d = days[start + i]
            val cy = availTop + rowH * i
            val baseline = cy + rowH * 0.66f

            val isToday = isSameDay(d.timeMs, nowMs)
            // Emphasis paint for today's row: bigger/bolder white. Its textAlign
            // is set right before each use because bigPaint is shared with the
            // hourly mode (which leaves it centred) — otherwise today's label and
            // temperature would be mis-centred against the row edges.
            val dayPaint = if (isToday) bigPaint else subPaint

            // day label, flush left
            dayPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(wf.format(Date(d.timeMs)), dpf(2f), baseline, dayPaint)

            // small condition text in the middle-left
            val cond = WeatherLabel.of(d.code)
            if (cond.isNotEmpty()) {
                subPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(cond, dpf(46f), baseline, subPaint)
            }

            // temperature range, flush right (use the same emphasis paint)
            dayPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${d.tMin}°…${d.tMax}°", w - dpf(2f), baseline, dayPaint)
        }
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}
