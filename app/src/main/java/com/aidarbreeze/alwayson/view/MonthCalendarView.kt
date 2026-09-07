package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import java.util.Locale

/**
 * A minimal month calendar (like the iPhone StandBy clock's calendar) drawn
 * directly on the canvas. Shows the current month with today highlighted, in a
 * dark OLED-friendly style — only the drawn pixels light up.
 */
class MonthCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val monthPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = 0x99FFFFFF.toInt()
        textSize = 30f
        isAntiAlias = true
    }
    private val weekdayPaint = Paint().apply {
        color = 0x73FFFFFF.toInt()
        textSize = 22f
        isAntiAlias = true
    }
    private val todayCirclePaint = Paint().apply { color = Color.WHITE }
    private val todayNumberPaint = Paint().apply {
        color = Color.BLACK
        textSize = 30f
        isAntiAlias = true
    }

    private var cachedYear = -1
    private var cachedMonth = -1
    private var daysInMonth = 31
    private var firstCell = 0        // grid column of the 1st of month
    private var todayDay = 0
    private var widthPx = 0f
    private var weekLabels: Array<String> = Array(7) { "" }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        widthPx = w.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH)
        if (year != cachedYear || month != cachedMonth) {
            buildMonth(now, year, month)
        }

        val monthTitle = String.format(
            Locale.getDefault(), "%s %d",
            now.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()),
            year
        )
        canvas.drawText(monthTitle, 0f, 50f, monthPaint)

        val dayWidth = widthPx / 7f
        val headerY = 88f
        val rowH = 29f
        val startRowY = headerY + rowH

        val fDow = now.firstDayOfWeek
        // Weekday header.
        for (i in 0 until 7) {
            val dow = ((fDow - 1 + i) % 7) + 1 // 1..7
            canvas.drawText(weekLabels[dow - 1], i * dayWidth, headerY, weekdayPaint)
        }

        // Body: days row-major starting at firstCell of row 0.
        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = col * dayWidth + dayWidth / 2f
                val cy = startRowY + row * rowH + rowH * 0.6f
                if (day == todayDay) {
                    canvas.drawCircle(cx, cy + 2f, 17f, todayCirclePaint)
                    drawCentered(canvas, day.toString(), cx, cy, todayNumberPaint)
                } else {
                    drawCentered(canvas, day.toString(), cx, cy, gridPaint)
                }
                day++
                col++
            }
            row++
        }
    }

    private fun drawCentered(canvas: Canvas, text: String, cx: Float, cy: Float, paint: Paint) {
        val w = paint.measureText(text)
        canvas.drawText(text, cx - w / 2f, cy, paint)
    }

    private fun buildMonth(now: Calendar, year: Int, month: Int) {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month, 1)
        val firstDow = c.get(Calendar.DAY_OF_WEEK) // 1..7 of the 1st
        val fDow = now.firstDayOfWeek
        firstCell = (firstDow - fDow + 7) % 7
        daysInMonth = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        todayDay = now.get(Calendar.DAY_OF_MONTH)

        // Weekday labels (Sun..Sat) in the current locale.
        val names = now.getDisplayNames(
            Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault()
        ) ?: emptyMap()
        for (d in 1..7) {
            weekLabels[d - 1] = names.entries
                .firstOrNull { it.key != null && it.value == d }
                ?.key
                ?: ""
        }
        cachedYear = year
        cachedMonth = month
    }
}
