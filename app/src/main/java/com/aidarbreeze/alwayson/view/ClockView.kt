package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.os.SystemClock
import android.view.View
import com.aidarbreeze.alwayson.Prefs
import com.aidarbreeze.alwayson.weather.WeatherRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Big clock face that can render the time in several visual styles:
 *
 *  0 - NORMAL  : plain light text (the default look).
 *  1 - OUTLINE : the digit shapes drawn as hollow contours; the line thickness
 *                is chosen by the user. Rendered as "inflated glyph minus the
 *                glyph" (two fills of one path) so the contour has no miter
 *                spikes and no stroke self-intersection creases at corners.
 *  2 - DOTS    : "comic" dot-matrix digits — each digit is built from many
 *                small round dots with gaps between them.
 *  3 - FLIP    : an old "откидные часы" flip-clock — each digit is split into
 *                a top and bottom half with a seam, like a mechanical flipper.
 *  4 - SEG     : seven-segment LED alarm-clock digits.
 *  5 - NEON    : glowing "sign" digits with a soft halo bloom around the core.
 *  6 - BLOCKS  : minimal rounded "chips" — each glyph sits in a translucent pill.
 *  7 - SERIF   : elegant thin serif ("editorial") digits.
 *  8 - ITALIC  : heavy slanted italic digits (sporty).
 *  9 - MATRIX  : like DOTS but drawn with sharp square LED pixels.
 *  10 - CLASSIC: "Classic Digital" — strict, regular-weight sans-serif digits.
 *  11 - BOLD   : "Bold Digital" — big, dense bold digits.
 *  12 - MONO   : "Monospaced" — fixed-width digits, the clock never shifts.
 *  13 - ROUNDED: "Soft Rounded" — digits with rounded (pillow) corners.
 *  14 - PREMIUM: "Premium AMOLED" — the main focus of the screen: large
 *                light-weight digits in near-white (#F5F7FA), even tracking,
 *                the seconds as a small dimmed suffix (#8B93A1), no shadow
 *                and no glow.
 *  15 - STACKED: iPhone StandBy Digital — hours over minutes, extra-bold,
 *                no colon.
 *  16 - ANALOG : iPhone StandBy analog — squircle tick dial, 12/3/6/9
 *                numerals, white hands, thin orange second hand.
 *  17 - FLOAT  : iPhone StandBy Float — giant bubble digits in the accent
 *                color with a "pop" animation on every minute change.
 *  18 - SOLAR  : iPhone StandBy Solar — big time over the sun-path arc with
 *                real sunrise/sunset (from the weather cache when present).
 *  19 - WORLD  : iPhone StandBy World — dotted world map (with a "you are
 *                here" dot when the location is known), local time + UTC.
 *  20 - MIN_MONO: iPhone Minimal Mono — small calm letterspaced monospaced
 *                digits.
 *  21 - FLIQLO  : classic Fliqlo-like split-flap cards with individual digits.
 *  22 - NIXIE   : glowing orange Nixie-tube clock.
 *  23 - LCD     : retro LCD panel with ghost + active seven-segment strokes.
 *  24 - PONG    : a real self-playing Pong match (AI paddles, real
 *                  bounces, running score to 11) plus a small clock line.
 *  25 - WORD    : minimalist word-clock phrase (e.g. "TEN THIRTY TWO").
 *  26 - BINARY  : HH:MM:SS binary clock in six LED columns.
 *  27 - POLAR   : concentric progress-ring (Polar Clock) face.
 *  28 - DRIFT   : burn-in-friendly drifting minimalist clock.
 *  29 - GLITCH  : cyber/glitch digital face with deterministic RGB offsets.
 *  30 - MATRIX  : Matrix-rain screensaver with the time cut through the rain.
 *
 * The view is sized to fill its column horizontally and picks the biggest
 * legible digit size that still fits, then centres the time. Font-based
 * styles compute the size from a width-stable reference (digits -> "8"), so
 * the font size never jumps when the digits change.
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var timeText = "00:00"
    private var stableRefSource = ""
    private var stableRefCache = "88:88"
    private var hmSource = ""
    private var hmCache = "00:00"

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Reused across draws so onDraw never allocates (allocation in onDraw =
    // GC pauses = scroll/draw jank).
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x26FFFFFF
    }

    // Reused glyph path of the OUTLINE style: getTextPath -> drawPath (two
    // passes over ONE path) so the stroke joins are honoured exactly as
    // configured and the two passes share identical glyph boundaries.
    private val glyphPath = Path()
    // Black fill used to erase the glyph interior back to the pure-black
    // OLED background (OUTLINE style's second pass).
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    // Reused stroke/fill paints for the iPhone faces (analog ticks/hands,
    // solar arc, world dots) so their onDraw stays allocation-free too.
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Float face: minute-change "pop" animation state.
    private var floatLastMinute = ""
    private var floatAnimStart = 0L

    // Solar face: cached sunrise/sunset (ms) + the forecast city's zone,
    // refreshed at most every 10 min.
    private var sunCache: Pair<Long, Long>? = null
    private var sunCacheTz: String? = null
    private var sunCacheTs = 0L
    // Weather-freshness stamp at the moment the cache was filled: if a NEWER
    // forecast has arrived since (city change, first fix), the cache refreshes
    // on the next tick instead of waiting out the full 10-minute cap.
    private var sunCacheFetchedAt = 0L

    // Solar face: scratch rect for the sun-path arc (no per-frame alloc).
    private val arcRect = android.graphics.RectF()

    // Set by onMeasure: <1 when the width-fitted content is taller than the
    // measured box (tight landscape column / short screens) — onDraw then
    // scales the whole face down so nothing clips instead.
    private var contentScale = 1f

    // Neon style: the 16-layer bloom is rendered once into a bitmap and only
    // re-rendered when the text or the size changes (otherwise every frame
    // would overdraw the huge glyphs 16 times).
    private var neonBmp: Bitmap? = null
    private var neonKey = ""

    companion object {
        // Typeface.create() hits the font system every call; cache one per
        // family so measure/draw never pay for it. `by lazy` is synchronized.
        private val tfLight: Typeface by lazy {
            Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        private val tfSans: Typeface by lazy {
            Typeface.create("sans-serif", Typeface.NORMAL)
        }
        private val tfBold: Typeface by lazy {
            Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val tfBoldItalic: Typeface by lazy {
            Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
        }
        private val tfSerif: Typeface by lazy {
            Typeface.create("serif", Typeface.NORMAL)
        }
        private val tfBlack: Typeface by lazy {
            Typeface.create("sans-serif-black", Typeface.NORMAL)
        }

        // Public style ids: settings/UI code can use the same stable values.
        const val STYLE_FLIQLO = 21
        const val STYLE_NIXIE = 22
        const val STYLE_LCD = 23
        const val STYLE_PONG = 24
        const val STYLE_WORD = 25
        const val STYLE_BINARY = 26
        const val STYLE_POLAR = 27
        const val STYLE_DRIFT = 28
        const val STYLE_GLITCH = 29
        const val STYLE_MATRIX_RAIN = 30
        const val MAX_CLOCK_STYLE = STYLE_MATRIX_RAIN

        // Pong match (style 24): first to PONG_GAME_TO points wins the game,
        // then the scoreboard restarts. The ball pauses briefly per point.
        private const val PONG_GAME_TO = 11
        private const val PONG_SERVE_PAUSE_MS = 900L
        private const val PONG_PADDLE_H = 0.24f // paddle length, share of court height

        // Reused 5x7 glyph rows. Keeping these as IntArray constants removes
        // dozens of Array<Int> allocations per frame in DOTS/MATRIX faces.
        private val GLYPH_0 = intArrayOf(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110)
        private val GLYPH_1 = intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110)
        private val GLYPH_2 = intArrayOf(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111)
        private val GLYPH_3 = intArrayOf(0b11110, 0b00001, 0b00001, 0b01110, 0b00001, 0b00001, 0b11110)
        private val GLYPH_4 = intArrayOf(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010)
        private val GLYPH_5 = intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b00001, 0b00001, 0b11110)
        private val GLYPH_6 = intArrayOf(0b01110, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110)
        private val GLYPH_7 = intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000)
        private val GLYPH_8 = intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110)
        private val GLYPH_9 = intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00001, 0b01110)
        private val DIGIT_STRINGS = arrayOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")

        // Dotted world map for style 19 (60 x 30, '#' = land). Coarse on
        // purpose: at dot size it reads as continents, not pixels. The last
        // 6 rows (below ~-54°) are empty ocean and never drawn.
        private const val WORLD_ROWS = 24
        private val WORLD_MAP = arrayOf(
            "........##.............##................###................",
            ".......#####..........####.............###..................",
            "......##############.#######.......########################.",
            "....#################...####...#############################",
            "..#####################..##...##############################",
            ".......#################.....###############################",
            ".........##############......##############################.",
            ".........##############......###########################....",
            ".........#############.......##########################.....",
            "..........###########......###########################......",
            "...........#####...##......##################.#####.........",
            "............####............#################.###...........",
            ".............###.............######.###....##.###...........",
            "..............###.............#######..........##...........",
            ".................#####........######..........##.####.......",
            ".................######.......######...........##.###.......",
            ".................######.......######........................",
            "..................#####.......#####..#............####......",
            "..................####.........###...##..........######.....",
            "..................###...........###..............######.....",
            "..................###............##..............#####......",
            "..................##..............................###.....#.",
            "..................##.....................................##.",
            "..................#.........................................",
            "............................................................",
            "............................................................",
            "............................................................",
            "............................................................",
            "............................................................",
            "............................................................",
        )
    }

    fun setTime(text: String) {
        // The analog/solar faces show live seconds (hands, sun drift) even
        // when the text itself hides them, so they redraw on every tick —
        // the 1-second ticker calls setTime() regardless.
        val s = style() // single prefs read (the style was queried twice)
        val live = s == 16 || s == 18 || s == STYLE_PONG || s == STYLE_BINARY ||
            s == STYLE_POLAR || s == STYLE_DRIFT || s == STYLE_GLITCH ||
            s == STYLE_MATRIX_RAIN
        if (text == timeText && !live) return
        // "9:59" -> "10:00" changes the fitted size and needs a re-measure;
        // same-length ticks ("10:00" -> "10:01") only need a redraw. Calling
        // requestLayout() every second would re-layout the whole settings
        // ScrollView every second and visibly stutter scrolling.
        val relayout = text.length != timeText.length
        timeText = text
        if (relayout) requestLayout()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        neonBmp?.recycle()
        neonBmp = null
        neonKey = ""
    }

    /** Re-measure AND redraw: a style/size/thickness change can change the
     *  measured height (font metrics differ), and invalidate() alone would
     *  keep the stale layout. */
    fun refresh() {
        requestLayout()
        invalidate()
    }

    /** The user-chosen clock accent color (white by default). */
    private fun ink(): Int = Prefs.clockColorValue(context)

    /** Current wall-clock time as (hour24, minute, second). Used by the
     *  analog/solar faces so they never depend on the text format. */
    private fun wallTime(): Triple<Int, Int, Int> {
        val c = Calendar.getInstance()
        return Triple(
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
            c.get(Calendar.SECOND)
        )
    }

    /** Splits "H:mm[:ss]" into (hour, minute, secondsOrEmpty). Falls back to
     *  the wall clock when the text does not parse (never crash on draw). */
    private fun textParts(): Triple<String, String, String> {
        val p = timeText.split(":")
        if (p.size >= 2 && p[0].isNotEmpty() && p[1].isNotEmpty()) {
            val sec = if (p.size >= 3) p[2].filter { it.isDigit() } else ""
            return Triple(p[0], p[1], sec)
        }
        val (h, m) = wallTime()
        val use24 = Prefs.force24h(context) ||
            android.text.format.DateFormat.is24HourFormat(context)
        val hh = if (use24) h else if (h % 12 == 0) 12 else h % 12
        // Locale.US for stable Latin digits (see the UTC label note).
        return Triple(hh.toString(), "%02d".format(Locale.US, m), "")
    }

    private fun style(): Int = Prefs.clockStyle(context)

    private fun thicknessPx(): Float {
        val dp = Prefs.clockThickness(context).toFloat()
        return dp * resources.displayMetrics.density
    }

    /** The typeface the current style is drawn with. Used for MEASUREMENT too,
     *  so onMeasure() and onDraw() always agree on size/width per style. */
    private fun typefaceForStyle(s: Int = style()): Typeface = when (s) {
        0 -> tfLight
        7 -> tfSerif
        8 -> tfBoldItalic
        10, 13, 14 -> tfSans
        12, 20, STYLE_LCD, STYLE_NIXIE, STYLE_MATRIX_RAIN -> Typeface.MONOSPACE
        15, 17, STYLE_FLIQLO, STYLE_GLITCH -> tfBlack
        else -> tfBold
    }

    private fun stableReferenceText(): String {
        if (stableRefSource != timeText) {
            stableRefSource = timeText
            val chars = timeText.toCharArray()
            for (i in chars.indices) if (chars[i].isDigit()) chars[i] = '8'
            stableRefCache = String(chars)
        }
        return stableRefCache
    }

    /** HH:mm part cached for animated faces so they do not allocate every frame. */
    private fun hourMinuteText(): String {
        if (hmSource != timeText) {
            hmSource = timeText
            val first = timeText.indexOf(':')
            val second = if (first >= 0) timeText.indexOf(':', first + 1) else -1
            hmCache = if (second > 0) timeText.substring(0, second) else timeText
        }
        return hmCache
    }

    /** Biggest digit text size (px) for one line that fits [availW], capped.
     *  The width is measured on a width-stable reference (every digit ->
     *  "8", the widest glyph) in the style's OWN typeface, so the chosen size
     *  — and therefore the clock width — never jumps when the digits change
     *  (e.g. 11:11 -> 12:45) and matches what is actually drawn.
     *
     *  [padPx] subtracts a constant margin (e.g. a stroke that grows the
     *  glyph by a fixed amount), [inflateFrac] accounts for a stroke width
     *  proportional to the em (FILL_AND_STROKE styles), [letterSpacing] the
     *  tracking the style draws with — so fit == draw for every style. */
    private fun fitTextSize(
        availW: Float,
        capPx: Float,
        typeface: Typeface = typefaceForStyle(),
        padPx: Float = 0f,
        inflateFrac: Float = 0f,
        letterSpacing: Float = 0f
    ): Float {
        paint.textSize = 1000f
        paint.typeface = typeface
        paint.letterSpacing = letterSpacing
        val w1000 = paint.measureText(stableReferenceText()) + inflateFrac * 1000f
        paint.letterSpacing = 0f
        if (w1000 <= 0f) return capPx
        val fromWidth = ((availW - padPx) * 1000f / w1000) * 0.97f
        return fromWidth.coerceAtMost(capPx)
    }

    /** Splits "h:mm:ss" into the "h:mm" part and the seconds; times without
     *  a seconds field come back as (whole, ""). Requires TWO colons so
     *  "HH:mm" is never mistaken for "time + seconds". */
    private fun premiumParts(): Pair<String, String> {
        if (timeText.count { it == ':' } < 2) return timeText to ""
        val idx = timeText.lastIndexOf(':')
        if (timeText.length - idx - 1 == 2) {
            return timeText.substring(0, idx) to timeText.substring(idx + 1)
        }
        return timeText to ""
    }

    /** Biggest size for the PREMIUM style: the large "h:mm" part plus a
     *  small dimmed seconds suffix must fit [availW] together. Measured once
     *  at a reference size in the real (letter-spaced) typeface and scaled
     *  analytically, so fit == draw with exactly two measureText calls
     *  instead of a ~50-iteration shrink loop on every measure/draw. */
    private fun fitPremiumSize(availW: Float, capPx: Float): Float {
        val (main, sec) = premiumParts()
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfSans
        paint.letterSpacing = 0.025f
        paint.textSize = 100f
        val big100 = paint.measureText(main)
        var small100 = 0f
        if (sec.isNotEmpty()) {
            paint.textSize = 42f // 0.42 * 100: the suffix scales with the size
            paint.letterSpacing = 0.08f
            small100 = paint.measureText(sec)
        }
        paint.letterSpacing = 0f
        // Total width at size S: big100/100*S + 0.14*S + small100/100*S.
        val perUnit = big100 / 100f +
            (if (sec.isNotEmpty()) 0.14f else 0f) + small100 / 100f
        if (perUnit <= 0f) return capPx
        return (availW / perUnit).coerceIn(8f, capPx)
    }

    /** Exact grid height that fits [availW], avoiding the old 2 px shrink loop. */
    private fun gridHeightForWidth(availW: Float, capPx: Float, sevenSeg: Boolean): Float {
        var units = 0f
        for (ch in timeText) {
            units += if (ch.isDigit()) {
                0.62f * if (sevenSeg) 1.12f else 1.06f
            } else {
                0.62f * if (sevenSeg) 0.55f else 0.50f
            }
        }
        if (units <= 0f) return capPx
        return (availW / units).coerceIn(dp(16f), capPx)
    }

    /** Fixed draw height of the complex screensaver faces, derived from the
     *  available width (0 = the face sizes itself like a text clock). */
    private fun fixedFaceHeight(style: Int, availW: Float, capPx: Float): Float = when (style) {
        STYLE_FLIQLO -> min(availW * 0.40f, capPx * 1.18f).coerceAtLeast(dp(82f))
        STYLE_NIXIE -> min(availW * 0.44f, capPx * 1.28f).coerceAtLeast(dp(92f))
        STYLE_LCD -> min(availW * 0.34f, capPx).coerceAtLeast(dp(72f))
        STYLE_PONG -> min(availW * 0.56f, capPx * 1.65f).coerceAtLeast(dp(120f))
        STYLE_WORD -> min(availW * 0.48f, capPx * 1.30f).coerceAtLeast(dp(96f))
        STYLE_BINARY -> min(availW * 0.48f, capPx * 1.35f).coerceAtLeast(dp(104f))
        STYLE_POLAR -> min(availW * 0.76f, capPx * 2.05f).coerceAtLeast(dp(150f))
        STYLE_MATRIX_RAIN -> min(availW * 0.58f, capPx * 1.70f).coerceAtLeast(dp(128f))
        else -> 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Honour the measure modes: EXACTLY -> use the given size,
        // AT_MOST -> at most the given size (capped by a sane default),
        // UNSPECIFIED -> a sane default width.
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val desiredWidth = dp(320f).toInt()
        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(desiredWidth, widthSize)
            else -> desiredWidth
        }
        val availW = measuredWidth.toFloat()
        val capPx = cap()
        val pad = dp(4f)
        // The PREMIUM style sizes its large part differently (seconds are a
        // small suffix), so measure the height from that size, not the
        // single-size fit, or the rendered clock could be taller than the
        // measured box.
        val st = style()
        val textSize = when (st) {
            1 -> fitTextSize(availW - pad * 2f, capPx, padPx = 2f * thicknessPx())
            2, 9 -> gridHeightForWidth(availW - pad * 2f, capPx, sevenSeg = false)
            4 -> gridHeightForWidth(availW - pad * 2f, capPx, sevenSeg = true)
            13 -> fitTextSize(availW - pad * 2f, capPx, inflateFrac = 0.10f)
            14 -> fitPremiumSize(availW - pad * 2f, capPx)
            15 -> fitStackedSize(availW - pad * 2f, capPx)
            16 -> 0f // analog is measured geometrically (a square dial)
            17 -> fitTextSize(availW - pad * 2f, capPx, tfBlack, inflateFrac = 0.14f)
            18, 19 -> fitTextSize(availW - pad * 2f, capPx, tfBold)
            20 -> fitTextSize(
                availW - pad * 2f, capPx, Typeface.MONOSPACE, letterSpacing = 0.08f
            ) * 0.66f
            STYLE_DRIFT -> fitTextSize(availW * 0.72f, capPx, tfLight) * 0.72f
            STYLE_GLITCH -> fitTextSize(availW - pad * 2f, capPx, tfBlack, inflateFrac = 0.04f)
            else -> fitTextSize(availW - pad * 2f, capPx)
        }

        // Grid faces use an analytically fitted height; complex screensaver
        // faces have fixed aspect ratios so draw and measure cannot disagree.
        val fixed = fixedFaceHeight(st, availW, capPx)
        val height = if (fixed > 0f) fixed else when (st) {
            2, 4, 9 -> textSize
            15 -> stackedHeight(textSize)
            16 -> min(availW, capPx * 2.2f).coerceAtLeast(dp(120f))
            18 -> textHeight(textSize) + availW * 0.30f + textSize * 0.62f + dp(8f)
            // Map height is 24 rows at cell = availW / 60 -> 0.4 * availW.
            19 -> availW * 0.4f + textHeight(textSize) + textSize * 0.60f + dp(8f)
            1 -> textHeight(textSize) + dp(6f) + 2f * thicknessPx()
            STYLE_DRIFT -> textHeight(textSize) + dp(28f)
            STYLE_GLITCH -> textHeight(textSize) + dp(16f)
            else -> textHeight(textSize) + dp(6f)
        }
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val finalHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(height.toInt(), heightSize)
            else -> height.toInt()
        }
        contentScale = if (height > finalHeight && height > 0f) {
            (finalHeight / height).coerceIn(0.2f, 1f)
        } else {
            1f
        }
        setMeasuredDimension(measuredWidth, finalHeight)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun textHeight(size: Float): Float {
        paint.textSize = size
        val fm = paint.fontMetrics
        return fm.bottom - fm.top
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        val s = style()
        if (s != 5 && neonBmp != null) {
            // Left the neon style: drop its cached bitmap at once.
            neonBmp?.recycle()
            neonBmp = null
            neonKey = ""
        }
        // Height-driven faces (grids, chips, analog) size themselves from
        // the measured box and always fit; the width-fitted faces scale down
        // when the box came back shorter than the fitted content.
        val heightDriven = s == 2 || s == 4 || s == 6 || s == 9 || s == 16 ||
            s == STYLE_FLIQLO || s == STYLE_NIXIE || s == STYLE_LCD ||
            s == STYLE_PONG || s == STYLE_WORD || s == STYLE_BINARY ||
            s == STYLE_POLAR || s == STYLE_MATRIX_RAIN
        val sc = if (heightDriven) 1f else contentScale
        if (sc < 1f) {
            canvas.save()
            canvas.scale(sc, sc, w / 2f, h / 2f)
        }
        when (s) {
            1 -> drawOutline(canvas, w, h)
            2 -> drawDotGrid(canvas, w, h, square = false)
            3 -> drawFlip(canvas, w, h)
            4 -> drawSevenSegment(canvas, w, h)
            5 -> drawNeon(canvas, w, h)
            6 -> drawBlocks(canvas, w, h)
            7 -> drawSerif(canvas, w, h)
            8 -> drawItalic(canvas, w, h)
            9 -> drawDotGrid(canvas, w, h, square = true)
            10 -> drawPlain(canvas, w, h, tfSans)
            11 -> drawPlain(canvas, w, h, tfBold)
            12 -> drawPlain(canvas, w, h, Typeface.MONOSPACE)
            13 -> drawSoftRounded(canvas, w, h)
            14 -> drawPremium(canvas, w, h)
            15 -> drawStacked(canvas, w, h)
            16 -> drawAnalog(canvas, w, h)
            17 -> drawFloat(canvas, w, h)
            18 -> drawSolar(canvas, w, h)
            19 -> drawWorld(canvas, w, h)
            20 -> drawMinimalMono(canvas, w, h)
            STYLE_FLIQLO -> drawFliqlo(canvas, w, h)
            STYLE_NIXIE -> drawNixie(canvas, w, h)
            STYLE_LCD -> drawLcd(canvas, w, h)
            STYLE_PONG -> drawPong(canvas, w, h)
            STYLE_WORD -> drawWordClock(canvas, w, h)
            STYLE_BINARY -> drawBinary(canvas, w, h)
            STYLE_POLAR -> drawPolar(canvas, w, h)
            STYLE_DRIFT -> drawDrift(canvas, w, h)
            STYLE_GLITCH -> drawGlitch(canvas, w, h)
            STYLE_MATRIX_RAIN -> drawMatrixRain(canvas, w, h)
            else -> drawNormal(canvas, w, h)
        }
        if (sc < 1f) canvas.restore()
    }

    // ---------- style 0: plain text ----------

    private fun drawNormal(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w, cap())
        val tf = tfLight
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tf
        paint.color = ink()
        paint.style = Paint.Style.FILL
        drawCenteredText(canvas, timeText, w, h, paint)
    }

    // ---------- style 1: hollow outline digits ----------
    //
    // Rendered as "the glyph dilated outward minus the glyph itself": ONE
    // path from getTextPath is drawn twice — first FILL_AND_STROKE with a
    // round-join stroke of 2t (the glyph inflated by t in every direction),
    // then the same path filled with the pure-black background, which erases
    // the glyph interior and leaves a uniform t-thick contour OUTSIDE the
    // font shape. The inner edge of the contour is an exact fill boundary of
    // the glyph and the outer edge is a clean morphological dilation (round
    // joins) — so, unlike a plain STROKED text, the digit corners have no
    // miter spikes ("удлинения") and the tight junctions have no stroke
    // self-intersection lumps ("заломы"), at any thickness.

    private fun drawOutline(canvas: Canvas, w: Float, h: Float) {
        val t = thicknessPx().coerceAtLeast(dp(0.5f))
        val size = fitTextSize(w - dp(4f), cap(), padPx = 2f * t)
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tfBold
        paint.textAlign = Paint.Align.LEFT
        val fm = paint.fontMetrics
        val x = (w - paint.measureText(timeText)) / 2f
        val y = h / 2f - (fm.ascent + fm.descent) / 2f
        glyphPath.reset()
        paint.getTextPath(timeText, 0, timeText.length, x, y, glyphPath)

        // Pass 1: the glyph dilated outward by t. ROUND joins keep the
        // dilation smooth at convex corners (no spikes past the offset).
        paint.color = ink()
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = 2f * t
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(glyphPath, paint)

        // Pass 2: erase the glyph itself back to the black background — only
        // the contour ring remains.
        bgPaint.color = Color.BLACK
        canvas.drawPath(glyphPath, bgPaint)
    }

    // ---------- style 3: flip-clock ----------

    private fun drawFlip(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w, cap())
        val tf = tfBold
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tf
        paint.color = ink()
        paint.style = Paint.Style.FILL
        // draw the string centred on the view's middle x
        paint.textAlign = Paint.Align.CENTER

        // Vertical centre of the string; a mechanical flipper has a seam here
        // that splits each digit into a top and a bottom half.
        val fm = paint.fontMetrics
        val textH = fm.bottom - fm.top
        val midY = h / 2f
        val gap = textH * 0.06f
        val baseline = midY - textH * 0.5f - fm.top

        // top half
        canvas.save()
        canvas.clipRect(0f, 0f, w, midY - gap)
        canvas.drawText(timeText, w / 2f, baseline, paint)
        canvas.restore()

        // bottom half
        canvas.save()
        canvas.clipRect(0f, midY + gap, w, h)
        canvas.drawText(timeText, w / 2f, baseline, paint)
        canvas.restore()

        // faint separator line across the seam (flip hinge)
        dimPaint.reset()
        dimPaint.isAntiAlias = true
        dimPaint.color = Color.parseColor("#33FFFFFF")
        dimPaint.strokeWidth = dp(1f)
        canvas.drawLine(0f, midY, w, midY, dimPaint)
    }

    // ---------- styles 2 / 9: dot-matrix "comic" and square LED-matrix ----------

    private fun drawDotGrid(canvas: Canvas, w: Float, h: Float, square: Boolean) {
        // Aim for a digit height that fills the view height, but keep the whole
        // row within the width.
        val capPx = cap()
        var digitH = h
        // Build the sequence of glyph columns first.
        val cols = buildColumns()
        // total advance width for given digit height
        fun totalW(height: Float): Float {
            var tw = 0f
            for (c in cols) {
                tw += if (c.digit) height * 0.62f * 1.06f else height * 0.62f * 0.5f
            }
            return tw
        }
        // shrink digitH until it fits width
        while (digitH > 2f && totalW(digitH) > w) digitH -= 2f
        if (digitH > capPx) digitH = capPx

        paint.reset()
        paint.isAntiAlias = true
        paint.color = ink()
        paint.style = Paint.Style.FILL

        // draw centred, generous vertical space so dots sit within the view
        val startX = (w - totalW(digitH)) / 2f
        var x = startX
        val yCentre = h / 2f
        for (c in cols) {
            if (c.digit) {
                drawGridGlyph(canvas, x, yCentre, digitH, c.char, square)
                x += digitH * 0.62f * 1.06f
            } else {
                // colon -> two dots at vertical middle
                val r = digitH * 0.09f
                val cx = x + digitH * 0.62f * 0.25f
                drawGridPixel(
                    canvas, cx, yCentre - digitH * 0.22f, r, square
                )
                drawGridPixel(
                    canvas, cx, yCentre + digitH * 0.22f, r, square
                )
                x += digitH * 0.62f * 0.5f
            }
        }
    }

    private class Col(val char: Char, val digit: Boolean)

    private fun buildColumns(): List<Col> {
        val out = ArrayList<Col>()
        for (ch in timeText) {
            out.add(Col(ch, ch.isDigit()))
        }
        return out
    }

    /** Draw one dot — a circle, or a square when [square] is true. */
    private fun drawGridPixel(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        square: Boolean
    ) {
        if (square) {
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, paint)
        } else {
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    /** Draws one digit glyph (5 wide x 7 tall) centred at yCentre. */
    private fun drawGridGlyph(
        canvas: Canvas,
        left: Float,
        yCentre: Float,
        digitH: Float,
        ch: Char,
        square: Boolean
    ) {
        val pat = glyph(ch) ?: return
        val dw = digitH * 0.62f
        val rows = 7
        val cols = 5
        val rowSpacing = digitH / (rows + 1f)
        val colSpacing = dw / (cols + 1f)
        // Square pixels are allowed to be a touch bigger so the matrix reads as
        // continuous bars instead of isolated points.
        val r = (rowSpacing.coerceAtMost(colSpacing)) *
            if (square) 0.42f else 0.34f
        val top = yCentre - digitH * 0.5f
        for (row in 0 until rows) {
            val bits = pat[row]
            for (col in 0 until cols) {
                // Bit 0 of the literal is its right-most character, but column 0
                // is drawn left-most, so read the bits from the other side
                // (otherwise the digit comes out mirrored).
                val bit = cols - 1 - col
                if (((bits shr bit) and 1) != 0) {
                    val cx = left + (col + 1) * colSpacing
                    val cy = top + (row + 1) * rowSpacing
                    drawGridPixel(canvas, cx, cy, r, square)
                }
            }
        }
    }

    // ---------- style 4: seven-segment LED alarm-clock ----------

    private fun drawSevenSegment(canvas: Canvas, w: Float, h: Float) {
        val capPx = cap()
        var digitH = h
        fun advance(dh: Float, ch: Char): Float =
            if (ch.isDigit()) dh * 0.62f * 1.12f else dh * 0.62f * 0.55f
        fun totalW(dh: Float): Float {
            var t = 0f
            for (ch in timeText) t += advance(dh, ch)
            return t
        }
        while (digitH > 2f && totalW(digitH) > w) digitH -= 2f
        if (digitH > capPx) digitH = capPx

        val seg = segPaint
        seg.style = Paint.Style.STROKE
        seg.color = ink()

        val xStart = (w - totalW(digitH)) / 2f
        var x = xStart
        val yTop = h / 2f - digitH * 0.5f
        val dwc = digitH * 0.62f
        for (ch in timeText) {
            if (ch.isDigit()) {
                drawSegGlyph(canvas, x, yTop, digitH, ch, seg)
                x += dwc * 1.12f
            } else {
                // colon -> two round dots at the vertical middle
                val r = digitH * 0.075f
                val cx = x + dwc * 0.25f
                val cy = h / 2f
                seg.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy - digitH * 0.2f, r, seg)
                canvas.drawCircle(cx, cy + digitH * 0.2f, r, seg)
                seg.style = Paint.Style.STROKE
                x += dwc * 0.55f
            }
        }
    }

    private fun segPattern(ch: Char): Int = when (ch) {
        '0' -> 0b0111111
        '1' -> 0b0000110
        '2' -> 0b1011011
        '3' -> 0b1001111
        '4' -> 0b1100110
        '5' -> 0b1101101
        '6' -> 0b1111101
        '7' -> 0b0000111
        '8' -> 0b1111111
        '9' -> 0b1101111
        else -> 0
    }

    /** Draws one seven-segment digit: cell [left..left+dw] x [top..top+dh]. */
    private fun drawSegGlyph(
        canvas: Canvas,
        left: Float,
        top: Float,
        dh: Float,
        ch: Char,
        seg: Paint
    ) {
        val p = segPattern(ch)
        val dw = dh * 0.62f
        seg.strokeWidth = dh * 0.16f
        // Segment centre lines. Bit 0=a(top),1=b,2=c,3=d(bottom),4=e,5=f,6=g(mid).
        val xL = left + dw * 0.12f
        val xR = left + dw * 0.88f
        val y0 = top + dh * 0.06f
        val yM = top + dh * 0.5f
        val y1 = top + dh * 0.94f
        fun bar(x1: Float, yy1: Float, x2: Float, yy2: Float) {
            canvas.drawLine(x1, yy1, x2, yy2, seg)
        }
        if ((p and 1) != 0) bar(xL, y0, xR, y0)   // a top
        if ((p and 64) != 0) bar(xL, yM, xR, yM)  // g middle
        if ((p and 8) != 0) bar(xL, y1, xR, y1)   // d bottom
        if ((p and 32) != 0) bar(xL, y0, xL, yM)  // f top-left
        if ((p and 2) != 0) bar(xR, y0, xR, yM)   // b top-right
        if ((p and 16) != 0) bar(xL, yM, xL, y1)  // e bottom-left
        if ((p and 4) != 0) bar(xR, yM, xR, y1)   // c bottom-right
    }

    // ---------- style 5: neon glow ----------

    private fun drawNeon(canvas: Canvas, w: Float, h: Float) {
        val core = fitTextSize(w, cap())
        // The key must include the accent color: a color change has to
        // re-render the cached bloom instead of glowing in the old ink
        // until the text or size happens to change.
        val key = "$timeText|${w.toInt()}|${h.toInt()}|${core.toInt()}|${ink()}"
        var bmp = if (key == neonKey) neonBmp else null
        if (bmp == null) {
            neonBmp?.recycle()
            neonBmp = null
            bmp = try {
                Bitmap.createBitmap(
                    w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
            } catch (_: Exception) {
                null
            }
            if (bmp != null) {
                renderNeonInto(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), core)
                neonBmp = bmp
                neonKey = key
            }
        }
        if (bmp != null) {
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } else {
            // Bitmap alloc failed (low memory): fall back to a direct draw.
            renderNeonInto(canvas, w, h, core)
        }
    }

    /** The neon bloom itself: translucent copies shrinking from a wide faint
     *  halo down to a crisp bright core. Only called when the cached bitmap
     *  is stale, never every frame. */
    private fun renderNeonInto(canvas: Canvas, w: Float, h: Float, core: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.typeface = tfBold
        paint.textAlign = Paint.Align.CENTER
        val layers = 16
        for (i in 0 until layers) {
            val f = i / (layers - 1f) // 0 = outermost, 1 = core
            paint.textSize = core * (1.18f - 0.18f * f)
            val alpha = (8 + (255 - 8) * f * f).toInt().coerceIn(0, 255)
            val ic = ink()
            paint.color = Color.argb(
                alpha, Color.red(ic), Color.green(ic), Color.blue(ic)
            )
            val fm = paint.fontMetrics
            val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(timeText, w / 2f, baseline, paint)
        }
    }

    // ---------- style 6: minimal rounded "chips" ----------

    private fun drawBlocks(canvas: Canvas, w: Float, h: Float) {
        val capPx = cap()
        val digitPad = dp(11f)
        val tf = tfBold
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.typeface = tf
        fun slotW(s: Float, ch: Char): Float {
            paint.textSize = s
            val cw = paint.measureText(ch.toString())
            return cw + if (ch == ':') dp(2f) * 2f else digitPad * 2f
        }
        fun total(s: Float): Float {
            var t = 0f
            for (ch in timeText) t += slotW(s, ch)
            return t
        }
        var size = (h * 0.72f).coerceAtMost(capPx)
        fun pillHeight(s: Float): Float {
            paint.textSize = s
            return paint.fontMetrics.bottom - paint.fontMetrics.top + dp(12f)
        }
        // Shrink until BOTH the row fits the width and the pills fit the
        // height (a short box would otherwise clip the chip tops/bottoms).
        while (size > 6f && (total(size) > w || pillHeight(size) > h)) size -= 2f
        paint.textSize = size
        val fm = paint.fontMetrics
        val textH = fm.bottom - fm.top
        val chipH = textH + dp(12f)
        val yTop = h / 2f - chipH / 2f
        val baseline = yTop + chipH / 2f - (fm.ascent + fm.descent) / 2f
        val radius = dp(10f)
        val bg = chipBg

        var x = (w - total(size)) / 2f
        paint.textAlign = Paint.Align.CENTER
        paint.color = ink()
        for (ch in timeText) {
            val slot = slotW(size, ch)
            val cx = x + slot / 2f
            if (ch == ':') {
                // colon as two dots between the pills, no background
                val r = dp(2.6f)
                canvas.drawCircle(cx, h / 2f - dp(7f), r, paint)
                canvas.drawCircle(cx, h / 2f + dp(7f), r, paint)
            } else {
                canvas.drawRoundRect(
                    x, yTop, x + slot, yTop + chipH, radius, radius, bg
                )
                canvas.drawText(ch.toString(), cx, baseline, paint)
            }
            x += slot
        }
    }

    // ---------- style 7: serif "editorial" ----------

    private fun drawSerif(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w, cap())
        val tf = tfSerif
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tf
        paint.color = ink()
        paint.style = Paint.Style.FILL
        drawCenteredText(canvas, timeText, w, h, paint)
    }

    // ---------- style 8: heavy slanted italic ----------

    private fun drawItalic(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w, cap())
        val tf = tfBoldItalic
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tf
        paint.color = ink()
        paint.style = Paint.Style.FILL
        drawCenteredText(canvas, timeText, w, h, paint)
    }

    // ---------- styles 10/11/12: plain digits in a chosen typeface ----------
    // 10 Classic Digital (regular sans), 11 Bold Digital (bold sans),
    // 12 Monospaced (fixed-width, the clock never shifts as digits change).

    private fun drawPlain(canvas: Canvas, w: Float, h: Float, tf: Typeface) {
        // Measured directly in this style's typeface, so fit == draw.
        val size = fitTextSize(w - dp(4f), cap(), tf)
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tf
        paint.color = ink()
        paint.style = Paint.Style.FILL
        drawCenteredText(canvas, timeText, w, h, paint)
    }

    // ---------- style 13: soft rounded ("pillow") digits ----------

    private fun drawSoftRounded(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w - dp(2f), cap(), inflateFrac = 0.10f)
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tfSans
        paint.color = ink()
        // FILL_AND_STROKE with round joins/caps rounds the corners of the
        // glyphs into a soft, rounded look.
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = (size * 0.10f).coerceIn(2f, dp(10f))
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        drawCenteredText(canvas, timeText, w, h, paint)
    }

    // ---------- style 14: premium AMOLED (the main focus of the screen) ----------
    // Large light-weight near-white digits with even tracking; the seconds,
    // when shown, are a small dimmed suffix on the same baseline. No shadow,
    // no glow — flat and calm on OLED.

    private fun drawPremium(canvas: Canvas, w: Float, h: Float) {
        val size = fitPremiumSize(w - dp(4f), cap())
        val (main, sec) = premiumParts()
        val gap = if (sec.isNotEmpty()) size * 0.14f else 0f

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfSans
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.color = ink()

        paint.textSize = size
        paint.letterSpacing = 0.025f
        val bigW = paint.measureText(main)
        var smallW = 0f
        if (sec.isNotEmpty()) {
            paint.textSize = size * 0.42f
            paint.letterSpacing = 0.08f
            smallW = paint.measureText(sec)
        }
        paint.letterSpacing = 0f

        val total = bigW + gap + smallW
        val x = (w - total) / 2f
        paint.textSize = size
        paint.letterSpacing = 0.025f
        paint.color = ink()
        val fm = paint.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(main, x, baseline, paint)

        if (sec.isNotEmpty()) {
            paint.textSize = size * 0.42f
            paint.letterSpacing = 0.08f
            paint.color = 0xFF8B93A1.toInt()
            canvas.drawText(sec, x + bigW + gap, baseline, paint)
        }
        paint.letterSpacing = 0f
    }


    // ---------- style 15: stacked iPhone digital (hours over minutes) ----------

    /** Biggest line size for the STACKED face: each line holds 2 digits. */
    private fun fitStackedSize(availW: Float, capPx: Float): Float {
        paint.textSize = 1000f
        paint.typeface = tfBlack
        val w1000 = paint.measureText("88")
        if (w1000 <= 0f) return capPx
        return ((availW * 1000f / w1000) * 0.97f).coerceAtMost(capPx)
    }

    /** Measured height of the STACKED face for a fitted line size. */
    private fun stackedHeight(size: Float): Float {
        paint.textSize = size
        paint.typeface = tfBlack
        val fm = paint.fontMetrics
        val line = fm.bottom - fm.top
        val secs = if (textParts().third.isNotEmpty()) size * 0.34f else 0f
        return line * 2f + size * 0.04f + secs + dp(4f)
    }

    private fun drawStacked(canvas: Canvas, w: Float, h: Float) {
        val size = fitStackedSize(w, cap())
        val (hh, mm, ss) = textParts()
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tfBlack
        paint.color = ink()
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.letterSpacing = -0.02f
        val fm = paint.fontMetrics
        val line = fm.bottom - fm.top
        val gap = size * 0.04f
        val secsH = if (ss.isNotEmpty()) size * 0.34f else 0f
        var baseline = (h - (line * 2f + gap + secsH)) / 2f - fm.top
        canvas.drawText(hh, w / 2f, baseline, paint)
        baseline += line + gap
        canvas.drawText(mm, w / 2f, baseline, paint)
        if (ss.isNotEmpty()) {
            paint.textSize = size * 0.30f
            paint.letterSpacing = 0.10f
            paint.color = 0xFF8B93A1.toInt()
            val secFm = paint.fontMetrics
            baseline += fm.descent + size * 0.08f - secFm.ascent
            canvas.drawText(ss, w / 2f, baseline, paint)
        }
        paint.letterSpacing = 0f
    }

    // ---------- style 16: analog iPhone dial ----------

    /** [color] with its alpha scaled by [f] (for dim ticks/labels). */
    private fun dimmed(color: Int, f: Float): Int {
        val a = (Color.alpha(color) * f).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    /** Draws [text] centred horizontally at [x], vertically around [y]. */
    private fun drawCenteredAt(canvas: Canvas, text: String, x: Float, y: Float, p: Paint) {
        p.textAlign = Paint.Align.CENTER
        val fm = p.fontMetrics
        canvas.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, p)
    }

    private fun drawHand(
        canvas: Canvas, cx: Float, cy: Float, angle: Float,
        len: Float, width: Float, color: Int
    ) {
        val p = strokePaint
        p.strokeWidth = width.coerceAtLeast(1f)
        p.color = color
        val tail = len * 0.12f
        canvas.drawLine(
            cx - cos(angle) * tail, cy - sin(angle) * tail,
            cx + cos(angle) * len, cy + sin(angle) * len, p
        )
    }

    private fun drawAnalog(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val rout = min(w, h) / 2f * 0.96f
        if (rout <= 2f) return
        val (h24, m, s) = wallTime()
        val main = ink()
        // Minute ticks on a slightly squared circle (the StandBy dial reads
        // as a squircle, not a perfect circle). k normalises the extent so
        // the diagonal ticks exactly touch [rout].
        val sq = 0.28f
        val kmax = (1f - sq) + sq / 0.70710678f
        val tick = strokePaint
        for (i in 0 until 60) {
            val a = (i / 60f * 2f * PI - PI / 2f).toFloat()
            val dx = cos(a)
            val dy = sin(a)
            val hour = i % 5 == 0
            val k = ((1f - sq) + sq / maxOf(abs(dx), abs(dy))) / kmax
            val rO = rout * k
            val len = if (hour) rout * 0.10f else rout * 0.05f
            tick.strokeWidth = (if (hour) rout * 0.028f else rout * 0.014f)
                .coerceAtLeast(1f)
            tick.color = if (hour) main else dimmed(main, 0.45f)
            canvas.drawLine(
                cx + dx * (rO - len), cy + dy * (rO - len),
                cx + dx * rO, cy + dy * rO, tick
            )
        }
        // Quarter numerals only, like the iPhone widget. Pulled slightly
        // inside (0.66) so they never collide with the inner tips of the
        // hour ticks along the axes.
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = rout * 0.22f
        paint.typeface = tfBold
        paint.color = main
        paint.style = Paint.Style.FILL
        val nr = rout * 0.66f
        drawCenteredAt(canvas, "12", cx, cy - nr, paint)
        drawCenteredAt(canvas, "3", cx + nr, cy, paint)
        drawCenteredAt(canvas, "6", cx, cy + nr, paint)
        drawCenteredAt(canvas, "9", cx - nr, cy, paint)
        // Hands: thick white hour/minute, thin iOS-orange second.
        val minA = ((m + s / 60f) / 60f * 2f * PI - PI / 2f).toFloat()
        val hrA = (((h24 % 12) + m / 60f) / 12f * 2f * PI - PI / 2f).toFloat()
        val secA = (s / 60f * 2f * PI - PI / 2f).toFloat()
        drawHand(canvas, cx, cy, minA, rout * 0.62f, rout * 0.055f, main)
        drawHand(canvas, cx, cy, hrA, rout * 0.44f, rout * 0.075f, main)
        drawHand(canvas, cx, cy, secA, rout * 0.70f, rout * 0.016f, 0xFFFF9F0A.toInt())
        fillPaint.color = main
        canvas.drawCircle(cx, cy, rout * 0.045f, fillPaint)
        fillPaint.color = 0xFFFF9F0A.toInt()
        canvas.drawCircle(cx, cy, rout * 0.020f, fillPaint)
    }

    // ---------- style 17: float iPhone bubble digits ----------

    private fun drawFloat(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w - dp(4f), cap(), tfBlack, inflateFrac = 0.14f)
        // "Pop" scale on every minute change (350 ms ease-out).
        val minute = textParts().second
        val now = SystemClock.uptimeMillis()
        if (minute != floatLastMinute) {
            floatLastMinute = minute
            floatAnimStart = now
        }
        var scale = 1f
        val dt = now - floatAnimStart
        if (floatAnimStart != 0L && dt < 350L) {
            val k = dt / 350f
            scale = 1f + 0.14f * (1f - k) * (1f - k)
            postInvalidateDelayed(16)
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tfBlack
        paint.color = ink()
        // A heavy round-join stroke over the fill inflates the glyphs into
        // the soft bubble look.
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = (size * 0.14f).coerceIn(2f, dp(22f))
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.letterSpacing = -0.01f
        canvas.save()
        canvas.scale(scale, scale, w / 2f, h / 2f)
        drawCenteredText(canvas, timeText, w, h, paint)
        canvas.restore()
        paint.letterSpacing = 0f
    }

    // ---------- style 18: solar iPhone (sun-path arc) ----------

    /** Sunrise/sunset (ms) from the last-known forecast. Refreshed at most
     *  every 10 minutes, or immediately when a NEWER forecast has landed
     *  since the cache was filled (city change, first fix). The forecast
     *  point's zone is remembered too, so the labels format the instants in
     *  the CITY's time, not the device's. */
    private fun sunTimes(): Pair<Long, Long>? {
        val now = System.currentTimeMillis()
        // Refresh when the 10-minute age cap is hit, but ALSO whenever a
        // newer forecast has landed since the cache was filled (city change,
        // first fix): otherwise a city switch would keep drawing the old
        // city's sunrise/sunset for up to the full 10 minutes.
        if (now - sunCacheTs < 10L * 60L * 1000L &&
            Prefs.lastWeatherUpdateMs(context) <= sunCacheFetchedAt
        ) return sunCache
        sunCacheTs = now
        sunCacheFetchedAt = Prefs.lastWeatherUpdateMs(context)
        val full = try {
            WeatherRepository.sunTimesFull(context)
        } catch (_: Exception) {
            null
        }
        sunCache = full?.let { it.riseMs to it.setMs }
        sunCacheTz = full?.timezoneId
        return sunCache
    }

    /** 06:00/18:00 of today, when no cached forecast has sun times yet. */
    private fun fallbackSun(): Pair<Long, Long> {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 6)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val rise = c.timeInMillis
        c.set(Calendar.HOUR_OF_DAY, 18)
        return rise to c.timeInMillis
    }

    // Cached solar-label formatter: drawSolar formats BOTH labels on every
    // 1-second tick while style 18 is up — a fresh SimpleDateFormat pair per
    // tick was constant GC churn on the always-on screen. Keyed by
    // pattern+locale+zone like the fmt() caches elsewhere.
    private val solarFmtCache = HashMap<String, SimpleDateFormat>()

    private fun solarLabel(ms: Long): String {
        val use24 = Prefs.force24h(context) ||
            android.text.format.DateFormat.is24HourFormat(context)
        // The sunrise/sunset instants belong to the forecast city: format
        // them in ITS zone (the device zone shifted every label for a city
        // in another timezone).
        val tz = sunCacheTz?.takeIf { it.isNotBlank() }
            ?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
        val pattern = if (use24) "HH:mm" else "h:mm"
        val key = pattern + '|' + Locale.getDefault().toLanguageTag() + '|' + tz.id
        val f = solarFmtCache.getOrPut(key) {
            SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = tz }
        }
        return f.format(Date(ms))
    }

    private fun drawSolar(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w - dp(4f), cap(), tfBold)
        val main = ink()
        // Big time on top.
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tfBold
        paint.color = main
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        val fm = paint.fontMetrics
        val timeBase = dp(2f) - fm.top
        canvas.drawText(timeText, w / 2f, timeBase, paint)
        // Sun-path arc: sunrise (left) to sunset (right) over a horizon.
        val nowMs = System.currentTimeMillis()
        val (riseMs, setMs) = sunTimes() ?: fallbackSun()
        val arcTop = timeBase + fm.bottom + size * 0.10f
        val arcH = w * 0.30f
        val arcBase = arcTop + arcH
        val arcL = w * 0.08f
        val arcR = w - w * 0.08f
        val arcW = arcR - arcL
        val sp = strokePaint
        sp.color = dimmed(main, 0.35f)
        sp.strokeWidth = dp(1.5f).coerceAtLeast(1f)
        canvas.drawLine(arcL - dp(8f), arcBase, arcR + dp(8f), arcBase, sp)
        arcRect.set(arcL, arcBase - 2f * arcH, arcR, arcBase)
        sp.color = dimmed(main, 0.55f)
        sp.strokeWidth = dp(2.5f).coerceAtLeast(1f)
        canvas.drawArc(arcRect, 180f, 180f, false, sp)
        // The sun dot travels the arc by day and parks dimmed at night.
        val span = (setMs - riseMs).coerceAtLeast(1L).toFloat()
        val p = ((nowMs - riseMs).toFloat() / span).coerceIn(0f, 1f)
        val day = nowMs in riseMs..setMs
        val ang = (PI * (1.0 - p)).toFloat()
        val sunX = (arcL + arcR) / 2f + cos(ang) * arcW / 2f
        val sunY = arcBase - sin(ang) * arcH
        fillPaint.color = if (day) 0xFFFBBF24.toInt() else dimmed(main, 0.4f)
        canvas.drawCircle(sunX, sunY, dp(if (day) 7f else 5f), fillPaint)
        // Sunrise/sunset labels under the horizon ends.
        paint.textSize = size * 0.30f
        paint.letterSpacing = 0.06f
        paint.color = dimmed(main, 0.65f)
        val labFm = paint.fontMetrics
        val labBase = arcBase + size * 0.08f - labFm.ascent
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("\u2191 " + solarLabel(riseMs), arcL, labBase, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("\u2193 " + solarLabel(setMs), arcR, labBase, paint)
        paint.letterSpacing = 0f
    }

    // ---------- style 19: world iPhone (dotted map + UTC) ----------

    private fun drawWorld(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w - dp(4f), cap(), tfBold)
        val main = ink()
        // Dotted continents. The map data spans the full globe over 30 rows,
        // but only the top 24 rows carry land (below -54° there is nothing
        // but empty ocean here) — draw only those, so the block has no dead
        // gap under the continents.
        val cols = WORLD_MAP[0].length
        val mapRows = WORLD_ROWS
        val cell = w / cols
        val mapHeight = cell * mapRows
        val dotR = (cell * 0.30f).coerceAtLeast(0.8f)
        fillPaint.color = dimmed(main, 0.42f)
        for (r in 0 until mapRows) {
            val row = WORLD_MAP[r]
            val cy = cell * (r + 0.5f)
            for (c in 0 until cols) {
                if (c < row.length && row[c] == '#') {
                    canvas.drawCircle(cell * (c + 0.5f), cy, dotR, fillPaint)
                }
            }
        }
        // "You are here" dot from the weather location, when known. The geo
        // mapping still spans the full 30-row globe, so latitudes land in the
        // same cells the land dots occupy.
        try {
            val loc = Prefs.weatherLocation(context)
            if (loc != null) {
                val px = ((loc.second + 180.0) / 360.0 * cols)
                    .toFloat().coerceIn(0f, (cols - 1).toFloat())
                val py = ((90.0 - loc.first) / 180.0 * WORLD_MAP.size)
                    .toFloat().coerceIn(0f, (mapRows - 1).toFloat())
                fillPaint.color = 0xFFFB923C.toInt()
                canvas.drawCircle(
                    cell * (px + 0.5f), cell * (py + 0.5f), dotR * 2.2f, fillPaint
                )
            }
        } catch (_: Exception) {
            // location is best-effort
        }
        // Local time + a small UTC clock under the map.
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tfBold
        paint.color = main
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        val fm = paint.fontMetrics
        val tBase = mapHeight + size * 0.06f - fm.top
        canvas.drawText(timeText, w / 2f, tBase, paint)
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        // Locale.US: Latin digits always, like every other numeric label —
        // the default locale would render Arabic-Indic digits on ar/fa
        // locales and break the monospace look.
        val utcStr = "UTC %02d:%02d".format(
            Locale.US, utc.get(Calendar.HOUR_OF_DAY), utc.get(Calendar.MINUTE)
        )
        paint.textSize = size * 0.30f
        paint.letterSpacing = 0.10f
        paint.color = dimmed(main, 0.65f)
        val ufm = paint.fontMetrics
        val uBase = tBase + fm.bottom + size * 0.10f - ufm.ascent
        canvas.drawText(utcStr, w / 2f, uBase, paint)
        paint.letterSpacing = 0f
    }

    // ---------- style 20: minimal mono (small, calm, letterspaced) ----------

    private fun drawMinimalMono(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(
            w - dp(4f), cap(), Typeface.MONOSPACE, letterSpacing = 0.08f
        ) * 0.66f
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = Typeface.MONOSPACE
        paint.color = dimmed(ink(), 0.88f)
        paint.style = Paint.Style.FILL
        paint.letterSpacing = 0.08f
        drawCenteredText(canvas, timeText, w, h, paint)
        paint.letterSpacing = 0f
    }

    // ---------- style 21: Fliqlo-like split-flap cards ----------

    private fun drawFliqlo(canvas: Canvas, w: Float, h: Float) {
        val digits = timeText.filter { it.isDigit() }.padStart(4, '0').take(4)
        val gap = h * 0.035f
        val colonW = h * 0.12f
        val cardW = ((w - colonW - gap * 6f) / 4f).coerceAtLeast(dp(18f))
        val cardH = min(h * 0.88f, cardW * 1.55f)
        val totalW = cardW * 4f + colonW + gap * 6f
        var x = (w - totalW) / 2f + gap
        val top = (h - cardH) / 2f
        val radius = min(cardW, cardH) * 0.10f

        fillPaint.color = 0xFF161616.toInt()
        strokePaint.color = 0xFF050505.toInt()
        strokePaint.strokeWidth = dp(1.2f)
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfLight
        paint.textAlign = Paint.Align.CENTER
        paint.color = ink()
        paint.textSize = cardH * 0.72f
        val fm = paint.fontMetrics
        val baseline = top + cardH / 2f - (fm.ascent + fm.descent) / 2f

        for (i in 0 until 4) {
            canvas.drawRoundRect(x, top, x + cardW, top + cardH, radius, radius, fillPaint)
            canvas.drawLine(x + dp(2f), top + cardH / 2f, x + cardW - dp(2f), top + cardH / 2f, strokePaint)
            canvas.drawText(digits[i].toString(), x + cardW / 2f, baseline, paint)
            x += cardW + gap
            if (i == 1) {
                fillPaint.color = dimmed(ink(), 0.88f)
                val cx = x + colonW / 2f
                val r = maxOf(dp(2f), cardH * 0.025f)
                canvas.drawCircle(cx, h * 0.42f, r, fillPaint)
                canvas.drawCircle(cx, h * 0.58f, r, fillPaint)
                fillPaint.color = 0xFF161616.toInt()
                x += colonW + gap
            }
        }
    }

    // ---------- style 22: Nixie tubes ----------

    private fun drawNixie(canvas: Canvas, w: Float, h: Float) {
        val digits = timeText.filter { it.isDigit() }.padStart(4, '0').take(4)
        val orange = 0xFFFF7A18.toInt()
        val dimOrange = 0x55FF6A00
        val gap = h * 0.035f
        val colonW = h * 0.12f
        val tubeW = ((w - colonW - gap * 6f) / 4f).coerceAtLeast(dp(18f))
        val tubeH = min(h * 0.90f, tubeW * 1.65f)
        val totalW = tubeW * 4f + colonW + gap * 6f
        var x = (w - totalW) / 2f + gap
        val top = (h - tubeH) / 2f
        val radius = tubeW * 0.46f

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = Typeface.MONOSPACE
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.textSize = tubeH * 0.67f
        val fm = paint.fontMetrics
        val baseline = top + tubeH / 2f - (fm.ascent + fm.descent) / 2f

        for (i in 0 until 4) {
            fillPaint.color = 0x161A0A00
            canvas.drawRoundRect(x, top, x + tubeW, top + tubeH, radius, radius, fillPaint)
            strokePaint.color = 0x445A2B0A
            strokePaint.strokeWidth = dp(1f)
            canvas.drawRoundRect(x, top, x + tubeW, top + tubeH, radius, radius, strokePaint)
            // Wide translucent filament followed by a crisp core.
            paint.color = dimOrange
            paint.strokeWidth = maxOf(dp(3f), tubeW * 0.065f)
            canvas.drawText(digits[i].toString(), x + tubeW / 2f, baseline, paint)
            paint.color = orange
            paint.strokeWidth = maxOf(dp(1.1f), tubeW * 0.018f)
            canvas.drawText(digits[i].toString(), x + tubeW / 2f, baseline, paint)
            x += tubeW + gap
            if (i == 1) {
                fillPaint.color = orange
                val cx = x + colonW / 2f
                val r = maxOf(dp(2f), tubeH * 0.022f)
                canvas.drawCircle(cx, h * 0.42f, r, fillPaint)
                canvas.drawCircle(cx, h * 0.58f, r, fillPaint)
                x += colonW + gap
            }
        }
        paint.style = Paint.Style.FILL
    }

    // ---------- style 23: retro LCD ----------

    private fun drawLcd(canvas: Canvas, w: Float, h: Float) {
        val panelPad = min(w, h) * 0.05f
        val radius = min(w, h) * 0.08f
        fillPaint.color = 0xFFB7C8A5.toInt()
        canvas.drawRoundRect(panelPad, panelPad, w - panelPad, h - panelPad, radius, radius, fillPaint)

        var units = 0f
        for (ch in timeText) units += if (ch.isDigit()) 0.62f * 1.12f else 0.62f * 0.55f
        if (units <= 0f) return
        val innerW = w - panelPad * 2.8f
        val innerH = h - panelPad * 2.2f
        val digitH = min(innerH, innerW / units)
        val dw = digitH * 0.62f
        var x = (w - units * digitH) / 2f
        val top = h / 2f - digitH / 2f
        val active = 0xFF233126.toInt()
        val ghost = 0x18233126
        val seg = segPaint

        for (ch in timeText) {
            if (ch.isDigit()) {
                // Draw all seven ghost segments, then active segments on top.
                seg.color = ghost
                drawSegMask(canvas, x, top, digitH, 0b1111111, seg)
                seg.color = active
                drawSegMask(canvas, x, top, digitH, segPattern(ch), seg)
                x += dw * 1.12f
            } else {
                seg.style = Paint.Style.FILL
                seg.color = active
                val cx = x + dw * 0.25f
                val r = digitH * 0.055f
                canvas.drawCircle(cx, h / 2f - digitH * 0.18f, r, seg)
                canvas.drawCircle(cx, h / 2f + digitH * 0.18f, r, seg)
                x += dw * 0.55f
            }
        }
        seg.style = Paint.Style.STROKE
    }

    private fun drawSegMask(canvas: Canvas, left: Float, top: Float, dh: Float, mask: Int, seg: Paint) {
        val dw = dh * 0.62f
        seg.style = Paint.Style.STROKE
        seg.strokeCap = Paint.Cap.SQUARE
        seg.strokeWidth = dh * 0.13f
        val xL = left + dw * 0.12f
        val xR = left + dw * 0.88f
        val y0 = top + dh * 0.06f
        val yM = top + dh * 0.50f
        val y1 = top + dh * 0.94f
        if ((mask and 1) != 0) canvas.drawLine(xL, y0, xR, y0, seg)
        if ((mask and 64) != 0) canvas.drawLine(xL, yM, xR, yM, seg)
        if ((mask and 8) != 0) canvas.drawLine(xL, y1, xR, y1, seg)
        if ((mask and 32) != 0) canvas.drawLine(xL, y0, xL, yM, seg)
        if ((mask and 2) != 0) canvas.drawLine(xR, y0, xR, yM, seg)
        if ((mask and 16) != 0) canvas.drawLine(xL, yM, xL, y1, seg)
        if ((mask and 4) != 0) canvas.drawLine(xR, yM, xR, y1, seg)
        seg.strokeCap = Paint.Cap.ROUND
    }

    // ---------- style 24: Pong - a real self-playing match ----------

    // Game state; survives frame skips and face switches. Positions are in
    // view pixels, velocities in pixels per second.
    private var pongInit = false
    private var pongW = 0f
    private var pongH = 0f
    private var pongBallX = 0f
    private var pongBallY = 0f
    private var pongBallVx = 0f
    private var pongBallVy = 0f
    private var pongLeftY = 0f
    private var pongRightY = 0f
    private var pongLeftErr = 0f   // AI aim error for the current approach
    private var pongRightErr = 0f
    private var pongScoreLeft = 0
    private var pongScoreRight = 0
    private var pongServeDir = 1f  // +1 = serve to the right, -1 = to the left
    private var pongServeAt = 0L   // uptimeMillis of the next serve; 0 = in play
    private var pongLastFrame = 0L

    private fun pongReset(w: Float, h: Float, now: Long) {
        pongInit = true
        pongW = w
        pongH = h
        pongBallX = w / 2f
        pongBallY = h / 2f
        pongBallVx = 0f
        pongBallVy = 0f
        pongLeftY = h / 2f
        pongRightY = h / 2f
        pongServeDir = if (kotlin.random.Random(now).nextBoolean()) 1f else -1f
        pongServeAt = now + 700L
        pongLastFrame = now
    }

    /** One point won; [scorer] 0 = left player, 1 = right player. */
    private fun pongPoint(scorer: Int, now: Long) {
        if (scorer == 0) pongScoreLeft++ else pongScoreRight++
        if (pongScoreLeft >= PONG_GAME_TO || pongScoreRight >= PONG_GAME_TO) {
            // Game won - the scoreboard starts over.
            pongScoreLeft = 0
            pongScoreRight = 0
        }
        // The player who conceded receives the next serve.
        pongServeDir = if (scorer == 0) 1f else -1f
        pongBallX = pongW / 2f
        pongBallY = pongH / 2f
        pongBallVx = 0f
        pongBallVy = 0f
        pongServeAt = now + PONG_SERVE_PAUSE_MS
    }

    private fun pongServe(now: Long) {
        val rnd = kotlin.random.Random(now)
        pongBallX = pongW / 2f
        pongBallY = pongH * (0.3f + 0.4f * rnd.nextFloat())
        pongBallVx = pongServeDir * pongBaseSpeed()
        pongBallVy = pongBallVx * (rnd.nextFloat() * 1.2f - 0.6f)
        // Fresh aim error for both defenders.
        pongLeftErr = pongAimError(rnd)
        pongRightErr = pongAimError(rnd)
    }

    private fun pongBaseSpeed(): Float = pongW * 0.55f

    /** How far off the ball the AI aims this approach; large errors miss. */
    private fun pongAimError(rnd: kotlin.random.Random): Float =
        (rnd.nextFloat() * 2f - 1f) * pongH * PONG_PADDLE_H * 0.9f

    private fun approachPong(cur: Float, target: Float, maxStep: Float): Float {
        val d = target - cur
        return if (abs(d) <= maxStep) target else cur + if (d > 0f) maxStep else -maxStep
    }

    /**
     * Advance the match by one frame. Real physics: the ball bounces off the
     * top/bottom edges and off the paddle faces, the return angle depends on
     * where exactly the paddle caught it, and each hit makes the ball a bit
     * faster. Both paddles are AI with a speed limit and a per-serve aim
     * error, so steep shots really do score points.
     */
    private fun stepPong(now: Long) {
        val dt = (now - pongLastFrame).coerceIn(0L, 100L) / 1000f
        pongLastFrame = now

        val w = pongW
        val h = pongH
        val margin = min(w, h) * 0.06f
        val paddleH = h * PONG_PADDLE_H
        val half = paddleH / 2f
        val paddleW = maxOf(dp(4f), w * 0.012f)
        val planeL = margin + paddleW
        val planeR = w - margin - paddleW
        val ballR = maxOf(dp(3f), min(w, h) * 0.022f)

        // Between points the paddles drift home and the score stays visible.
        if (pongServeAt != 0L) {
            if (now < pongServeAt) {
                val home = h * 0.85f * dt
                pongLeftY = approachPong(pongLeftY, h / 2f, home)
                pongRightY = approachPong(pongRightY, h / 2f, home)
                return
            }
            pongServe(now)
            pongServeAt = 0L
        }

        val step = h * 0.85f * dt
        val targetL = if (pongBallVx < 0f) (pongBallY + pongLeftErr).coerceIn(half, h - half) else h / 2f
        val targetR = if (pongBallVx > 0f) (pongBallY + pongRightErr).coerceIn(half, h - half) else h / 2f
        pongLeftY = approachPong(pongLeftY, targetL, step)
        pongRightY = approachPong(pongRightY, targetR, step)

        pongBallX += pongBallVx * dt
        pongBallY += pongBallVy * dt
        if (pongBallY < ballR) { pongBallY = ballR; pongBallVy = abs(pongBallVy) }
        if (pongBallY > h - ballR) { pongBallY = h - ballR; pongBallVy = -abs(pongBallVy) }

        // Paddle faces: reflect while the ball is still in front of them.
        val reach = half + ballR
        if (pongBallVx < 0f && pongBallX - ballR <= planeL && pongBallX >= margin * 0.5f &&
            pongBallY > pongLeftY - reach && pongBallY < pongLeftY + reach
        ) {
            pongBallX = planeL + ballR
            pongBounce((pongBallY - pongLeftY) / reach, 1f)
            pongRightErr = pongAimError(kotlin.random.Random(now))
        }
        if (pongBallVx > 0f && pongBallX + ballR >= planeR && pongBallX <= w - margin * 0.5f &&
            pongBallY > pongRightY - reach && pongBallY < pongRightY + reach
        ) {
            pongBallX = planeR - ballR
            pongBounce((pongBallY - pongRightY) / reach, -1f)
            pongLeftErr = pongAimError(kotlin.random.Random(now))
        }

        // A ball fully past a paddle is a point for the other side.
        if (pongBallX + ballR < 0f) pongPoint(1, now)     // left conceded, right scores
        else if (pongBallX - ballR > w) pongPoint(0, now) // right conceded, left scores
    }

    /** Reflect off a paddle: the hit offset sets the return angle (up to
     *  60 degrees) and every hit makes the ball a bit faster, capped. */
    private fun pongBounce(offset: Float, dir: Float) {
        val clamped = offset.coerceIn(-1f, 1f)
        val speed = min(
            pongBaseSpeed() * 1.9f,
            kotlin.math.hypot(pongBallVx, pongBallVy) * 1.045f
        )
        val angle = clamped * (PI.toFloat() / 3f)
        pongBallVx = dir * speed * cos(angle)
        pongBallVy = speed * sin(angle)
    }

    private fun drawPong(canvas: Canvas, w: Float, h: Float) {
        val now = SystemClock.uptimeMillis()
        if (!pongInit || w != pongW || h != pongH) pongReset(w, h, now)
        stepPong(now)

        val main = ink()
        val margin = min(w, h) * 0.06f
        val paddleH = h * PONG_PADDLE_H
        val paddleW = maxOf(dp(4f), w * 0.012f)
        val ballR = maxOf(dp(3f), min(w, h) * 0.022f)

        // Center net, dashed.
        strokePaint.color = dimmed(main, 0.32f)
        strokePaint.strokeWidth = dp(1.2f)
        var y = margin
        while (y < h - margin) {
            canvas.drawLine(w / 2f, y, w / 2f, min(y + dp(6f), h - margin), strokePaint)
            y += dp(13f)
        }

        // Paddles and ball (the ball hides between points).
        fillPaint.color = main
        canvas.drawRoundRect(
            margin, pongLeftY - paddleH / 2f,
            margin + paddleW, pongLeftY + paddleH / 2f, paddleW, paddleW, fillPaint
        )
        canvas.drawRoundRect(
            w - margin - paddleW, pongRightY - paddleH / 2f,
            w - margin, pongRightY + paddleH / 2f, paddleW, paddleW, fillPaint
        )
        if (pongServeAt == 0L) canvas.drawCircle(pongBallX, pongBallY, ballR, fillPaint)

        // The clock is the headline: big, bright, top center (the same size
        // and place the original pong-clock face used), so the face still
        // reads as a clock at a glance.
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = Typeface.MONOSPACE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(h * 0.30f, w * 0.13f)
        paint.color = main
        drawCenteredAt(canvas, hourMinuteText(), w / 2f, h * 0.23f, paint)

        // The running match score below the court, secondary but readable:
        // "3 : 7", game to 11.
        paint.textSize = min(h * 0.12f, w * 0.065f)
        paint.color = dimmed(main, 0.72f)
        drawCenteredAt(
            canvas,
            pongScoreLeft.toString() + " : " + pongScoreRight.toString(),
            w / 2f, h - margin * 0.5f, paint
        )

        // 20 fps is smooth enough for a screensaver but much cheaper than 60 fps.
        postInvalidateDelayed(50L)
    }

    // ---------- style 25: word clock ----------

    private fun numberWord(v: Int): String = when (v) {
        0 -> "ZERO"
        1 -> "ONE"
        2 -> "TWO"
        3 -> "THREE"
        4 -> "FOUR"
        5 -> "FIVE"
        6 -> "SIX"
        7 -> "SEVEN"
        8 -> "EIGHT"
        9 -> "NINE"
        10 -> "TEN"
        11 -> "ELEVEN"
        12 -> "TWELVE"
        13 -> "THIRTEEN"
        14 -> "FOURTEEN"
        15 -> "FIFTEEN"
        16 -> "SIXTEEN"
        17 -> "SEVENTEEN"
        18 -> "EIGHTEEN"
        19 -> "NINETEEN"
        20 -> "TWENTY"
        30 -> "THIRTY"
        40 -> "FORTY"
        50 -> "FIFTY"
        else -> if (v in 21..59) {
            numberWord((v / 10) * 10) + " " + numberWord(v % 10)
        } else {
            v.toString()
        }
    }

    private fun drawWordClock(canvas: Canvas, w: Float, h: Float) {
        val (h24, m, _) = wallTime()
        val use24 = Prefs.force24h(context) || android.text.format.DateFormat.is24HourFormat(context)
        val hour = if (use24) h24 else if (h24 % 12 == 0) 12 else h24 % 12
        val first = numberWord(hour)
        val second = numberWord(m)
        val main = ink()

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfBold
        paint.textAlign = Paint.Align.CENTER
        paint.color = main
        paint.textSize = min(h * 0.28f, w * 0.13f)
        paint.letterSpacing = 0.06f
        drawCenteredAt(canvas, first, w / 2f, h * 0.38f, paint)
        paint.color = dimmed(main, 0.62f)
        paint.textSize *= 0.82f
        drawCenteredAt(canvas, second, w / 2f, h * 0.65f, paint)
        paint.letterSpacing = 0f
    }

    // ---------- style 26: binary HH:MM:SS ----------

    private fun drawBinary(canvas: Canvas, w: Float, h: Float) {
        val (hh, mm, ss) = wallTime()
        val values = intArrayOf(hh / 10, hh % 10, mm / 10, mm % 10, ss / 10, ss % 10)
        val main = ink()
        val cols = 6
        val rows = 4
        val cellW = w / (cols + 1.2f)
        val cellH = h / (rows + 1.6f)
        val r = min(cellW, cellH) * 0.23f
        val startX = (w - cellW * (cols - 1)) / 2f
        val startY = (h - cellH * (rows - 1)) / 2f

        for (c in 0 until cols) {
            for (row in 0 until rows) {
                val bit = 3 - row
                val on = ((values[c] shr bit) and 1) != 0
                fillPaint.color = if (on) main else dimmed(main, 0.12f)
                canvas.drawCircle(startX + c * cellW, startY + row * cellH, r, fillPaint)
            }
        }
        // subtle separators between HH / MM / SS
        fillPaint.color = dimmed(main, 0.45f)
        val dotR = maxOf(dp(1.5f), r * 0.23f)
        for (sep in intArrayOf(2, 4)) {
            val x = startX + (sep - 0.5f) * cellW
            canvas.drawCircle(x, h * 0.43f, dotR, fillPaint)
            canvas.drawCircle(x, h * 0.57f, dotR, fillPaint)
        }
    }

    // ---------- style 27: Polar Clock ----------

    private fun drawPolar(canvas: Canvas, w: Float, h: Float) {
        val (hh, mm, ss) = wallTime()
        val cx = w / 2f
        val cy = h / 2f
        val baseR = min(w, h) * 0.39f
        val main = ink()
        val track = dimmed(main, 0.14f)
        val stroke = maxOf(dp(4f), baseR * 0.075f)
        val values = floatArrayOf((hh % 12) / 12f, mm / 60f, ss / 60f)

        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = stroke
        for (i in 0..2) {
            val r = baseR - i * stroke * 1.65f
            arcRect.set(cx - r, cy - r, cx + r, cy + r)
            strokePaint.color = track
            canvas.drawArc(arcRect, -90f, 360f, false, strokePaint)
            strokePaint.color = dimmed(main, 1f - i * 0.20f)
            canvas.drawArc(arcRect, -90f, 360f * values[i], false, strokePaint)
        }

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfBold
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(w, h) * 0.17f
        paint.color = main
        drawCenteredAt(canvas, timeText, cx, cy, paint)
    }

    // ---------- style 28: drifting burn-in-safe clock ----------

    private fun drawDrift(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w * 0.72f, cap(), tfLight) * 0.72f
        val now = System.currentTimeMillis() / 1000L
        // Deterministic slow path: no Random allocations and no sudden jumps.
        val ax = sin(now / 37.0).toFloat()
        val ay = sin(now / 53.0 + 1.7).toFloat()
        val dx = ax * w * 0.11f
        val dy = ay * h * 0.16f

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfLight
        paint.textSize = size
        paint.color = dimmed(ink(), 0.86f)
        paint.style = Paint.Style.FILL
        val fm = paint.fontMetrics
        paint.textAlign = Paint.Align.CENTER
        val baseline = h / 2f + dy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(timeText, w / 2f + dx, baseline, paint)
    }

    // ---------- style 29: deterministic glitch ----------

    private fun drawGlitch(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w - dp(10f), cap(), tfBlack, inflateFrac = 0.04f)
        val tick = System.currentTimeMillis() / 180L
        val jitterA = (((tick * 37L) % 9L) - 4L).toFloat() * dp(0.55f)
        val jitterB = (((tick * 53L) % 11L) - 5L).toFloat() * dp(0.45f)

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfBlack
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.FILL
        val fm = paint.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f

        paint.color = 0x887C3AED.toInt()
        canvas.drawText(timeText, w / 2f + jitterA, baseline - dp(1f), paint)
        paint.color = 0x8867E8F9.toInt()
        canvas.drawText(timeText, w / 2f + jitterB, baseline + dp(1f), paint)
        paint.color = ink()
        canvas.drawText(timeText, w / 2f, baseline, paint)

        // A few deterministic horizontal dropout slices.
        fillPaint.color = Color.BLACK
        for (i in 0..2) {
            val frac = (((tick + i * 29L) % 100L) / 100f)
            val y = h * (0.28f + frac * 0.44f)
            canvas.drawRect(0f, y, w, y + dp(1.2f), fillPaint)
        }
        postInvalidateDelayed(180L)
    }

    // ---------- style 30: Matrix rain ----------

    private fun drawMatrixRain(canvas: Canvas, w: Float, h: Float) {
        val green = 0xFF00E676.toInt()
        val now = SystemClock.uptimeMillis()
        val colW = maxOf(dp(12f), w / 24f)
        val cols = (w / colW).toInt().coerceAtLeast(1)
        val rows = (h / colW).toInt() + 3

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = Typeface.MONOSPACE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = colW * 0.78f
        val phase = (now / 90L).toInt()
        for (c in 0 until cols) {
            val head = (phase + c * 7) % rows
            for (tail in 0..5) {
                val row = head - tail
                if (row < 0 || row >= rows) continue
                val code = ((c * 17 + row * 31 + phase) % 10)
                paint.color = Color.argb((220 - tail * 34).coerceAtLeast(35), 0, 230, 118)
                canvas.drawText(DIGIT_STRINGS[code], c * colW + colW / 2f, (row + 1) * colW, paint)
            }
        }

        // Dark veil behind the time keeps it readable without a rectangular card.
        paint.typeface = tfBlack
        paint.textSize = fitTextSize(w - dp(12f), cap(), tfBlack)
        val fm = paint.fontMetrics
        val base = h / 2f - (fm.ascent + fm.descent) / 2f
        val textW = paint.measureText(timeText)
        fillPaint.color = 0xB8000000.toInt()
        canvas.drawRoundRect(
            w / 2f - textW / 2f - dp(10f), base + fm.ascent - dp(5f),
            w / 2f + textW / 2f + dp(10f), base + fm.descent + dp(5f),
            dp(8f), dp(8f), fillPaint
        )
        paint.color = green
        canvas.drawText(timeText, w / 2f, base, paint)
        postInvalidateDelayed(90L)
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        w: Float,
        h: Float,
        p: Paint
    ) {
        val fm = p.fontMetrics
        // Centre horizontally: align to CENTER and anchor at w/2 (not the left
        // edge — that is what previously shifted the digits to the right).
        p.textAlign = Paint.Align.CENTER
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, w / 2f, baseline, p)
    }

    private fun cap(): Float =
        118f * resources.displayMetrics.density * sizeFactor()

    /** User clock-size setting: 0 small, 1 normal, 2 large. */
    private fun sizeFactor(): Float = when (Prefs.clockSize(context)) {
        0 -> 0.72f
        2 -> 1.3f
        else -> 1f
    }

    // Standard 5x7 glyphs, cached in the companion object.
    private fun glyph(ch: Char): IntArray? = when (ch) {
        '0' -> GLYPH_0
        '1' -> GLYPH_1
        '2' -> GLYPH_2
        '3' -> GLYPH_3
        '4' -> GLYPH_4
        '5' -> GLYPH_5
        '6' -> GLYPH_6
        '7' -> GLYPH_7
        '8' -> GLYPH_8
        '9' -> GLYPH_9
        else -> null
    }

}
