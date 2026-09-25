package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
 * OLED-friendly month calendar for the Always-On / screensaver screen.
 *
 * Styles ([Prefs.calendarStyle]):
 *  0 CLASSIC       — clean month grid, today in a white disc.
 *  1 MINIMAL       — no title, today is only brighter.
 *  2 FILLED_TODAY  — today in an accent blue disc.
 *  3 OUTLINED      — today in a thin ring.
 *  4 WEEKEND       — weekends are muted.
 *  5 OLED_MONO     — strict monospaced white/gray calendar.
 *  6 COMPACT       — tighter spacing for short landscape screens.
 *  7 LARGE         — enlarged day numbers without row overlap.
 *  8 PREMIUM       — dark premium card, two-tone title, accent today.
 *  9 IPHONE        — StandBy-inspired red month + red today disc.
 * 10 FLIP_BOARD    — split mechanical day cards, inspired by flip screensavers.
 * 11 GLASS         — translucent rounded glass card with soft separators.
 * 12 MATERIAL      — Material-style header and accent today pill.
 * 13 TERMINAL      — green phosphor monospace terminal calendar.
 * 14 DOT_MATRIX    — day numbers rendered from round LED dots.
 * 15 SWISS         — bold editorial/Swiss poster layout.
 * 16 TILES         — every day is a compact rounded tile.
 * 17 WEEK_FOCUS    — current week is bright, surrounding weeks are dimmed.
 * 18 NEON          — cyan/magenta neon-sign calendar.
 * 19 BIG_DATE      — giant current date with a compact month grid beside it.
 *
 * Existing style IDs 0..9 are intentionally preserved for preference/database
 * compatibility. New styles only extend the range to 19.
 */
class MonthCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private val TF_SANS: Typeface by lazy {
            Typeface.create("sans-serif", Typeface.NORMAL)
        }
        private val TF_MEDIUM: Typeface by lazy {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val TF_BOLD: Typeface by lazy {
            Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val TF_BLACK: Typeface by lazy {
            Typeface.create("sans-serif-black", Typeface.NORMAL)
        }
        private val TF_SERIF: Typeface by lazy {
            Typeface.create("serif", Typeface.NORMAL)
        }
        private val TF_MONO: Typeface by lazy {
            Typeface.MONOSPACE
        }

        // 3x5 dot-matrix digits, row-major; bit 2 is the left-most dot.
        private val DOT_DIGITS = arrayOf(
            intArrayOf(0b111, 0b101, 0b101, 0b101, 0b111), // 0
            intArrayOf(0b010, 0b110, 0b010, 0b010, 0b111), // 1
            intArrayOf(0b111, 0b001, 0b111, 0b100, 0b111), // 2
            intArrayOf(0b111, 0b001, 0b111, 0b001, 0b111), // 3
            intArrayOf(0b101, 0b101, 0b111, 0b001, 0b001), // 4
            intArrayOf(0b111, 0b100, 0b111, 0b001, 0b111), // 5
            intArrayOf(0b111, 0b100, 0b111, 0b101, 0b111), // 6
            intArrayOf(0b111, 0b001, 0b010, 0b010, 0b010), // 7
            intArrayOf(0b111, 0b101, 0b111, 0b101, 0b111), // 8
            intArrayOf(0b111, 0b101, 0b111, 0b001, 0b111)  // 9
        )

        private val RU_MONTHS = arrayOf(
            "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
        )
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val weekdayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val todayCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val todayNumPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val todayRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    // ---- premium card (style 8) ----
    private val premCardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF11151B.toInt()
        style = Paint.Style.FILL
    }
    private val premCardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF26303B.toInt()
        style = Paint.Style.STROKE
    }
    private val premTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F7FA.toInt()
        typeface = TF_MEDIUM
    }
    private val premTitleYearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B93A1.toInt()
        typeface = TF_SANS
    }
    private val premWeekdayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF59616D.toInt()
        typeface = TF_MEDIUM
        letterSpacing = 0.06f
    }
    private val premDayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F7FA.toInt()
        typeface = TF_SANS
    }
    private val premWeekendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B93A1.toInt()
        typeface = TF_SANS
    }
    private val premTodayNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7DD3FC.toInt()
        typeface = TF_MEDIUM
    }
    private val premTodayFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF263E4C.toInt()
        style = Paint.Style.FILL
    }
    private val premTodayStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7DD3FC.toInt()
        style = Paint.Style.STROKE
    }

    private var cachedYear = -1
    private var cachedMonth = -1
    private var cachedFirstDow = -1
    private var cachedLocale = ""
    private var daysInMonth = 31
    private var firstCell = 0
    private var todayDay = 0
    private var todayMonth = -1
    private var todayYear = -1
    private val weekLabels = arrayOf("", "", "", "", "", "", "")
    private val dayStrings = Array(32) { if (it == 0) "" else it.toString() }

    // Configured first day of week; 0 means "follow locale/system".
    private var configuredFirstDow = 0

    // A calendar view may stay visible for days. Do not depend on a parent clock
    // tick to notice midnight: schedule one exact model refresh ourselves.
    private val midnightRefresh = object : Runnable {
        override fun run() {
            cachedYear = -1
            cachedMonth = -1
            invalidate()
            scheduleMidnightRefresh()
        }
    }

    private fun style(): Int = Prefs.calendarStyle(context).coerceIn(0, 19)
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleMidnightRefresh()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(midnightRefresh)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    /** Apply style/size/locale preference changes immediately from Settings. */
    fun refresh() {
        cachedLocale = ""
        requestLayout()
        invalidate()
    }

    /** Set Calendar.DAY_OF_WEEK (1..7), or 0 to follow locale/system. */
    fun setFirstDayOfWeek(dow: Int) {
        val d = dow.coerceIn(0, 7)
        if (d != configuredFirstDow) {
            configuredFirstDow = d
            cachedFirstDow = -1
            invalidate()
        }
    }

    private fun scheduleMidnightRefresh() {
        removeCallbacks(midnightRefresh)
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 80)
        }
        val delay = (next.timeInMillis - now.timeInMillis).coerceAtLeast(1000L)
        postDelayed(midnightRefresh, delay)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        val now = Calendar.getInstance()
        ensureModel(now)

        when (val st = style()) {
            8 -> drawPremium(canvas, w, h, now)
            10 -> drawFlipBoard(canvas, w, h, now)
            11 -> drawGlass(canvas, w, h, now)
            12 -> drawMaterial(canvas, w, h, now)
            13 -> drawTerminal(canvas, w, h, now)
            14 -> drawDotMatrix(canvas, w, h, now)
            15 -> drawSwiss(canvas, w, h, now)
            16 -> drawTiles(canvas, w, h, now)
            17 -> drawWeekFocus(canvas, w, h, now)
            18 -> drawNeon(canvas, w, h, now)
            19 -> drawBigDate(canvas, w, h, now)
            else -> drawBase(canvas, w, h, now, st)
        }
    }

    private fun ensureModel(now: Calendar) {
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH)
        val localeTag = Locale.getDefault().toLanguageTag()
        val fDow = if (configuredFirstDow != 0) configuredFirstDow else now.firstDayOfWeek

        todayDay = now.get(Calendar.DAY_OF_MONTH)
        todayMonth = month
        todayYear = year

        if (year != cachedYear || month != cachedMonth || fDow != cachedFirstDow ||
            localeTag != cachedLocale
        ) {
            rebuild(year, month, fDow)
        }
    }

    // ---------------------------------------------------------------------
    // Existing styles 0..7 and 9, kept compatible but with corrected layout.
    // ---------------------------------------------------------------------

    private fun drawBase(canvas: Canvas, w: Float, h: Float, now: Calendar, st: Int) {
        val ios = st == 9
        val minimal = st == 1 || st == 5
        val compact = st == 6
        val mono = st == 5
        val large = st == 7
        val firstDow = if (configuredFirstDow != 0) configuredFirstDow else now.firstDayOfWeek

        val padX = min(w * 0.05f, dp(if (compact) 10f else 18f))
        val padTop = min(h * 0.07f, dp(if (compact) 8f else 16f))
        val padBottom = min(h * 0.04f, dp(if (compact) 6f else 10f))
        val contentW = (w - 2f * padX).coerceAtLeast(1f)
        val dayWidth = contentW / 7f

        val titleFont = min(min(w, h) * 0.135f, dp(48f)).coerceAtLeast(dp(18f))
        val weekdayFont = min(min(w, h) * 0.065f, dp(20f)).coerceAtLeast(dp(10f))

        titlePaint.reset()
        titlePaint.isAntiAlias = true
        titlePaint.typeface = if (mono) TF_MONO else if (ios) TF_BOLD else TF_MEDIUM
        titlePaint.color = if (ios) 0xFFFF3B30.toInt() else Color.WHITE
        titlePaint.textAlign = Paint.Align.CENTER

        weekdayPaint.reset()
        weekdayPaint.isAntiAlias = true
        weekdayPaint.typeface = if (mono) TF_MONO else TF_MEDIUM
        weekdayPaint.color = if (mono) 0x6FFFFFFF else 0x8FFFFFFF.toInt()
        weekdayPaint.textAlign = Paint.Align.CENTER

        val showHeading = !minimal
        var cursorY = padTop
        if (showHeading) {
            val monthName = monthHeading(now.get(Calendar.MONTH))
            val title = if (ios) {
                "$monthName ${now.get(Calendar.YEAR)}".uppercase(Locale.getDefault())
            } else {
                "$monthName ${now.get(Calendar.YEAR)}"
            }
            titlePaint.textSize = fitTextSize(titlePaint, title, contentW, titleFont)
            val titleH = textHeight(titlePaint)
            val titleCy = cursorY + titleH / 2f
            drawTextCentered(canvas, title, w / 2f, titleCy, titlePaint)
            cursorY += titleH + dp(if (compact) 5f else 9f)
        }

        weekdayPaint.textSize = weekdayFont
        val weekH = textHeight(weekdayPaint)
        val weekCy = cursorY + weekH / 2f
        drawWeekLabels(canvas, padX, dayWidth, weekCy, firstDow, weekdayPaint, dayWidth * 0.88f)
        cursorY += weekH + dp(if (compact) 4f else 8f)

        val gridBottom = h - padBottom
        val availRows = gridBottom - cursorY
        if (availRows <= dp(16f)) return
        val weeks = weekCount()
        val rowH = availRows / weeks

        var gridFont = min(rowH * 0.56f, dayWidth * 0.58f)
        if (large) gridFont *= 1.20f
        if (compact) gridFont *= 0.90f
        // Corrected: LARGE used to be allowed to exceed its row. Clamp against
        // both row and column after applying the multiplier.
        gridFont = min(gridFont, min(rowH * 0.72f, dayWidth * 0.72f))
            .coerceAtLeast(dp(10f))

        dayPaint.reset()
        dayPaint.isAntiAlias = true
        dayPaint.typeface = if (mono) TF_MONO else TF_SANS
        dayPaint.color = if (mono) 0xB8FFFFFF.toInt() else 0xB3FFFFFF.toInt()
        dayPaint.textSize = gridFont
        dayPaint.textAlign = Paint.Align.CENTER

        secondaryPaint.reset()
        secondaryPaint.isAntiAlias = true
        secondaryPaint.typeface = dayPaint.typeface
        secondaryPaint.color = if (mono) 0x52FFFFFF else 0x66FFFFFF
        secondaryPaint.textSize = gridFont
        secondaryPaint.textAlign = Paint.Align.CENTER

        todayNumPaint.reset()
        todayNumPaint.isAntiAlias = true
        todayNumPaint.typeface = if (mono) TF_MONO else TF_MEDIUM
        todayNumPaint.textSize = gridFont
        todayNumPaint.textAlign = Paint.Align.CENTER

        todayCirclePaint.reset()
        todayCirclePaint.isAntiAlias = true
        todayCirclePaint.style = Paint.Style.FILL
        todayCirclePaint.color = when {
            ios -> 0xFFFF3B30.toInt()
            st == 2 -> 0xFF7DD3FC.toInt()
            else -> Color.WHITE
        }

        todayRingPaint.reset()
        todayRingPaint.isAntiAlias = true
        todayRingPaint.style = Paint.Style.STROKE
        todayRingPaint.strokeWidth = (gridFont * 0.075f).coerceIn(dp(1f), dp(2.5f))
        todayRingPaint.color = Color.WHITE

        val circleR = min(rowH * 0.36f, dayWidth * 0.36f)
        val marker = when (st) {
            1 -> 2 // number only
            3, 5 -> 1 // ring
            else -> 0 // fill
        }
        val dimWeekends = st == 4
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH)

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = padX + dayWidth * (col + 0.5f)
                val cy = cursorY + rowH * (row + 0.5f)
                val isToday = year == todayYear && month == todayMonth && day == todayDay
                val text = dayStrings[day]

                if (isToday) {
                    when (marker) {
                        0 -> {
                            canvas.drawCircle(cx, cy, circleR, todayCirclePaint)
                            todayNumPaint.color = if (ios) Color.WHITE else Color.BLACK
                            drawTextCentered(canvas, text, cx, cy, todayNumPaint)
                        }
                        1 -> {
                            canvas.drawCircle(cx, cy, circleR, todayRingPaint)
                            todayNumPaint.color = Color.WHITE
                            drawTextCentered(canvas, text, cx, cy, todayNumPaint)
                        }
                        else -> {
                            todayNumPaint.color = Color.WHITE
                            drawTextCentered(canvas, text, cx, cy, todayNumPaint)
                        }
                    }
                } else {
                    val p = if (dimWeekends && isWeekend(day, firstCell, firstDow)) {
                        secondaryPaint
                    } else {
                        dayPaint
                    }
                    drawTextCentered(canvas, text, cx, cy, p)
                }
                day++
                col++
            }
            row++
        }
    }

    // ---------------------------------------------------------------------
    // Style 8: Premium card (existing style, corrected baselines + label fit).
    // ---------------------------------------------------------------------

    private fun drawPremium(canvas: Canvas, w: Float, h: Float, now: Calendar) {
        val inset = dp(10f)
        val cardL = inset
        val cardT = inset
        val cardR = w - inset
        val cardB = h - inset
        if (cardR - cardL < dp(80f) || cardB - cardT < dp(80f)) return

        val radius = dp(22f)
        premCardStroke.strokeWidth = dp(1f)
        canvas.drawRoundRect(cardL, cardT, cardR, cardB, radius, radius, premCardFill)
        canvas.drawRoundRect(cardL, cardT, cardR, cardB, radius, radius, premCardStroke)

        val pad = dp(14f)
        val left = cardL + pad
        val right = cardR - pad
        val contentW = right - left
        if (contentW <= 0f) return
        val dayWidth = contentW / 7f
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH)
        val locale = Locale.getDefault()
        val fDow = if (configuredFirstDow != 0) configuredFirstDow else now.firstDayOfWeek

        val titleFont = min(min(w, h) * 0.105f, dp(38f)).coerceAtLeast(dp(17f))
        val monthName = monthHeading(month)
        val yearStr = " $year"
        premTitlePaint.textSize = titleFont
        premTitleYearPaint.textSize = titleFont
        val mw = premTitlePaint.measureText(monthName)
        val yw = premTitleYearPaint.measureText(yearStr)
        val scale = if (mw + yw > contentW && mw + yw > 0f) contentW / (mw + yw) else 1f
        premTitlePaint.textSize = titleFont * scale
        premTitleYearPaint.textSize = titleFont * scale
        val tw = premTitlePaint.measureText(monthName) + premTitleYearPaint.measureText(yearStr)
        val tx = w / 2f - tw / 2f
        val titleCy = cardT + pad + textHeight(premTitlePaint) / 2f
        val titleBase = baselineForCenter(titleCy, premTitlePaint)
        canvas.drawText(monthName, tx, titleBase, premTitlePaint)
        canvas.drawText(yearStr, tx + premTitlePaint.measureText(monthName), titleBase, premTitleYearPaint)

        val weekdayFont = min(min(w, h) * 0.043f, dp(16f)).coerceAtLeast(dp(9f))
        premWeekdayPaint.textSize = weekdayFont
        val weekCy = titleCy + textHeight(premTitlePaint) / 2f + dp(12f) + textHeight(premWeekdayPaint) / 2f
        drawWeekLabels(
            canvas, left, dayWidth, weekCy, fDow, premWeekdayPaint,
            dayWidth * 0.88f, uppercase = true, locale = locale
        )

        val gridTop = weekCy + textHeight(premWeekdayPaint) / 2f + dp(7f)
        val gridBottom = cardB - pad
        val availRows = gridBottom - gridTop
        if (availRows <= 0f) return
        val weeks = weekCount()
        val rowH = availRows / weeks
        val gridFont = min(rowH * 0.53f, dayWidth * 0.60f).coerceAtLeast(dp(9f))
        val circleR = min(rowH * 0.35f, dayWidth * 0.35f)
        premTodayStroke.strokeWidth = dp(1f)

        premDayPaint.textSize = gridFont
        premWeekendPaint.textSize = gridFont
        premTodayNumPaint.textSize = gridFont
        premDayPaint.textAlign = Paint.Align.CENTER
        premWeekendPaint.textAlign = Paint.Align.CENTER
        premTodayNumPaint.textAlign = Paint.Align.CENTER

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = left + dayWidth * (col + 0.5f)
                val cy = gridTop + rowH * (row + 0.5f)
                val isToday = year == todayYear && month == todayMonth && day == todayDay
                if (isToday) {
                    canvas.drawCircle(cx, cy, circleR, premTodayFill)
                    canvas.drawCircle(cx, cy, circleR, premTodayStroke)
                    drawTextCentered(canvas, dayStrings[day], cx, cy, premTodayNumPaint)
                } else {
                    val p = if (isWeekend(day, firstCell, fDow)) premWeekendPaint else premDayPaint
                    drawTextCentered(canvas, dayStrings[day], cx, cy, p)
                }
                day++
                col++
            }
            row++
        }
    }

    // ---------------------------------------------------------------------
    // Style 10: FLIP_BOARD
    // ---------------------------------------------------------------------

    private fun drawFlipBoard(canvas: Canvas, w: Float, h: Float, now: Calendar) {
        val pad = dp(10f)
        val titleH = (min(w, h) * 0.105f).coerceIn(dp(19f), dp(40f))
        titlePaint.reset()
        titlePaint.isAntiAlias = true
        titlePaint.typeface = TF_BOLD
        titlePaint.textSize = titleH
        titlePaint.color = Color.WHITE
        titlePaint.textAlign = Paint.Align.CENTER
        val title = "${monthHeading(now.get(Calendar.MONTH))} ${now.get(Calendar.YEAR)}"
        titlePaint.textSize = fitTextSize(titlePaint, title, w - 2f * pad, titleH)
        val titleCy = pad + textHeight(titlePaint) / 2f
        drawTextCentered(canvas, title, w / 2f, titleCy, titlePaint)

        val firstDow = effectiveFirstDow(now)
        val weekdaySize = dp(10f).coerceAtMost(min(w, h) * 0.045f)
        weekdayPaint.reset()
        weekdayPaint.isAntiAlias = true
        weekdayPaint.typeface = TF_MEDIUM
        weekdayPaint.textSize = weekdaySize
        weekdayPaint.color = 0x8FFFFFFF.toInt()
        weekdayPaint.textAlign = Paint.Align.CENTER

        val left = pad
        val contentW = w - 2f * pad
        val dayW = contentW / 7f
        val weekCy = titleCy + textHeight(titlePaint) / 2f + dp(8f) + textHeight(weekdayPaint) / 2f
        drawWeekLabels(canvas, left, dayW, weekCy, firstDow, weekdayPaint, dayW * 0.82f, uppercase = true)

        val gridTop = weekCy + textHeight(weekdayPaint) / 2f + dp(6f)
        val gridBottom = h - pad
        val weeks = weekCount()
        val rowH = (gridBottom - gridTop) / weeks
        if (rowH <= dp(10f)) return
        val gap = min(dp(2.5f), min(dayW, rowH) * 0.06f)
        val radius = min(dp(5f), min(dayW, rowH) * 0.13f)
        val font = min(rowH * 0.43f, dayW * 0.45f).coerceAtLeast(dp(8f))

        dayPaint.reset()
        dayPaint.isAntiAlias = true
        dayPaint.typeface = TF_BOLD
        dayPaint.textSize = font
        dayPaint.color = 0xFFECEFF3.toInt()
        dayPaint.textAlign = Paint.Align.CENTER

        fillPaint.color = 0xFF17191D.toInt()
        linePaint.color = 0xFF050607.toInt()
        linePaint.strokeWidth = dp(1f)
        val accent = 0xFFFF9F0A.toInt()

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val l = left + col * dayW + gap
                val t = gridTop + row * rowH + gap
                val r = left + (col + 1) * dayW - gap
                val b = gridTop + (row + 1) * rowH - gap
                rect.set(l, t, r, b)
                val isToday = isToday(now, day)
                fillPaint.color = if (isToday) 0xFF332414.toInt() else 0xFF17191D.toInt()
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                linePaint.color = if (isToday) accent else 0xFF050607.toInt()
                linePaint.strokeWidth = if (isToday) dp(1.4f) else dp(1f)
                canvas.drawLine(l + gap, (t + b) / 2f, r - gap, (t + b) / 2f, linePaint)
                if (isToday) {
                    linePaint.style = Paint.Style.STROKE
                    canvas.drawRoundRect(rect, radius, radius, linePaint)
                    linePaint.style = Paint.Style.STROKE
                    dayPaint.color = 0xFFFFB74D.toInt()
                } else {
                    dayPaint.color = 0xFFECEFF3.toInt()
                }
                drawTextCentered(canvas, dayStrings[day], (l + r) / 2f, (t + b) / 2f, dayPaint)
                day++
                col++
            }
            row++
        }
    }

    // ---------------------------------------------------------------------
    // Style 11: GLASS
    // ---------------------------------------------------------------------

    private fun drawGlass(canvas: Canvas, w: Float, h: Float, now: Calendar) {
        val inset = dp(8f)
        val radius = dp(24f)
        fillPaint.color = 0x24182433
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius, radius, fillPaint)
        linePaint.color = 0x3DFFFFFF
        linePaint.strokeWidth = dp(1f)
        linePaint.style = Paint.Style.STROKE
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius, radius, linePaint)

        val left = inset + dp(12f)
        val right = w - inset - dp(12f)
        val contentW = right - left
        val dayW = contentW / 7f
        val titleSize = min(min(w, h) * 0.105f, dp(38f)).coerceAtLeast(dp(17f))
        titlePaint.reset()
        titlePaint.isAntiAlias = true
        titlePaint.typeface = TF_MEDIUM
        titlePaint.textSize = titleSize
        titlePaint.color = 0xFFF8FAFC.toInt()
        titlePaint.textAlign = Paint.Align.CENTER
        val title = "${monthHeading(now.get(Calendar.MONTH))} ${now.get(Calendar.YEAR)}"
        titlePaint.textSize = fitTextSize(titlePaint, title, contentW, titleSize)
        val titleCy = inset + dp(12f) + textHeight(titlePaint) / 2f
        drawTextCentered(canvas, title, w / 2f, titleCy, titlePaint)

        weekdayPaint.reset()
        weekdayPaint.isAntiAlias = true
        weekdayPaint.typeface = TF_MEDIUM
        weekdayPaint.textSize = dp(10f).coerceAtMost(min(w, h) * 0.043f)
        weekdayPaint.color = 0x80FFFFFF.toInt()
        weekdayPaint.textAlign = Paint.Align.CENTER
        val weekCy = titleCy + textHeight(titlePaint) / 2f + dp(10f) + textHeight(weekdayPaint) / 2f
        val firstDow = effectiveFirstDow(now)
        drawWeekLabels(canvas, left, dayW, weekCy, firstDow, weekdayPaint, dayW * 0.86f, uppercase = true)

        linePaint.color = 0x1FFFFFFF
        linePaint.strokeWidth = dp(0.7f)
        val gridTop = weekCy + textHeight(weekdayPaint) / 2f + dp(5f)
        val gridBottom = h - inset - dp(10f)
        val weeks = weekCount()
        val rowH = (gridBottom - gridTop) / weeks
        if (rowH <= 0f) return
        for (i in 1 until weeks) {
            val y = gridTop + rowH * i
            canvas.drawLine(left, y, right, y, linePaint)
        }

        val font = min(rowH * 0.50f, dayW * 0.56f).coerceAtLeast(dp(9f))
        dayPaint.reset()
        dayPaint.isAntiAlias = true
        dayPaint.typeface = TF_SANS
        dayPaint.textSize = font
        dayPaint.color = 0xD9FFFFFF.toInt()
        dayPaint.textAlign = Paint.Align.CENTER
        todayNumPaint.reset()
        todayNumPaint.isAntiAlias = true
        todayNumPaint.typeface = TF_MEDIUM
        todayNumPaint.textSize = font
        todayNumPaint.color = Color.WHITE
        todayNumPaint.textAlign = Paint.Align.CENTER

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = left + dayW * (col + 0.5f)
                val cy = gridTop + rowH * (row + 0.5f)
                if (isToday(now, day)) {
                    fillPaint.color = 0x667DD3FC
                    canvas.drawCircle(cx, cy, min(dayW, rowH) * 0.34f, fillPaint)
                    drawTextCentered(canvas, dayStrings[day], cx, cy, todayNumPaint)
                } else {
                    drawTextCentered(canvas, dayStrings[day], cx, cy, dayPaint)
                }
                day++
                col++
            }
            row++
        }
    }

    // ---------------------------------------------------------------------
    // Style 12: MATERIAL
    // ---------------------------------------------------------------------

    private fun drawMaterial(canvas: Canvas, w: Float, h: Float, now: Calendar) {
        val accent = 0xFF8AB4F8.toInt()
        val left = dp(14f)
        val right = w - dp(14f)
        val contentW = (right - left).coerceAtLeast(1f)
        val dayW = contentW / 7f

        titlePaint.reset()
        titlePaint.isAntiAlias = true
        titlePaint.typeface = TF_MEDIUM
        titlePaint.color = 0xFFE8EAED.toInt()
        titlePaint.textAlign = Paint.Align.LEFT
        val maxTitle = min(min(w, h) * 0.13f, dp(46f)).coerceAtLeast(dp(19f))
        val title = monthHeading(now.get(Calendar.MONTH))
        titlePaint.textSize = fitTextSize(titlePaint, title, contentW * 0.72f, maxTitle)
        val titleCy = dp(10f) + textHeight(titlePaint) / 2f
        canvas.drawText(title, left, baselineForCenter(titleCy, titlePaint), titlePaint)

        secondaryPaint.reset()
        secondaryPaint.isAntiAlias = true
        secondaryPaint.typeface = TF_MEDIUM
        secondaryPaint.textSize = min(titlePaint.textSize * 0.55f, dp(18f))
        secondaryPaint.color = 0xFF9AA0A6.toInt()
        secondaryPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(now.get(Calendar.YEAR).toString(), right, baselineForCenter(titleCy, secondaryPaint), secondaryPaint)

        fillPaint.color = accent
        val barTop = titleCy + textHeight(titlePaint) / 2f + dp(7f)
        canvas.drawRoundRect(left, barTop, left + contentW * 0.22f, barTop + dp(3f), dp(1.5f), dp(1.5f), fillPaint)

        weekdayPaint.reset()
        weekdayPaint.isAntiAlias = true
        weekdayPaint.typeface = TF_MEDIUM
        weekdayPaint.textSize = min(min(w, h) * 0.042f, dp(14f)).coerceAtLeast(dp(8f))
        weekdayPaint.color = 0xFF9AA0A6.toInt()
        weekdayPaint.textAlign = Paint.Align.CENTER
        val weekCy = barTop + dp(10f) + textHeight(weekdayPaint) / 2f
        val firstDow = effectiveFirstDow(now)
        drawWeekLabels(canvas, left, dayW, weekCy, firstDow, weekdayPaint, dayW * 0.9f, uppercase = true)

        val gridTop = weekCy + textHeight(weekdayPaint) / 2f + dp(6f)
        val gridBottom = h - dp(8f)
        val weeks = weekCount()
        val rowH = (gridBottom - gridTop) / weeks
        if (rowH <= 0f) return
        val font = min(rowH * 0.50f, dayW * 0.55f).coerceAtLeast(dp(9f))

        dayPaint.reset()
        dayPaint.isAntiAlias = true
        dayPaint.typeface = TF_SANS
        dayPaint.textSize = font
        dayPaint.color = 0xFFE8EAED.toInt()
        dayPaint.textAlign = Paint.Align.CENTER
        secondaryPaint.textSize = font
        secondaryPaint.typeface = TF_SANS
        secondaryPaint.textAlign = Paint.Align.CENTER
        secondaryPaint.color = 0xFF74787D.toInt()
        todayNumPaint.reset()
        todayNumPaint.isAntiAlias = true
        todayNumPaint.typeface = TF_MEDIUM
        todayNumPaint.textSize = font
        todayNumPaint.color = 0xFF202124.toInt()
        todayNumPaint.textAlign = Paint.Align.CENTER

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = left + dayW * (col + 0.5f)
                val cy = gridTop + rowH * (row + 0.5f)
                when {
                    isToday(now, day) -> {
                        val r = min(dayW, rowH) * 0.34f
                        fillPaint.color = accent
                        canvas.drawRoundRect(cx - r, cy - r, cx + r, cy + r, r, r, fillPaint)
                        drawTextCentered(canvas, dayStrings[day], cx, cy, todayNumPaint)
                    }
                    isWeekend(day, firstCell, firstDow) ->
                        drawTextCentered(canvas, dayStrings[day], cx, cy, secondaryPaint)
                    else -> drawTextCentered(canvas, dayStrings[day], cx, cy, dayPaint)
                }
                day++
                col++
            }
            row++
        }
    }

    // ---------------------------------------------------------------------
    // Style 13: TERMINAL
    // ---------------------------------------------------------------------

    private fun drawTerminal(canvas: Canvas, w: Float, h: Float, now: Calendar) {
        val green = 0xFF65FF8F.toInt()
        val dimGreen = 0x8065FF8F.toInt()
        val faintGreen = 0x3365FF8F
        val pad = dp(9f)
        val left = pad
        val right = w - pad
        val contentW = right - left

        linePaint.color = faintGreen
        linePaint.strokeWidth = dp(1f)
        linePaint.style = Paint.Style.STROKE
        canvas.drawRect(left, pad, right, h - pad, linePaint)

        titlePaint.reset()
        titlePaint.isAntiAlias = true
        titlePaint.typeface = TF_MONO
        titlePaint.color = green
        titlePaint.textAlign = Paint.Align.LEFT
        val title = "> ${monthHeading(now.get(Calendar.MONTH)).uppercase(Locale.getDefault())} ${now.get(Calendar.YEAR)}"
        val maxTitle = min(min(w, h) * 0.095f, dp(31f)).coerceAtLeast(dp(14f))
        titlePaint.textSize = fitTextSize(titlePaint, title, contentW - dp(8f), maxTitle)
        val titleCy = pad + dp(7f) + textHeight(titlePaint) / 2f
        canvas.drawText(title, left + dp(6f), baselineForCenter(titleCy, titlePaint), titlePaint)

        val firstDow = effectiveFirstDow(now)
        val dayW = contentW / 7f
        weekdayPaint.reset()
        weekdayPaint.isAntiAlias = true
        weekdayPaint.typeface = TF_MONO
        weekdayPaint.color = dimGreen
        weekdayPaint.textSize = min(min(w, h) * 0.038f, dp(13f)).coerceAtLeast(dp(8f))
        weekdayPaint.textAlign = Paint.Align.CENTER
        val weekCy = titleCy + textHeight(titlePaint) / 2f + dp(7f) + textHeight(weekdayPaint) / 2f
        drawWeekLabels(canvas, left, dayW, weekCy, firstDow, weekdayPaint, dayW * 0.88f, uppercase = true)

        val gridTop = weekCy + textHeight(weekdayPaint) / 2f + dp(5f)
        val gridBottom = h - pad
        val weeks = weekCount()
        val rowH = (gridBottom - gridTop) / weeks
        if (rowH <= 0f) return

        for (i in 1 until 7) {
            val x = left + dayW * i
            canvas.drawLine(x, gridTop, x, gridBottom, linePaint)
        }
        for (i in 0..weeks) {
            val y = gridTop + rowH * i
            canvas.drawLine(left, y, right, y, linePaint)
        }

        val font = min(rowH * 0.45f, dayW * 0.45f).coerceAtLeast(dp(8f))
        dayPaint.reset()
        dayPaint.isAntiAlias = true
        dayPaint.typeface = TF_MONO
        dayPaint.textSize = font
        dayPaint.color = green
        dayPaint.textAlign = Paint.Align.CENTER
        todayNumPaint.reset()
        todayNumPaint.isAntiAlias = true
        todayNumPaint.typeface = TF_MONO
        todayNumPaint.textSize = font
        todayNumPaint.color = Color.BLACK
        todayNumPaint.textAlign = Paint.Align.CENTER

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = left + dayW * (col + 0.5f)
                val cy = gridTop + rowH * (row + 0.5f)
                if (isToday(now, day)) {
                    fillPaint.color = green
                    val rx = dayW * 0.34f
                    val ry = rowH * 0.30f
                    canvas.drawRect(cx - rx, cy - ry, cx + rx, cy + ry, fillPaint)
                    drawTextCentered(canvas, dayStrings[day], cx, cy, todayNumPaint)
                } else {
                    drawTextCentered(canvas, dayStrings[day], cx, cy, dayPaint)
                }
                day++
                col++
            }
            row++
        }
    }

    // ---------------------------------------------------------------------
    // Style 14: DOT_MATRIX
    // ---------------------------------------------------------------------

    private fun drawDotMatrix(canvas: Canvas, w: Float, h: Float, now: Calendar) {
        val accent = 0xFFFFC857.toInt()
        val main = 0xFFDDE7F0.toInt()
        val dim = 0x557A8792
        val pad = dp(10f)
        val left = pad
        val right = w - pad
        val contentW = right - left
        val dayW = contentW / 7f

        titlePaint.reset()
        titlePaint.isAntiAlias = true
        titlePaint.typeface = TF_MONO
        titlePaint.color = main
        titlePaint.textAlign = Paint.Align.CENTER
        val title = "${monthHeading(now.get(Calendar.MONTH)).uppercase(Locale.getDefault())}  ${now.get(Calendar.YEAR)}"
        val maxTitle = min(min(w, h) * 0.09f, dp(30f)).coerceAtLeast(dp(13f))
        titlePaint.textSize = fitTextSize(titlePaint, title, contentW, maxTitle)
        val titleCy = pad + textHeight(titlePaint) / 2f
        drawTextCentered(canvas, title, w / 2f, titleCy, titlePaint)

        weekdayPaint.reset()
        weekdayPaint.isAntiAlias = true
        weekdayPaint.typeface = TF_MONO
        weekdayPaint.textSize = min(min(w, h) * 0.036f, dp(12f)).coerceAtLeast(dp(7f))
        weekdayPaint.color = 0x7797A6B3
        weekdayPaint.textAlign = Paint.Align.CENTER
        val firstDow = effectiveFirstDow(now)
        val weekCy = titleCy + textHeight(titlePaint) / 2f + dp(7f) + textHeight(weekdayPaint) / 2f
        drawWeekLabels(canvas, left, dayW, weekCy, firstDow, weekdayPaint, dayW * 0.88f, uppercase = true)

        val gridTop = weekCy + textHeight(weekdayPaint) / 2f + dp(5f)
        val gridBottom = h - pad
        val weeks = weekCount()
        val rowH = (gridBottom - gridTop) / weeks
        if (rowH <= 0f) return

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = left + dayW * (col + 0.5f)
                val cy = gridTop + rowH * (row + 0.5f)
                val isToday = isToday(now, day)
                if (isToday) {
                    fillPaint.color = 0x2EFFC857
                    canvas.drawCircle(cx, cy, min(dayW, rowH) * 0.38f, fillPaint)
                }
                drawDotNumber(
                    canvas, day, cx, cy,
                    min(dayW * 0.72f, rowH * 0.62f),
                    if (isToday) accent else main,
                    dim
                )
                day++
                col++
            }
            row++
        }
    }

    private fun drawDotNumber(
        canvas: Canvas,
        value: Int,
        cx: Float,
        cy: Float,
        box: Float,
        onColor: Int,
        offColor: Int
    ) {
        val tens = if (value >= 10) value / 10 else -1
        val ones = value % 10
        val digitCount = if (tens >= 0) 2 else 1
        val cols = digitCount * 3 + (digitCount - 1)
        val step = min(box / cols, box / 5f)
        val r = (step * 0.30f).coerceAtLeast(0.6f)
        val totalW = step * cols
        val totalH = step * 5f
        var x0 = cx - totalW / 2f + step / 2f
        val y0 = cy - totalH / 2f + step / 2f

        fun digit(d: Int, baseX: Float) {
            val pat = DOT_DIGITS[d]
            for (row in 0 until 5) {
                for (col in 0 until 3) {
                    val bit = (pat[row] shr (2 - col)) and 1
                    fillPaint.color = if (bit != 0) onColor else offColor
                    canvas.drawCircle(baseX + col * step, y0 + row * step, r, fillPaint)
                }
            }
        }

        if (tens >= 0) {
            digit(tens, x0)
            x0 += step * 4f
        }
        digit(ones, x0)
    }

    // ---------------------------------------------------------------------
    // Style 15: SWISS
    // ---------------------------------------------------------------------

    private fun drawSwiss(canvas: Canvas, w: Float, h: Float, now: Calendar) {
        val red = 0xFFFF453A.toInt()
        val left = dp(12f)
        val right = w - dp(12f)
        val contentW = right - left

        titlePaint.reset()
        titlePaint.isAntiAlias = true
        titlePaint.typeface = TF_BLACK
        titlePaint.color = Color.WHITE
        titlePaint.textAlign = Paint.Align.LEFT
        val title = monthHeading(now.get(Calendar.MONTH)).uppercase(Locale.getDefault())
        val maxTitle = min(min(w, h) * 0.15f, dp(54f)).coerceAtLeast(dp(21f))
        titlePaint.textSize = fitTextSize(titlePaint, title, contentW * 0.78f, maxTitle)
        val titleCy = dp(8f) + textHeight(titlePaint) / 2f
        canvas.drawText(title, left, baselineForCenter(titleCy, titlePaint), titlePaint)

        secondaryPaint.reset()
        secondaryPaint.isAntiAlias = true
        secondaryPaint.typeface = TF_BOLD
        secondaryPaint.textSize = min(titlePaint.textSize * 0.42f, dp(18f))
        secondaryPaint.color = red
        secondaryPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(now.get(Calendar.YEAR).toString(), right, baselineForCenter(titleCy, secondaryPaint), secondaryPaint)

        fillPaint.color = red
        val lineY = titleCy + textHeight(titlePaint) / 2f + dp(4f)
        canvas.drawRect(left, lineY, right, lineY + dp(3f), fillPaint)

        val dayW = contentW / 7f
        weekdayPaint.reset()
        weekdayPaint.isAntiAlias = true
        weekdayPaint.typeface = TF_BOLD
        weekdayPaint.textSize = min(min(w, h) * 0.038f, dp(13f)).coerceAtLeast(dp(8f))
        weekdayPaint.color = 0xFFB5B8BD.toInt()
        weekdayPaint.textAlign = Paint.Align.CENTER
        val weekCy = lineY + dp(8f) + textHeight(weekdayPaint) / 2f
        val firstDow = effectiveFirstDow(now)
        drawWeekLabels(canvas, left, dayW, weekCy, firstDow, weekdayPaint, dayW * 0.9f, uppercase = true)

        val gridTop = weekCy + textHeight(weekdayPaint) / 2f + dp(5f)
        val gridBottom = h - dp(8f)
        val weeks = weekCount()
        val rowH = (gridBottom - gridTop) / weeks
        val font = min(rowH * 0.52f, dayW * 0.57f).coerceAtLeast(dp(9f))

        dayPaint.reset()
        dayPaint.isAntiAlias = true
        dayPaint.typeface = TF_BOLD
        dayPaint.textSize = font
        dayPaint.color = Color.WHITE
        dayPaint.textAlign = Paint.Align.CENTER

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = left + dayW * (col + 0.5f)
                val cy = gridTop + rowH * (row + 0.5f)
                if (isToday(now, day)) {
                    fillPaint.color = red
                    val barW = min(dayW * 0.13f, dp(4f))
                    canvas.drawRect(
                        cx - dayW * 0.34f,
                        cy - rowH * 0.30f,
                        cx - dayW * 0.34f + barW,
                        cy + rowH * 0.30f,
                        fillPaint
                    )
                    dayPaint.color = red
                    drawTextCentered(canvas, dayStrings[day], cx, cy, dayPaint)
                    dayPaint.color = Color.WHITE
                } else {
                    drawTextCentered(canvas, dayStrings[day], cx, cy, dayPaint)
                }
                day++
                col++
            }
            row++
        }
    }

    // ---------------------------------------------------------------------
    // Style 16: TILES
    // ---------------------------------------------------------------------

    private fun drawTiles(canvas: Canvas, w: Float, h: Float, now: Calendar) {
        val pad = dp(8f)
        val left = pad
        val right = w - pad
        val contentW = right - left
        val dayW = contentW / 7f
        val accent = 0xFF5EEAD4.toInt()

        titlePaint.reset()
        titlePaint.isAntiAlias = true
        titlePaint.typeface = TF_MEDIUM
        titlePaint.textAlign = Paint.Align.CENTER
        titlePaint.color = Color.WHITE
        val title = "${monthHeading(now.get(Calendar.MONTH))} ${now.get(Calendar.YEAR)}"
        val maxTitle = min(min(w, h) * 0.10f, dp(34f)).coerceAtLeast(dp(15f))
        titlePaint.textSize = fitTextSize(titlePaint, title, contentW, maxTitle)
        val titleCy = pad + textHeight(titlePaint) / 2f
        drawTextCentered(canvas, title, w / 2f, titleCy, titlePaint)

        weekdayPaint.reset()
        weekdayPaint.isAntiAlias = true
        weekdayPaint.typeface = TF_MEDIUM
        weekdayPaint.textSize = min(min(w, h) * 0.036f, dp(12f)).coerceAtLeast(dp(7f))
        weekdayPaint.color = 0x779CA3AF
        weekdayPaint.textAlign = Paint.Align.CENTER
        val firstDow = effectiveFirstDow(now)
        val weekCy = titleCy + textHeight(titlePaint) / 2f + dp(5f) + textHeight(weekdayPaint) / 2f
        drawWeekLabels(canvas, left, dayW, weekCy, firstDow, weekdayPaint, dayW * 0.86f, uppercase = true)

        val gridTop = weekCy + textHeight(weekdayPaint) / 2f + dp(4f)
        val gridBottom = h - pad
        val weeks = weekCount()
        val rowH = (gridBottom - gridTop) / weeks
        if (rowH <= 0f) return
        val gap = min(dp(2f), min(dayW, rowH) * 0.07f)
        val radius = min(dp(7f), min(dayW, rowH) * 0.18f)
        val font = min(rowH * 0.43f, dayW * 0.43f).coerceAtLeast(dp(8f))

        dayPaint.reset()
        dayPaint.isAntiAlias = true
        dayPaint.typeface = TF_MEDIUM
        dayPaint.textSize = font
        dayPaint.textAlign = Paint.Align.CENTER

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val l = left + col * dayW + gap
                val t = gridTop + row * rowH + gap
                val r = left + (col + 1) * dayW - gap
                val b = gridTop + (row + 1) * rowH - gap
                val today = isToday(now, day)
                fillPaint.color = if (today) 0xFF163B39.toInt() else 0xFF14171C.toInt()
                canvas.drawRoundRect(l, t, r, b, radius, radius, fillPaint)
                linePaint.style = Paint.Style.STROKE
                linePaint.strokeWidth = if (today) dp(1.2f) else dp(0.7f)
                linePaint.color = if (today) accent else 0xFF272C33.toInt()
                canvas.drawRoundRect(l, t, r, b, radius, radius, linePaint)
                dayPaint.color = when {
                    today -> accent
                    isWeekend(day, firstCell, firstDow) -> 0xFF818894.toInt()
                    else -> 0xFFE8EBEF.toInt()
                }
                drawTextCentered(canvas, dayStrings[day], (l + r) / 2f, (t + b) / 2f, dayPaint)
                day++
                col++
            }
            row++
        }
    }

    // ---------------------------------------------------------------------
    // Style 17: WEEK_FOCUS
    // ---------------------------------------------------------------------

    private fun drawWeekFocus(canvas: Canvas, w: Float, h: Float, now: Calendar) {
        val pad = dp(10f)
        val left = pad
        val right = w - pad
        val contentW = right - left
        val dayW = contentW / 7f
        val firstDow = effectiveFirstDow(now)

        titlePaint.reset()
        titlePaint.isAntiAlias = true
        titlePaint.typeface = TF_MEDIUM
        titlePaint.textAlign = Paint.Align.CENTER
        titlePaint.color = Color.WHITE
        val title = "${monthHeading(now.get(Calendar.MONTH))} ${now.get(Calendar.YEAR)}"
        val maxTitle = min(min(w, h) * 0.105f, dp(36f)).coerceAtLeast(dp(16f))
        titlePaint.textSize = fitTextSize(titlePaint, title, contentW, maxTitle)
        val titleCy = pad + textHeight(titlePaint) / 2f
        drawTextCentered(canvas, title, w / 2f, titleCy, titlePaint)

        weekdayPaint.reset()
        weekdayPaint.isAntiAlias = true
        weekdayPaint.typeface = TF_MEDIUM
        weekdayPaint.textSize = min(min(w, h) * 0.037f, dp(12f)).coerceAtLeast(dp(7f))
        weekdayPaint.color = 0x709CA3AF
        weekdayPaint.textAlign = Paint.Align.CENTER
        val weekCy = titleCy + textHeight(titlePaint) / 2f + dp(6f) + textHeight(weekdayPaint) / 2f
        drawWeekLabels(canvas, left, dayW, weekCy, firstDow, weekdayPaint, dayW * 0.86f, uppercase = true)

        val gridTop = weekCy + textHeight(weekdayPaint) / 2f + dp(5f)
        val gridBottom = h - pad
        val weeks = weekCount()
        val rowH = (gridBottom - gridTop) / weeks
        if (rowH <= 0f) return
        val focusRow = if (todayMonth == now.get(Calendar.MONTH) && todayYear == now.get(Calendar.YEAR)) {
            (firstCell + todayDay - 1) / 7
        } else -1

        if (focusRow in 0 until weeks) {
            fillPaint.color = 0x18FFFFFF
            val t = gridTop + focusRow * rowH + dp(1f)
            val b = gridTop + (focusRow + 1) * rowH - dp(1f)
            canvas.drawRoundRect(left, t, right, b, dp(10f), dp(10f), fillPaint)
        }

        val font = min(rowH * 0.51f, dayW * 0.56f).coerceAtLeast(dp(9f))
        dayPaint.reset()
        dayPaint.isAntiAlias = true
        dayPaint.typeface = TF_SANS
        dayPaint.textSize = font
        dayPaint.textAlign = Paint.Align.CENTER
        secondaryPaint.reset()
        secondaryPaint.isAntiAlias = true
        secondaryPaint.typeface = TF_SANS
        secondaryPaint.textSize = font
        secondaryPaint.textAlign = Paint.Align.CENTER
        secondaryPaint.color = 0x4DFFFFFF
        todayNumPaint.reset()
        todayNumPaint.isAntiAlias = true
        todayNumPaint.typeface = TF_MEDIUM
        todayNumPaint.textSize = font
        todayNumPaint.color = Color.BLACK
        todayNumPaint.textAlign = Paint.Align.CENTER

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = left + dayW * (col + 0.5f)
                val cy = gridTop + rowH * (row + 0.5f)
                when {
                    isToday(now, day) -> {
                        fillPaint.color = Color.WHITE
                        canvas.drawCircle(cx, cy, min(dayW, rowH) * 0.31f, fillPaint)
                        drawTextCentered(canvas, dayStrings[day], cx, cy, todayNumPaint)
                    }
                    row == focusRow -> {
                        dayPaint.color = Color.WHITE
                        drawTextCentered(canvas, dayStrings[day], cx, cy, dayPaint)
                    }
                    else -> drawTextCentered(canvas, dayStrings[day], cx, cy, secondaryPaint)
                }
                day++
                col++
            }
            row++
        }
    }

    // ---------------------------------------------------------------------
    // Style 18: NEON
    // ---------------------------------------------------------------------

    private fun drawNeon(canvas: Canvas, w: Float, h: Float, now: Calendar) {
        val cyan = 0xFF67E8F9.toInt()
        val magenta = 0xFFF472B6.toInt()
        val pad = dp(10f)
        val left = pad
        val right = w - pad
        val contentW = right - left
        val dayW = contentW / 7f

        titlePaint.reset()
        titlePaint.isAntiAlias = true
        titlePaint.typeface = TF_BOLD
        titlePaint.textAlign = Paint.Align.CENTER
        val title = "${monthHeading(now.get(Calendar.MONTH)).uppercase(Locale.getDefault())} ${now.get(Calendar.YEAR)}"
        val maxTitle = min(min(w, h) * 0.105f, dp(38f)).coerceAtLeast(dp(16f))
        titlePaint.textSize = fitTextSize(titlePaint, title, contentW, maxTitle)
        val titleCy = pad + textHeight(titlePaint) / 2f

        // Cheap OLED-safe glow: three outline passes, no BlurMaskFilter/bitmap.
        titlePaint.style = Paint.Style.FILL_AND_STROKE
        titlePaint.strokeJoin = Paint.Join.ROUND
        titlePaint.strokeWidth = dp(5f)
        titlePaint.color = 0x2467E8F9
        drawTextCentered(canvas, title, w / 2f, titleCy, titlePaint)
        titlePaint.strokeWidth = dp(2.5f)
        titlePaint.color = 0x5267E8F9
        drawTextCentered(canvas, title, w / 2f, titleCy, titlePaint)
        titlePaint.style = Paint.Style.FILL
        titlePaint.color = cyan
        drawTextCentered(canvas, title, w / 2f, titleCy, titlePaint)

        weekdayPaint.reset()
        weekdayPaint.isAntiAlias = true
        weekdayPaint.typeface = TF_MEDIUM
        weekdayPaint.textSize = min(min(w, h) * 0.037f, dp(12f)).coerceAtLeast(dp(7f))
        weekdayPaint.color = 0x9967E8F9.toInt()
        weekdayPaint.textAlign = Paint.Align.CENTER
        val firstDow = effectiveFirstDow(now)
        val weekCy = titleCy + textHeight(titlePaint) / 2f + dp(7f) + textHeight(weekdayPaint) / 2f
        drawWeekLabels(canvas, left, dayW, weekCy, firstDow, weekdayPaint, dayW * 0.86f, uppercase = true)

        val gridTop = weekCy + textHeight(weekdayPaint) / 2f + dp(5f)
        val gridBottom = h - pad
        val weeks = weekCount()
        val rowH = (gridBottom - gridTop) / weeks
        if (rowH <= 0f) return
        val font = min(rowH * 0.49f, dayW * 0.54f).coerceAtLeast(dp(9f))

        dayPaint.reset()
        dayPaint.isAntiAlias = true
        dayPaint.typeface = TF_MEDIUM
        dayPaint.textSize = font
        dayPaint.textAlign = Paint.Align.CENTER
        dayPaint.style = Paint.Style.FILL
        dayPaint.color = cyan

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = left + dayW * (col + 0.5f)
                val cy = gridTop + rowH * (row + 0.5f)
                if (isToday(now, day)) {
                    linePaint.style = Paint.Style.STROKE
                    linePaint.strokeWidth = dp(5f)
                    linePaint.color = 0x22F472B6
                    canvas.drawCircle(cx, cy, min(dayW, rowH) * 0.34f, linePaint)
                    linePaint.strokeWidth = dp(2f)
                    linePaint.color = magenta
                    canvas.drawCircle(cx, cy, min(dayW, rowH) * 0.34f, linePaint)
                    dayPaint.color = magenta
                    drawTextCentered(canvas, dayStrings[day], cx, cy, dayPaint)
                    dayPaint.color = cyan
                } else {
                    drawTextCentered(canvas, dayStrings[day], cx, cy, dayPaint)
                }
                day++
                col++
            }
            row++
        }
    }

    // ---------------------------------------------------------------------
    // Style 19: BIG_DATE
    // ---------------------------------------------------------------------

    private fun drawBigDate(canvas: Canvas, w: Float, h: Float, now: Calendar) {
        val landscape = w >= h * 1.15f
        if (!landscape) {
            // Portrait fallback: a big header above the mini month. This avoids
            // forcing two unreadably narrow columns on phones in portrait.
            drawBigDatePortrait(canvas, w, h, now)
            return
        }

        val split = w * 0.36f
        val pad = dp(10f)
        val accent = 0xFFFB923C.toInt()

        // Left: giant current day.
        titlePaint.reset()
        titlePaint.isAntiAlias = true
        titlePaint.typeface = TF_BLACK
        titlePaint.textAlign = Paint.Align.CENTER
        titlePaint.color = Color.WHITE
        titlePaint.textSize = min(h * 0.50f, split * 0.70f).coerceAtLeast(dp(32f))
        val dayCy = h * 0.43f
        drawTextCentered(canvas, dayStrings[todayDay], split / 2f, dayCy, titlePaint)

        val dow = now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault())
            ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }.orEmpty()
        secondaryPaint.reset()
        secondaryPaint.isAntiAlias = true
        secondaryPaint.typeface = TF_MEDIUM
        secondaryPaint.textAlign = Paint.Align.CENTER
        secondaryPaint.textSize = min(h * 0.07f, dp(22f)).coerceAtLeast(dp(10f))
        secondaryPaint.color = accent
        drawTextCentered(canvas, dow, split / 2f, dayCy + textHeight(titlePaint) * 0.42f, secondaryPaint)

        secondaryPaint.textSize = min(h * 0.055f, dp(18f)).coerceAtLeast(dp(9f))
        secondaryPaint.color = 0x99FFFFFF.toInt()
        val monYear = "${monthHeading(now.get(Calendar.MONTH))} ${now.get(Calendar.YEAR)}"
        secondaryPaint.textSize = fitTextSize(secondaryPaint, monYear, split - 2f * pad, secondaryPaint.textSize)
        drawTextCentered(canvas, monYear, split / 2f, h - pad - textHeight(secondaryPaint) / 2f, secondaryPaint)

        linePaint.color = 0x30FFFFFF
        linePaint.strokeWidth = dp(1f)
        canvas.drawLine(split, pad, split, h - pad, linePaint)

        // Right: compact month grid.
        drawMiniGrid(canvas, split + pad, pad, w - pad, h - pad, now, accent)
    }

    private fun drawBigDatePortrait(canvas: Canvas, w: Float, h: Float, now: Calendar) {
        val accent = 0xFFFB923C.toInt()
        val pad = dp(10f)
        val headerH = h * 0.30f

        titlePaint.reset()
        titlePaint.isAntiAlias = true
        titlePaint.typeface = TF_BLACK
        titlePaint.textAlign = Paint.Align.LEFT
        titlePaint.color = Color.WHITE
        titlePaint.textSize = min(headerH * 0.63f, w * 0.30f).coerceAtLeast(dp(32f))
        val cy = pad + headerH * 0.47f
        canvas.drawText(dayStrings[todayDay], pad, baselineForCenter(cy, titlePaint), titlePaint)

        secondaryPaint.reset()
        secondaryPaint.isAntiAlias = true
        secondaryPaint.typeface = TF_MEDIUM
        secondaryPaint.textAlign = Paint.Align.LEFT
        secondaryPaint.textSize = min(headerH * 0.16f, dp(18f)).coerceAtLeast(dp(9f))
        secondaryPaint.color = accent
        val infoX = pad + titlePaint.measureText(dayStrings[todayDay]) + dp(10f)
        val mon = monthHeading(now.get(Calendar.MONTH)).uppercase(Locale.getDefault())
        canvas.drawText(mon, infoX, baselineForCenter(cy - secondaryPaint.textSize * 0.65f, secondaryPaint), secondaryPaint)
        secondaryPaint.color = 0x99FFFFFF.toInt()
        canvas.drawText(now.get(Calendar.YEAR).toString(), infoX, baselineForCenter(cy + secondaryPaint.textSize * 0.65f, secondaryPaint), secondaryPaint)

        drawMiniGrid(canvas, pad, headerH, w - pad, h - pad, now, accent)
    }

    private fun drawMiniGrid(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        now: Calendar,
        accent: Int
    ) {
        val contentW = right - left
        val contentH = bottom - top
        if (contentW <= dp(70f) || contentH <= dp(70f)) return
        val dayW = contentW / 7f
        val firstDow = effectiveFirstDow(now)

        weekdayPaint.reset()
        weekdayPaint.isAntiAlias = true
        weekdayPaint.typeface = TF_MEDIUM
        weekdayPaint.textSize = min(contentH * 0.055f, dp(12f)).coerceAtLeast(dp(7f))
        weekdayPaint.color = 0x669CA3AF
        weekdayPaint.textAlign = Paint.Align.CENTER
        val weekCy = top + textHeight(weekdayPaint) / 2f
        drawWeekLabels(canvas, left, dayW, weekCy, firstDow, weekdayPaint, dayW * 0.88f, uppercase = true)

        val gridTop = weekCy + textHeight(weekdayPaint) / 2f + dp(4f)
        val weeks = weekCount()
        val rowH = (bottom - gridTop) / weeks
        val font = min(rowH * 0.49f, dayW * 0.53f).coerceAtLeast(dp(8f))
        dayPaint.reset()
        dayPaint.isAntiAlias = true
        dayPaint.typeface = TF_SANS
        dayPaint.textSize = font
        dayPaint.textAlign = Paint.Align.CENTER
        dayPaint.color = 0xD9FFFFFF.toInt()
        todayNumPaint.reset()
        todayNumPaint.isAntiAlias = true
        todayNumPaint.typeface = TF_MEDIUM
        todayNumPaint.textSize = font
        todayNumPaint.textAlign = Paint.Align.CENTER
        todayNumPaint.color = Color.BLACK

        var day = 1
        var row = 0
        while (day <= daysInMonth) {
            var col = if (row == 0) firstCell else 0
            while (col < 7 && day <= daysInMonth) {
                val cx = left + dayW * (col + 0.5f)
                val cy = gridTop + rowH * (row + 0.5f)
                if (isToday(now, day)) {
                    fillPaint.color = accent
                    canvas.drawCircle(cx, cy, min(dayW, rowH) * 0.31f, fillPaint)
                    drawTextCentered(canvas, dayStrings[day], cx, cy, todayNumPaint)
                } else {
                    drawTextCentered(canvas, dayStrings[day], cx, cy, dayPaint)
                }
                day++
                col++
            }
            row++
        }
    }

    // ---------------------------------------------------------------------
    // Shared helpers.
    // ---------------------------------------------------------------------

    private fun effectiveFirstDow(now: Calendar): Int =
        if (configuredFirstDow != 0) configuredFirstDow else now.firstDayOfWeek

    private fun weekCount(): Int =
        ceil((firstCell + daysInMonth) / 7.0).toInt().coerceIn(4, 6)

    private fun isToday(now: Calendar, day: Int): Boolean =
        now.get(Calendar.YEAR) == todayYear &&
            now.get(Calendar.MONTH) == todayMonth && day == todayDay

    /** Calendar.DAY_OF_WEEK (1=Sun..7=Sat) for [day] in the shown month. */
    private fun isWeekend(day: Int, firstCell: Int, firstDow: Int): Boolean {
        val dow = ((firstDow - 1 + firstCell + (day - 1)) % 7) + 1
        return dow == Calendar.SUNDAY || dow == Calendar.SATURDAY
    }

    private fun textHeight(p: Paint): Float {
        val fm = p.fontMetrics
        return fm.descent - fm.ascent
    }

    private fun baselineForCenter(cy: Float, p: Paint): Float {
        val fm = p.fontMetrics
        return cy - (fm.ascent + fm.descent) / 2f
    }

    private fun drawTextCentered(canvas: Canvas, text: String, cx: Float, cy: Float, p: Paint) {
        p.textAlign = Paint.Align.CENTER
        canvas.drawText(text, cx, baselineForCenter(cy, p), p)
    }

    /** One-shot fit; avoids per-frame decrement loops for long localized labels. */
    private fun fitTextSize(p: Paint, text: String, maxWidth: Float, preferred: Float): Float {
        p.textSize = preferred
        val width = p.measureText(text)
        if (width <= maxWidth || width <= 0f) return preferred
        val scaled = preferred * maxWidth / width
        p.textSize = scaled
        return scaled
    }

    private fun drawWeekLabels(
        canvas: Canvas,
        left: Float,
        dayWidth: Float,
        cy: Float,
        firstDow: Int,
        p: Paint,
        maxLabelWidth: Float,
        uppercase: Boolean = false,
        locale: Locale = Locale.getDefault()
    ) {
        val preferred = p.textSize
        for (i in 0 until 7) {
            val weekday = (firstDow - 1 + i) % 7 + 1
            var label = weekLabels[weekday - 1]
            if (uppercase) label = label.uppercase(locale)
            if (label.isEmpty()) continue
            p.textSize = preferred
            val width = p.measureText(label)
            if (width > maxLabelWidth && width > 0f) {
                p.textSize = (preferred * maxLabelWidth / width).coerceAtLeast(dp(6f))
            }
            drawTextCentered(canvas, label, left + dayWidth * (i + 0.5f), cy, p)
        }
        p.textSize = preferred
    }

    private fun rebuild(year: Int, month: Int, fDow: Int) {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month, 1)
        firstCell = (c.get(Calendar.DAY_OF_WEEK) - fDow + 7) % 7
        daysInMonth = c.getActualMaximum(Calendar.DAY_OF_MONTH)

        val locale = Locale.getDefault()
        val symbols = DateFormatSymbols(locale)
        for (d in 1..7) {
            val raw = symbols.shortWeekdays.getOrNull(d).orEmpty()
            // Keep localized abbreviations, but trim whitespace that some OEM
            // DateFormatSymbols implementations append to narrow labels.
            weekLabels[d - 1] = raw.trim().replaceFirstChar { it.titlecase(locale) }
        }
        cachedYear = year
        cachedMonth = month
        cachedFirstDow = fDow
        cachedLocale = locale.toLanguageTag()
    }

    /** Localized standalone month name; Russian explicitly uses nominative. */
    private fun monthHeading(month: Int): String {
        val locale = Locale.getDefault()
        if (locale.language.equals("ru", ignoreCase = true)) {
            return RU_MONTHS.getOrElse(month) { "" }
        }
        val symbols = DateFormatSymbols.getInstance(locale)
        val name = symbols.months.getOrNull(month).orEmpty()
        if (name.isNotBlank()) {
            return name.replaceFirstChar { it.titlecase(locale) }
        }

        // Corrected fallback: the old code asked a fresh Calendar for its month
        // name without first setting [month], so a rare DateFormatSymbols fallback
        // could display the current real month instead of the requested one.
        val c = Calendar.getInstance().apply { set(Calendar.MONTH, month) }
        return c.getDisplayName(Calendar.MONTH, Calendar.LONG, locale)
            ?.replaceFirstChar { it.titlecase(locale) }.orEmpty()
    }
}
