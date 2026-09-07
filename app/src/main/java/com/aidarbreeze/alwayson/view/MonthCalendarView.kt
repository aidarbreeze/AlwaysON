package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.text.DateFormatSymbols
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
    private val weekdayPaint = Paint().apply { color = 0x8FFFFFFF.toInt(); isAntiAlias = true }
    private val dayPaint = Paint().apply { color = 0xB3FFFFFF.toInt(); isAntiAlias = true }
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

        val padX = min(w * 0.05f, 20f)
        val padTop = min(h * 0.05f, 14f)
        val padBottom = min(h * 0.04f, 12f)

        val titleFont = (min(w, h) * 0.10f).coerceIn(20f, 36f)
        val weekdayFont = (min(w, h) * 0.07f).coerceIn(15f, 28f)
        val gridFont = (min(w, h) * 0.085f).coerceIn(16f, 30f)

        // Month title, centred over the whole calendar, first letter upper.
        titlePaint.textSize = titleFont
        val monthName = monthHeading(month)
        val monthTitle = "$monthName $year"
        canvas.drawText(
            monthTitle,
            (w - titlePaint.measureText(monthTitle)) / 2f,
            padTop + titleFont,
            titlePaint
        )

        val dayWidth = (w - padX * 2f) / 7f

        // Weekday header, centred in each column (grid columns run from the
        // week's first day, so the label shown is (firstDayOfWeek + i)).
        weekdayPaint.textSize = weekdayFont
        val firstDow = now.firstDayOfWeek
        val weekTop = padTop + titleFont * 1.8f
        for (i in 0 until 7) {
            val cx = padX + dayWidth * i + dayWidth / 2f
            val weekday = (firstDow - 1 + i) % 7 + 1
            val label = weekLabels[weekday - 1]
            if (label.isNotEmpty()) {
                canvas.drawText(
                    label,
                    cx - weekdayPaint.measureText(label) / 2f,
                    weekTop + weekdayFont,
                    weekdayPaint
                )
            }
        }

        // Grid below the weekday row, spread to the bottom.
        val gridTop = weekTop + weekdayFont * 1.9f
        val gridBottom = h - padBottom
        val rowH = ((gridBottom - gridTop) / 6f).coerceAtLeast(dayWidth * 0.62f)
        val circleR = min(rowH * 0.40f, dayWidth * 0.30f)

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
                    canvas.drawText(
                        num,
                        cx - todayNumPaint.measureText(num) / 2f,
                        cy + gridFont * 0.36f,
                        todayNumPaint
                    )
                } else {
                    canvas.drawText(
                        num,
                        cx - dayPaint.measureText(num) / 2f,
                        cy + gridFont * 0.36f,
                        dayPaint
                    )
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
            val raw = names.entries
                .firstOrNull { it.key != null && it.value == d }
                ?.key ?: ""
            // "пн" -> "Пн", "вс" -> "Вс" — readable single word per column.
            weekLabels[d - 1] = raw.replaceFirstChar {
                it.titlecase(Locale.getDefault())
            }
        }
        cachedYear = year
        cachedMonth = month
    }

    /** Localized month in nominative, first letter capital (e.g. "Сентябрь"). */
    private fun monthHeading(month: Int): String {
        val locale = Locale.getDefault()
        // In Russian a standalone month must be nominative ("Сентябрь"), while
        // the date formatter gives the genitive ("сентября"); pick the right one.
        if (locale.language.equals("ru", ignoreCase = true)) {
            val ru = arrayOf(
                "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
                "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
            )
            return ru.getOrElse(month) { "" }
        }
        val symbols = DateFormatSymbols.getInstance(locale)
        val name = symbols.months.getOrNull(month).orEmpty()
        if (name.isNotBlank()) {
            return name.replaceFirstChar { it.titlecase(locale) }
        }
        return Calendar.getInstance().getDisplayName(
            Calendar.MONTH, Calendar.LONG, locale
        )?.replaceFirstChar { it.titlecase(locale) } ?: ""
    }
}
