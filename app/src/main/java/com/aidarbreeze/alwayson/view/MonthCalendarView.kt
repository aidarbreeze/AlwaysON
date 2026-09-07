package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import java.util.Locale
import kotlin.math.min

/**
 * A month calendar (like the iPhone StandBy one) drawn on the canvas so it is
 * OLED-friendly. Layout is computed from the view's measured size, so the grid
 * always fits — nothing is clipped on short landscape screens.
 */
class MonthCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val titlePaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val weekdayPaint = Paint().apply { color = 0x73FFFFFF.toInt(); isAntiAlias = true }
    private val dayPaint = Paint().apply { color = 0x99FFFFFF.toInt(); isAntiAlias = true }
    private val todayCirclePaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val todayNumPaint = Paint().apply { color = Color.BLACK; isAntiAlias = true }

    private var cachedYear = -1
    private var cachedMonth = -1
    private var daysInMonth = 31
    private var firstCell = 0
    private var todayDay = 0
    private var weekLabels = arrayOf("", "", "", "", "", "", "")

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH)
        if (year != cachedYear || month != cachedMonth) {
            rebuild(now, year, month)
        }

        val titleFont = (min(w, h) * 0.10f).coerceIn(22f, 40f)
        val gridFont = (min(w, h) * 0.075f).coerceIn(16f, 30f)
        val weekdayFont = gridFont * 0.72f

        titlePaint.textSize = titleFont

        val padX = min(w * 0.04f, 20f)
        val padTop = min(h * 0.05f, 16f)

        val monthTitle = String.format(
            Locale.getDefault(), "%s %d",
            now.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()), year
        )
        canvas.drawText(monthTitle, padX, padTop + titleFont, titlePaint)

        val gridWidth = w - padX * 2f
        val dayWidth = gridWidth / 7f

        // weekday header baseline right under the title
        val weekBaseline = padTop + titleFont * 1.7f
        weekdayPaint.textSize = weekdayFont
        // The grid's columns run from the week's first day (Calendar) to the
        // last. weekLabels is indexed by the Calendar.DAY_OF_WEEK constant, so
        // column i must show the weekday (firstDayOfWeek + i), not a fixed
        // Sunday-first list - otherwise headers drift off the numbers.
        val firstDow = now.firstDayOfWeek
        for (i in 0 until 7) {
            val cx = padX + dayWidth * i + dayWidth / 2f
            val weekday = (firstDow - 1 + i) % 7 + 1
            val label = weekLabels[weekday - 1]
            if (label.isNotEmpty()) {
                canvas.drawText(label, cx - weekdayPaint.measureText(label) / 2f, weekBaseline, weekdayPaint)
            }
        }

        // grid area from below weekday row down to bottom
        val gridTop = weekBaseline + weekdayFont * 0.5f
        val gridBottom = h - padTop
        val rowsAvail = 6
        val rowH = ((gridBottom - gridTop) / rowsAvail).coerceAtLeast(dayWidth * 0.55f)
        val circleR = min(rowH * 0.42f, dayWidth * 0.32f)

        dayPaint.textSize = gridFont
        todayNumPaint.textSize = gridFont

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = padX + dayWidth * col + dayWidth / 2f
                val cy = gridTop + row * rowH + rowH * 0.5f
                val num = day.toString()
                if (day == todayDay) {
                    canvas.drawCircle(cx, cy, circleR, todayCirclePaint)
                    todayNumPaint.color = Color.BLACK
                    canvas.drawText(num, cx - todayNumPaint.measureText(num) / 2f,
                        cy + gridFont * 0.35f, todayNumPaint)
                } else {
                    canvas.drawText(num, cx - dayPaint.measureText(num) / 2f,
                        cy + gridFont * 0.35f, dayPaint)
                }
                day++
                col++
            }
            row++
        }
    }

    private fun rebuild(now: Calendar, year: Int, month: Int) {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month, 1)
        val fDow = now.firstDayOfWeek
        firstCell = (c.get(Calendar.DAY_OF_WEEK) - fDow + 7) % 7
        daysInMonth = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        todayDay = now.get(Calendar.DAY_OF_MONTH)

        val names = now.getDisplayNames(
            Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault()
        ) ?: emptyMap()
        for (d in 1..7) {
            weekLabels[d - 1] = names.entries
                .firstOrNull { it.key != null && it.value == d }
                ?.key ?: ""
        }
        cachedYear = year
        cachedMonth = month
    }
}
