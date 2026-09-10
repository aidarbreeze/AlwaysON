package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.aidarbreeze.alwayson.Prefs
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * A month calendar (like the iPhone StandBy one) drawn on the canvas so it is
 * OLED-friendly. Layout is computed from the view's measured size, so the grid
 * always fits — nothing is clipped on short landscape screens.
 *
 * Styles ([Prefs.calendarStyle]):
 *  0 CLASSIC      — the base grid with a month heading and a filled today.
 *  1 MINIMAL      — no month heading, today is just a brighter number.
 *  2 FILLED_TODAY — base grid, today highlighted with a filled disc.
 *  3 OUTLINED     — base grid, today highlighted with a ring.
 *  4 WEEKEND      — base grid, weekend days drawn dimmer.
 *  5 OLED_MONO    — minimal, strict white/gray, today as a thin ring.
 *  6 COMPACT      — base grid with tighter padding and rows.
 *  7 LARGE        — base grid with enlarged day numbers.
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
    private val todayRingPaint = Paint().apply {
        color = Color.WHITE; isAntiAlias = true
        style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val weekendPaint = Paint().apply { color = 0x66FFFFFF.toInt(); isAntiAlias = true }
    private val todayNumBright = Paint().apply { color = Color.WHITE; isAntiAlias = true }

    /** Current style code (0..7, see class doc). */
    private fun style(): Int = Prefs.calendarStyle(context)

    /** True when the month heading is drawn (not in the minimal variants). */
    private fun showHeading(): Boolean = style() != 1 && style() != 5

    /** Today marker: 0 = filled disc, 1 = ring, 2 = none (brighter number). */
    private fun todayMarker(): Int = when (style()) {
        1, 5 -> if (style() == 5) 1 else 2
        3 -> 1
        else -> 0
    }

    private fun weekendDim(): Boolean = style() == 4
    private fun compact(): Boolean = style() == 6
    private fun largeNumbers(): Boolean = style() == 7

    private var cachedYear = -1
    private var cachedMonth = -1
    private var cachedFirstDow = -1
    private var daysInMonth = 31
    private var firstCell = 0
    // "Today" is stored as a full day+month+year triple so a cell is only ever
    // highlighted when all three match (robust across midnight/year change).
    private var todayDay = 0
    private var todayMonth = -1
    private var todayYear = -1
    private var weekLabels = arrayOf("", "", "", "", "", "", "")

    // Configured first day of week; 0 means "follow the locale/system".
    private var configuredFirstDow = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    /** Set an explicit first day of week (Calendar.DAY_OF_WEEK), or 0 to fall
     *  back to the locale default. */
    fun setFirstDayOfWeek(dow: Int) {
        val d = dow.coerceIn(0, 7)
        if (d != configuredFirstDow) {
            configuredFirstDow = d
            cachedFirstDow = -1 // force a rebuild so columns realign
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH)
        val fDow = if (configuredFirstDow != 0) configuredFirstDow else now.firstDayOfWeek
        // The screen can stay on across midnight (desk-clock mode), so the
        // highlighted "today" must be refreshed on every draw — the month
        // structure itself only changes when the month/year/week-start does.
        todayDay = now.get(Calendar.DAY_OF_MONTH)
        todayMonth = month
        todayYear = year
        val isToday = year == todayYear && month == todayMonth
        if (year != cachedYear || month != cachedMonth || fDow != cachedFirstDow) {
            rebuild(year, month, fDow)
        }

        val compact = compact()
        val padX = min(w * 0.05f, if (compact) 14f else 20f)
        val padTop = min(h * 0.07f, if (compact) 14f else 22f)
        val padBottom = min(h * 0.04f, if (compact) 8f else 12f)

        // Month title must stand out — keep it clearly larger than the day grid.
        val titleFont = (min(w, h) * 0.14f).coerceIn(28f, 64f)
        val weekdayFont = (min(w, h) * 0.075f).coerceIn(16f, 30f)

        val showHead = showHeading()
        if (showHead) {
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
        }

        val dayWidth = (w - padX * 2f) / 7f

        // Weekday header, centred in each column (grid columns run from the
        // week's first day, so the label shown is (firstDayOfWeek + i)).
        weekdayPaint.textSize = weekdayFont
        val firstDow = if (configuredFirstDow != 0) configuredFirstDow else now.firstDayOfWeek
        val weekTop = if (showHead) padTop + titleFont * 1.8f else padTop + weekdayFont * 0.4f
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

        // Numeric grid below the weekday row. The month spans weeks rows (not
        // always six), so there is no wasted blank row and the digits can be
        // larger while staying compact.
        val gridTop = weekTop + weekdayFont * 1.9f
        val gridBottom = h - padBottom
        val availRows = gridBottom - gridTop
        if (availRows <= 0f) return // too little room left after the header
        val weeks = ceil((firstCell + daysInMonth) / 7.0).toInt().coerceIn(4, 6)

        // Day digits as big as both the row height and the column width allow.
        val rowH = availRows / weeks
        var gridFont = min(rowH * 0.68f, dayWidth * 0.72f)
        if (largeNumbers()) gridFont *= 1.22f
        if (compact) gridFont *= 0.86f
        gridFont = gridFont.coerceIn(16f, 56f)
        val circleR = min(rowH * 0.44f, dayWidth * 0.44f)
        todayRingPaint.strokeWidth = (gridFont * 0.09f).coerceIn(1.5f, 4f)

        dayPaint.textSize = gridFont
        todayNumPaint.textSize = gridFont
        todayNumBright.textSize = gridFont
        weekendPaint.textSize = gridFont

        val marker = todayMarker()
        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = padX + dayWidth * col + dayWidth / 2f
                val cy = gridTop + row * rowH + rowH * 0.5f
                val num = day.toString()
                val isToday = isToday && day == todayDay
                if (isToday) {
                    when (marker) {
                        0 -> { // filled disc
                            canvas.drawCircle(cx, cy, circleR, todayCirclePaint)
                            todayNumPaint.color = Color.BLACK
                            canvas.drawText(
                                num,
                                cx - todayNumPaint.measureText(num) / 2f,
                                cy + gridFont * 0.36f,
                                todayNumPaint
                            )
                        }
                        1 -> { // ring
                            canvas.drawCircle(cx, cy, circleR, todayRingPaint)
                            canvas.drawText(
                                num,
                                cx - todayNumBright.measureText(num) / 2f,
                                cy + gridFont * 0.36f,
                                todayNumBright
                            )
                        }
                        else -> { // brighter number only
                            canvas.drawText(
                                num,
                                cx - todayNumBright.measureText(num) / 2f,
                                cy + gridFont * 0.36f,
                                todayNumBright
                            )
                        }
                    }
                } else {
                    // Weekend accent: draw the day number dimmer.
                    val p = if (weekendDim() && isWeekend(day, firstCell, firstDow)) weekendPaint
                    else dayPaint
                    canvas.drawText(
                        num,
                        cx - p.measureText(num) / 2f,
                        cy + gridFont * 0.36f,
                        p
                    )
                }
                day++
                col++
            }
            row++
        }
    }

    /** Calendar.DAY_OF_WEEK (1=Sun..7=Sat) for the [day]-th of the shown month. */
    private fun isWeekend(day: Int, firstCell: Int, firstDow: Int): Boolean {
        val dow = ((firstDow - 1 + firstCell + (day - 1)) % 7) + 1
        return dow == 1 || dow == 7
    }

    private fun rebuild(year: Int, month: Int, fDow: Int) {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month, 1)
        firstCell = (c.get(Calendar.DAY_OF_WEEK) - fDow + 7) % 7
        daysInMonth = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        // todayDay is maintained in onDraw (it changes at midnight without a
        // month change).

        val names = c.getDisplayNames(
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
        cachedFirstDow = fDow
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
