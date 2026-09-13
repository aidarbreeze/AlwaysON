package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
 *  8 PREMIUM      — "Premium Card": the month in a translucent dark card with
 *                   a rounded border, month title (year dimmed), small-caps
 *                   weekday labels, today in an accent ring, weekends dimmed.
 *  9 IPHONE       — iPhone StandBy calendar: red month title, today as a
 *                   filled red disc with a white number.
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

    // ---- premium card (style 8) ----
    private val premCardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF11151B.toInt(); style = Paint.Style.FILL
    }
    private val premCardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF26303B.toInt(); style = Paint.Style.STROKE
    }
    private val premTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F7FA.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val premTitleYearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B93A1.toInt()
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val premWeekdayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF59616D.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.06f
    }
    private val premDayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F7FA.toInt() }
    private val premWeekendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8B93A1.toInt() }
    private val premTodayNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7DD3FC.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val premTodayFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF263E4C.toInt(); style = Paint.Style.FILL
    }
    private val premTodayStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7DD3FC.toInt(); style = Paint.Style.STROKE
    }

    /** Current style code (0..8, see class doc). */
    private fun style(): Int = Prefs.calendarStyle(context)

    /** True when the month heading is drawn (not in the minimal variants). */
    private fun showHeading(st: Int): Boolean = st != 1 && st != 5

    /** Today marker: 0 = filled disc, 1 = ring, 2 = none (brighter number). */
    private fun todayMarker(st: Int): Int = when (st) {
        1, 5 -> if (st == 5) 1 else 2
        3 -> 1
        else -> 0
    }

    private fun weekendDim(st: Int): Boolean = st == 4
    private fun compact(st: Int): Boolean = st == 6
    private fun largeNumbers(st: Int): Boolean = st == 7

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
        if (year != cachedYear || month != cachedMonth || fDow != cachedFirstDow) {
            rebuild(year, month, fDow)
        }

        // The premium card has its own layout (a bordered card, two-tone
        // title, small-caps weekdays) and reuses the same month structure.
        // The style is read once per frame and passed down, not queried from
        // Prefs by every helper.
        val st = style()
        if (st == 8) {
            drawPremium(canvas, w, h, now)
            return
        }
        // iPhone style: red month title, today as a filled red disc with a
        // white number. The paints are fields reused across draws, so both
        // branches assign explicitly and no state leaks between styles.
        val ios = st == 9
        titlePaint.color = if (ios) 0xFFFF3B30.toInt() else Color.WHITE
        // Style 2 ("Filled Today") used to render pixel-identical to style 0
        // (both: a white filled disc) — it now fills the disc in accent sky.
        todayCirclePaint.color = when {
            ios -> 0xFFFF3B30.toInt()
            st == 2 -> 0xFF7DD3FC.toInt()
            else -> Color.WHITE
        }
        todayNumPaint.color = if (ios) Color.WHITE else Color.BLACK

        val compact = compact(st)
        val padX = min(w * 0.05f, if (compact) 14f else 20f)
        val padTop = min(h * 0.07f, if (compact) 14f else 22f)
        val padBottom = min(h * 0.04f, if (compact) 8f else 12f)

        // Month title must stand out — keep it clearly larger than the day grid.
        val titleFont = (min(w, h) * 0.14f).coerceIn(28f, 64f)
        val weekdayFont = (min(w, h) * 0.075f).coerceIn(16f, 30f)

        val showHead = showHeading(st)
        if (showHead) {
            // Month title, centred over the whole calendar, first letter upper.
            titlePaint.textSize = titleFont
            val monthName = monthHeading(month)
            // The iPhone widget shouts the month in red caps ("OCTOBER").
            val monthTitle = if (ios) "$monthName $year".uppercase(Locale.getDefault())
            else "$monthName $year"
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
                // Shrink this label only if it would overflow its column
                // (some locales give short names with a dot: "чт.", "Thu.").
                val maxW = dayWidth * 0.9f
                var size = weekdayFont
                weekdayPaint.textSize = size
                while (size > 8f && weekdayPaint.measureText(label) > maxW) {
                    size -= 1f
                    weekdayPaint.textSize = size
                }
                canvas.drawText(
                    label,
                    cx - weekdayPaint.measureText(label) / 2f,
                    weekTop + size,
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
        if (largeNumbers(st)) gridFont *= 1.22f
        if (compact) gridFont *= 0.86f
        gridFont = gridFont.coerceIn(16f, 56f)
        val circleR = min(rowH * 0.44f, dayWidth * 0.44f)
        todayRingPaint.strokeWidth = (gridFont * 0.09f).coerceIn(1.5f, 4f)

        dayPaint.textSize = gridFont
        todayNumPaint.textSize = gridFont
        todayNumBright.textSize = gridFont
        weekendPaint.textSize = gridFont

        val marker = todayMarker(st)
        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = padX + dayWidth * col + dayWidth / 2f
                val cy = gridTop + row * rowH + rowH * 0.5f
                val num = day.toString()
                // Full day+month+year check, no shadowing.
                val cellIsToday =
                    year == todayYear && month == todayMonth && day == todayDay
                if (cellIsToday) {
                    when (marker) {
                        0 -> { // filled disc (colors assigned above per style)
                            canvas.drawCircle(cx, cy, circleR, todayCirclePaint)
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
                    val p = if (weekendDim(st) && isWeekend(day, firstCell, firstDow)) weekendPaint
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

    /** Style 8: the month as a translucent card — rounded 22dp border,
     *  "September 2026" with the year dimmed, small-caps weekday labels,
     *  today in an accent ring, weekends muted. No heavy grid lines. */
    private fun drawPremium(canvas: Canvas, w: Float, h: Float, now: Calendar) {
        val density = resources.displayMetrics.density
        val inset = 10f * density
        val cardL = inset
        val cardT = inset
        val cardR = w - inset
        val cardB = h - inset
        // Too small to host the card at all — draw nothing rather than clip.
        if (cardR - cardL < 60f * density || cardB - cardT < 60f * density) return

        val radius = 22f * density
        premCardStroke.strokeWidth = 1f * density
        canvas.drawRoundRect(cardL, cardT, cardR, cardB, radius, radius, premCardFill)
        canvas.drawRoundRect(cardL, cardT, cardR, cardB, radius, radius, premCardStroke)

        val padX = cardL + 14f * density
        val contentW = cardR - 14f * density - padX
        if (contentW <= 0f) return
        val dayWidth = contentW / 7f

        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH)
        val locale = Locale.getDefault()

        // --- title: month in primary, the year in secondary, centred ---
        val titleFont = (min(w, h) * 0.11f).coerceIn(20f, 44f)
        val monthName = monthHeading(month)
        val yearStr = " $year"
        premTitlePaint.textSize = titleFont
        premTitleYearPaint.textSize = titleFont
        val mw = premTitlePaint.measureText(monthName)
        val yw = premTitleYearPaint.measureText(yearStr)
        val tx = (w - mw - yw) / 2f
        val titleBase = cardT + 22f * density + titleFont * 0.75f
        canvas.drawText(monthName, tx, titleBase, premTitlePaint)
        canvas.drawText(yearStr, tx + mw, titleBase, premTitleYearPaint)

        // --- weekday labels: small caps, tertiary ---
        val weekdayFont = (min(w, h) * 0.045f).coerceIn(10f, 18f)
        premWeekdayPaint.textSize = weekdayFont
        val fDow = if (configuredFirstDow != 0) configuredFirstDow else now.firstDayOfWeek
        val weekTop = titleBase + 14f * density + weekdayFont
        for (i in 0 until 7) {
            val cx = padX + dayWidth * i + dayWidth / 2f
            val weekday = (fDow - 1 + i) % 7 + 1
            val label = weekLabels[weekday - 1].uppercase(locale)
            if (label.isNotEmpty()) {
                canvas.drawText(label, cx - premWeekdayPaint.measureText(label) / 2f, weekTop, premWeekdayPaint)
            }
        }

        // --- day grid ---
        val gridTop = weekTop + 12f * density
        val gridBottom = cardB - 14f * density
        val availRows = gridBottom - gridTop
        if (availRows <= 0f) return
        val weeks = ceil((firstCell + daysInMonth) / 7.0).toInt().coerceIn(4, 6)
        val rowH = availRows / weeks
        val gridFont = min(rowH * 0.58f, dayWidth * 0.68f).coerceIn(10f, 30f)
        val circleR = min(rowH * 0.46f, dayWidth * 0.46f)
        premTodayStroke.strokeWidth = 1f * density

        premDayPaint.textSize = gridFont
        premWeekendPaint.textSize = gridFont
        premTodayNumPaint.textSize = gridFont

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = padX + dayWidth * col + dayWidth / 2f
                val cy = gridTop + row * rowH + rowH * 0.5f
                val num = day.toString()
                val isToday = year == todayYear && month == todayMonth && day == todayDay
                when {
                    isToday -> {
                        canvas.drawCircle(cx, cy, circleR, premTodayFill)
                        canvas.drawCircle(cx, cy, circleR, premTodayStroke)
                        canvas.drawText(
                            num,
                            cx - premTodayNumPaint.measureText(num) / 2f,
                            cy + gridFont * 0.35f,
                            premTodayNumPaint
                        )
                    }
                    else -> {
                        val p = if (isWeekend(day, firstCell, fDow)) premWeekendPaint else premDayPaint
                        canvas.drawText(num, cx - p.measureText(num) / 2f, cy + gridFont * 0.35f, p)
                    }
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

        // Direct lookup by Calendar constant (1=SUN .. 7=SAT); no map-order
        // or key-format assumptions. "пн" -> "Пн", "Mon" -> "Mon".
        val locale = Locale.getDefault()
        val symbols = DateFormatSymbols(locale)
        for (d in 1..7) {
            val raw = symbols.shortWeekdays[d].orEmpty()
            weekLabels[d - 1] = raw.replaceFirstChar { it.titlecase(locale) }
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
