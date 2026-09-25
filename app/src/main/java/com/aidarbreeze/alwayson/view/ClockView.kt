package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
 *  31..40       : newer concepts - spoken-RU word clock, moon phase,
 *                  solar horizon, orbits, day perimeter, outline type,
 *                  dayline (light/dark), neumorph (light), card deck,
    *                  odometer drums.
    *  41..47       : the rest of the concept sheet - mic-reactive atom,
    *                  real orrery (JPL elements), liquid glass, gradient
    *                  mesh, frosted panel, Swiss railway dial (the seconds
    *                  hand pauses at 12 at the end of each minute) and a
    *                  Braun-style functional dial.
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
        const val STYLE_WORD_RU = 31
        const val STYLE_MOON = 32
        const val STYLE_SOLAR = 33
        const val STYLE_ORBIT = 34
        const val STYLE_PERIMETER = 35
        const val STYLE_OUTLINE = 36
        const val STYLE_DAYLINE = 37
        const val STYLE_NEUMO = 38
        const val STYLE_DECK = 39
        const val STYLE_ODO = 40
        const val STYLE_ATOMIC = 41
        const val STYLE_ORRERY = 42
        const val STYLE_GLASS = 43
        const val STYLE_MESH = 44
        const val STYLE_FROST = 45
        const val STYLE_RAILWAY = 46
        const val STYLE_BRAUN = 47
        const val MAX_CLOCK_STYLE = STYLE_BRAUN

        // Pong match (style 24): first to PONG_GAME_TO points wins the game,
        // then the scoreboard restarts. The ball pauses briefly per point.
        private const val PONG_GAME_TO = 11
        private const val PONG_SERVE_PAUSE_MS = 900L
        private const val PONG_PADDLE_H = 0.24f // paddle length, share of court height
        private const val PONG_BOTTOM_BAND = 0.17f // score strip below the court, share of view height

        /** Renders one style into a small offscreen bitmap for the settings
         *  list. Safe off the window: no attach needed for measure+draw. */
        fun drawStyleThumbnail(ctx: android.content.Context, style: Int, wPx: Int, hPx: Int): Bitmap {
            val v = ClockView(ctx)
            v.styleOverride = style
            v.setTime(android.text.format.DateFormat.format(
                "HH:mm", java.lang.System.currentTimeMillis()).toString())
            val wm = View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY)
            val hm = View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY)
            v.measure(wm, hm)
            v.layout(0, 0, wPx, hPx)
            val bmp = Bitmap.createBitmap(wPx.coerceAtLeast(1), hPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            return bmp
        }

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
        // The mic-reactive face (41) is the only consumer of the microphone;
        // thumbnails pass a styleOverride and never touch it.
        if (s == STYLE_ATOMIC && styleOverride == null) MicLevel.start(context)
        else MicLevel.stop()
        val live = s == 16 || s == 18 || s == STYLE_PONG || s == STYLE_BINARY ||
            s == STYLE_POLAR || s == STYLE_DRIFT || s == STYLE_GLITCH ||
            s == STYLE_MATRIX_RAIN || s == STYLE_ORBIT || s == STYLE_PERIMETER ||
            s == STYLE_OUTLINE
        if (s != lastStyleApplied) {
            // Time rotation switched the face: heights differ per style.
            lastStyleApplied = s
            requestLayout()
        }
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
        MicLevel.stop()
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

    /** Preview override (settings thumbnails); null = follow prefs/rotation. */
    var styleOverride: Int? = null

    private var rotPoolRaw = "\u0000"
    private var rotPool: List<Int> = emptyList()
    private var lastStyleApplied = -1

    /**
     * The effective style. When the user enabled the time rotation and
     * picked at least one face, the style cycles deterministically by wall
     * clock - no timers, every consumer (overlay, dream, previews) agrees.
     */
    private fun style(): Int {
        styleOverride?.let { return it }
        if (Prefs.clockRotateEnabled(context)) {
            val raw = Prefs.clockRotatePool(context)
            if (raw != rotPoolRaw) {
                rotPoolRaw = raw
                rotPool = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in 0..MAX_CLOCK_STYLE }.distinct()
            }
            if (rotPool.isNotEmpty()) {
                val every = Prefs.clockRotateEveryMin(context).coerceAtLeast(1)
                val idx = ((System.currentTimeMillis() / 60000L) / every).toInt()
                return rotPool[idx % rotPool.size]
            }
        }
        return Prefs.clockStyle(context)
    }

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
        12, 20, STYLE_LCD, STYLE_NIXIE, STYLE_MATRIX_RAIN, STYLE_ODO -> Typeface.MONOSPACE
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
        STYLE_WORD_RU -> min(availW * 0.55f, capPx * 1.45f).coerceAtLeast(dp(120f))
        STYLE_MOON -> min(availW * 0.60f, capPx * 1.55f).coerceAtLeast(dp(132f))
        STYLE_SOLAR -> min(availW * 0.62f, capPx * 1.80f).coerceAtLeast(dp(132f))
        STYLE_ORBIT -> min(availW, capPx * 1.60f).coerceAtLeast(dp(150f))
        STYLE_PERIMETER -> min(availW * 0.55f, capPx * 1.70f).coerceAtLeast(dp(124f))
        STYLE_OUTLINE -> min(availW * 0.50f, capPx * 1.40f).coerceAtLeast(dp(104f))
        STYLE_DAYLINE -> min(availW * 0.50f, capPx * 1.60f).coerceAtLeast(dp(110f))
        STYLE_NEUMO -> min(availW * 0.45f, capPx * 1.40f).coerceAtLeast(dp(104f))
        STYLE_DECK -> min(availW * 0.50f, capPx * 1.50f).coerceAtLeast(dp(120f))
        STYLE_ODO -> min(availW * 0.42f, capPx * 1.40f).coerceAtLeast(dp(96f))
        STYLE_ATOMIC -> min(availW * 0.90f, capPx * 1.70f).coerceAtLeast(dp(150f))
        STYLE_ORRERY -> min(availW * 0.95f, capPx * 1.80f).coerceAtLeast(dp(150f))
        STYLE_GLASS -> min(availW * 0.60f, capPx * 1.60f).coerceAtLeast(dp(120f))
        STYLE_MESH -> min(availW * 0.60f, capPx * 1.60f).coerceAtLeast(dp(120f))
        STYLE_FROST -> min(availW * 0.55f, capPx * 1.50f).coerceAtLeast(dp(110f))
        STYLE_RAILWAY -> min(availW * 0.92f, capPx * 1.75f).coerceAtLeast(dp(150f))
        STYLE_BRAUN -> min(availW * 0.92f, capPx * 1.75f).coerceAtLeast(dp(150f))
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
            STYLE_WORD_RU -> drawWordRu(canvas, w, h)
            STYLE_MOON -> drawMoonPhase(canvas, w, h)
            STYLE_SOLAR -> drawSolarHorizon(canvas, w, h)
            STYLE_ORBIT -> drawOrbitClock(canvas, w, h)
            STYLE_PERIMETER -> drawPerimeter(canvas, w, h)
            STYLE_OUTLINE -> drawOutlineType(canvas, w, h)
            STYLE_DAYLINE -> drawDayline(canvas, w, h)
            STYLE_NEUMO -> drawNeumo(canvas, w, h)
            STYLE_DECK -> drawDeck(canvas, w, h)
            STYLE_ODO -> drawOdometer(canvas, w, h)
            STYLE_ATOMIC -> drawAtomicLab(canvas, w, h)
            STYLE_ORRERY -> drawRealOrrery(canvas, w, h)
            STYLE_GLASS -> drawLiquidGlass(canvas, w, h)
            STYLE_MESH -> drawGradientMesh(canvas, w, h)
            STYLE_FROST -> drawFrosted(canvas, w, h)
            STYLE_RAILWAY -> drawRailway(canvas, w, h)
            STYLE_BRAUN -> drawBraun(canvas, w, h)
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
        val courtH = h * (1f - PONG_BOTTOM_BAND)
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
                pongLeftY = approachPong(pongLeftY, courtH / 2f, home)
                pongRightY = approachPong(pongRightY, courtH / 2f, home)
                return
            }
            pongServe(now)
            pongServeAt = 0L
        }

        val step = h * 0.85f * dt
        val targetL = if (pongBallVx < 0f) (pongBallY + pongLeftErr).coerceIn(half, courtH - half) else courtH / 2f
        val targetR = if (pongBallVx > 0f) (pongBallY + pongRightErr).coerceIn(half, courtH - half) else courtH / 2f
        pongLeftY = approachPong(pongLeftY, targetL, step)
        pongRightY = approachPong(pongRightY, targetR, step)

        pongBallX += pongBallVx * dt
        pongBallY += pongBallVy * dt
        if (pongBallY < ballR) { pongBallY = ballR; pongBallVy = abs(pongBallVy) }
        if (pongBallY > courtH - ballR) { pongBallY = courtH - ballR; pongBallVy = -abs(pongBallVy) }

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
        val courtH = h * (1f - PONG_BOTTOM_BAND)
        val paddleH = h * PONG_PADDLE_H
        val paddleW = maxOf(dp(4f), w * 0.012f)
        val ballR = maxOf(dp(3f), min(w, h) * 0.022f)

        // Center net, dashed.
        strokePaint.color = dimmed(main, 0.32f)
        strokePaint.strokeWidth = dp(1.2f)
        var y = margin
        while (y < courtH - margin) {
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

        // The clock is the headline: big, bright, top center, above the
        // court, so the face still reads as a clock at a glance.
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = Typeface.MONOSPACE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(h * 0.32f, w * 0.15f)
        paint.color = main
        drawCenteredAt(canvas, hourMinuteText(), w / 2f, h * 0.19f, paint)

        // The running match score below the court, secondary but readable:
        // "3 : 7", game to 11.
        paint.textSize = min(h * 0.12f, w * 0.065f)
        paint.color = dimmed(main, 0.72f)
        drawCenteredAt(
            canvas,
            pongScoreLeft.toString() + " : " + pongScoreRight.toString(),
            w / 2f, courtH + (h - courtH) / 2f, paint
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


    // ---------- styles 31-40: newer time visualizations ----------

    // ---- 31: word clock, spoken Russian ----

    private val ruOnes = arrayOf("НОЛЬ", "ОДИН", "ДВА", "ТРИ", "ЧЕТЫРЕ", "ПЯТЬ", "ШЕСТЬ", "СЕМЬ", "ВОСЕМЬ", "ДЕВЯТЬ")
    private val ruTeens = arrayOf("ДЕСЯТЬ", "ОДИННАДЦАТЬ", "ДВЕНАДЦАТЬ", "ТРИНАДЦАТЬ", "ЧЕТЫРНАДЦАТЬ", "ПЯТНАДЦАТЬ", "ШЕСТНАДЦАТЬ", "СЕМНАДЦАТЬ", "ВОСЕМНАДЦАТЬ", "ДЕВЯТНАДЦАТЬ")
    private val ruTens = arrayOf("", "ДЕСЯТЬ", "ДВАДЦАТЬ", "ТРИДЦАТЬ", "СОРОК", "ПЯТЬДЕСЯТ")
    private val ruHourNom = arrayOf("ДВЕНАДЦАТЬ", "ЧАС", "ДВА", "ТРИ", "ЧЕТЫРЕ", "ПЯТЬ", "ШЕСТЬ", "СЕМЬ", "ВОСЕМЬ", "ДЕВЯТЬ", "ДЕСЯТЬ", "ОДИННАДЦАТЬ", "ДВЕНАДЦАТЬ")
    private val ruHourGen = arrayOf("ДВЕНАДЦАТОГО", "ПЕРВОГО", "ВТОРОГО", "ТРЕТЬЕГО", "ЧЕТВЁРТОГО", "ПЯТОГО", "ШЕСТОГО", "СЕДЬМОГО", "ВОСЬМОГО", "ДЕВЯТОГО", "ДЕСЯТОГО", "ОДИННАДЦАТОГО", "ДВЕНАДЦАТОГО")
    private val ruWeek = arrayOf("ВОСКРЕСЕНЬЕ", "ПОНЕДЕЛЬНИК", "ВТОРНИК", "СРЕДА", "ЧЕТВЕРГ", "ПЯТНИЦА", "СУББОТА")
    private val ruMonthGen = arrayOf("ЯНВАРЯ", "ФЕВРАЛЯ", "МАРТА", "АПРЕЛЯ", "МАЯ", "ИЮНЯ", "ИЮЛЯ", "АВГУСТА", "СЕНТЯБРЯ", "ОКТЯБРЯ", "НОЯБРЯ", "ДЕКАБРЯ")
    private val ruDayOrd = arrayOf("ПЕРВОГО", "ВТОРОГО", "ТРЕТЬЕГО", "ЧЕТВЁРТОГО", "ПЯТОГО", "ШЕСТОГО", "СЕДЬМОГО", "ВОСЬМОГО", "ДЕВЯТОГО", "ДЕСЯТОГО", "ОДИННАДЦАТОГО", "ДВЕНАДЦАТОГО", "ТРИНАДЦАТОГО", "ЧЕТЫРНАДЦАТОГО", "ПЯТНАДЦАТОГО", "ШЕСТНАДЦАТОГО", "СЕМНАДЦАТОГО", "ВОСЕМНАДЦАТОГО", "ДЕВЯТНАДЦАТОГО", "ДВАДЦАТОГО", "ДВАДЦАТЬ ПЕРВОГО", "ДВАДЦАТЬ ВТОРОГО", "ДВАДЦАТЬ ТРЕТЬЕГО", "ДВАДЦАТЬ ЧЕТВЁРТОГО", "ДВАДЦАТЬ ПЯТОГО", "ДВАДЦАТЬ ШЕСТОГО", "ДВАДЦАТЬ СЕДЬМОГО", "ДВАДЦАТЬ ВОСЬМОГО", "ДВАДЦАТЬ ДЕВЯТОГО", "ТРИДЦАТОГО", "ТРИДЦАТЬ ПЕРВОГО")
    private val ruGenOnes = arrayOf("", "ОДНОЙ", "ДВУХ", "ТРЁХ", "ЧЕТЫРЁХ", "ПЯТИ", "ШЕСТИ", "СЕМИ", "ВОСЬМИ", "ДЕВЯТИ")
    private val ruGenTeens = arrayOf("ДЕСЯТИ", "ОДИННАДЦАТИ", "ДВЕНАДЦАТИ", "ТРИНАДЦАТИ", "ЧЕТЫРНАДЦАТИ", "ПЯТНАДЦАТИ", "ШЕСТНАДЦАТИ", "СЕМНАДЦАТИ", "ВОСЕМНАДЦАТИ", "ДЕВЯТНАДЦАТИ")
    private val ruGenTens = arrayOf("", "", "ДВАДЦАТИ", "ТРИДЦАТИ", "СОРОКА", "ПЯТИДЕСЯТИ")

    private fun ruMinWord(n: Int): String = when {
        n < 10 -> ruOnes[n]
        n < 20 -> ruTeens[n - 10]
        n % 10 == 0 -> ruTens[n / 10]
        else -> ruTens[n / 10] + " " + ruOnes[n % 10]
    }

    private fun ruFemMin(n: Int): String = when (n) {
        1 -> "ОДНА"
        2 -> "ДВЕ"
        21 -> "ДВАДЦАТЬ ОДНА"
        22 -> "ДВАДЦАТЬ ДВЕ"
        else -> ruMinWord(n)
    }

    private fun ruPlural(n: Int, one: String, few: String, many: String): String {
        val a = n % 10
        val b = n % 100
        return if (a == 1 && b != 11) one
        else if (a in 2..4 && !(b in 12..14)) few
        else many
    }

    private fun ruGenNum(n: Int): String = when {
        n < 10 -> ruGenOnes[n]
        n < 20 -> ruGenTeens[n - 10]
        n % 10 == 0 -> ruGenTens[n / 10]
        else -> ruGenTens[n / 10] + " " + ruGenOnes[n % 10]
    }

    /** «без пятнадцати два», «пол первого», «ровно пять часов». */
    private fun ruWordTime(h: Int, m: Int): Pair<String, String> {
        val h12 = h % 12
        val gen = ruHourGen[h12 + 1]
        val nom = ruHourNom[h12 + 1]
        return when {
            m == 0 -> (ruMinWord(if (h12 == 0) 12 else h12) + " " +
                ruPlural(h, "ЧАС", "ЧАСА", "ЧАСОВ")) to "РОВНО"
            m <= 24 -> (ruFemMin(m) + " " + ruPlural(m, "МИНУТА", "МИНУТЫ", "МИНУТ")) to gen
            m <= 34 -> "ПОЛ" to gen
            60 - m == 15 -> "БЕЗ ЧЕТВЕРТИ" to nom
            else -> {
                val left = 60 - m
                val tail = if (left < 5) (if (left == 1) " МИНУТЫ" else " МИНУТ") else ""
                ("БЕЗ " + ruGenNum(left) + tail) to nom
            }
        }
    }

    private fun drawWordRu(canvas: Canvas, w: Float, h: Float) {
        val (h24, m, _) = wallTime()
        val (a, b) = ruWordTime(h24, m)
        val cal = Calendar.getInstance()
        val dateLine = "СЕГОДНЯ " + ruWeek[cal.get(Calendar.DAY_OF_WEEK) - 1] + ", " +
            ruDayOrd[cal.get(Calendar.DAY_OF_MONTH) - 1] + " " + ruMonthGen[cal.get(Calendar.MONTH)]
        val main = ink()

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfBold
        paint.textAlign = Paint.Align.CENTER
        paint.color = main
        var ts = min(h * 0.15f, w * 0.10f)
        paint.textSize = ts
        val need = maxOf(paint.measureText(a), paint.measureText(b))
        if (need > w * 0.94f && need > 0f) ts *= (w * 0.94f) / need
        paint.textSize = ts
        val lineStep = ts * 1.3f
        val centerY = (h - h * 0.10f) / 2f
        drawCenteredAt(canvas, a, w / 2f, centerY - lineStep / 2f, paint)
        paint.color = dimmed(main, 0.80f)
        drawCenteredAt(canvas, b, w / 2f, centerY + lineStep / 2f, paint)
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = min(h * 0.05f, w * 0.038f)
        paint.color = dimmed(main, 0.55f)
        drawCenteredAt(canvas, dateLine, w / 2f, h - h * 0.06f, paint)
    }

    // ---- 32: moon phase (Meeus phase angle) ----

    private data class MoonPhaseInfo(
        val illum: Float,
        val ageDays: Float,
        val waxing: Boolean,
        val name: String,
        val angleDeg: Double
    )

    private fun moonPhaseInfo(nowMs: Long): MoonPhaseInfo {
        val jd = nowMs / 86400000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        val d = 297.8501921 + 445267.1114034 * t
        val ms = 357.5291092 + 35999.0502909 * t
        val mp = 134.9633964 + 477198.8675055 * t
        var i = 180.0 - d - 6.289 * kotlin.math.sin(Math.toRadians(mp)) +
            2.1 * kotlin.math.sin(Math.toRadians(ms)) -
            1.274 * kotlin.math.sin(Math.toRadians(2 * d - mp)) -
            0.658 * kotlin.math.sin(Math.toRadians(2 * d)) -
            0.214 * kotlin.math.sin(Math.toRadians(2 * mp)) -
            0.11 * kotlin.math.sin(Math.toRadians(d))
        i = (i % 360.0 + 360.0) % 360.0
        val illum = ((1 + kotlin.math.cos(Math.toRadians(i))) / 2).toFloat()
        val waxing = i < 180.0
        val age = (((180 - i) % 360 + 360) % 360) / 360.0 * 29.530588
        val name = when {
            age < 1.0 || age > 28.53 -> "Новолуние"
            age < 6.38 -> "Растущий серп"
            age < 8.38 -> "Первая четверть"
            age < 13.77 -> "Растущая луна"
            age < 15.77 -> "Полнолуние"
            age < 21.15 -> "Убывающая луна"
            age < 23.15 -> "Последняя четверть"
            else -> "Убывающий серп"
        }
        return MoonPhaseInfo(illum, age.toFloat(), waxing, name, i)
    }

    /** Shadow region: limb semicircle + elliptical terminator (as in the
     *  reference sheet); i in degrees, 0 = full, 180 = new. */
    private fun moonShadowPath(i: Double, r: Float): Path {
        val p = Path()
        val waning = i > 180.0
        val xarc = ((if (waning) 1.0 else -1.0) *
            kotlin.math.cos(Math.toRadians(i)) * r).toFloat()
        val rx = kotlin.math.max(kotlin.math.abs(xarc), r * 0.02f)
        val disc = RectF(-r, -r, r, r)
        if (waning) p.arcTo(disc, -90f, 180f) else p.arcTo(disc, -90f, -180f)
        val term = RectF(-rx, -r, rx, r)
        if (xarc > 0f) p.arcTo(term, 90f, -180f) else p.arcTo(term, 90f, 180f)
        p.close()
        return p
    }

    private fun drawMoonPhase(canvas: Canvas, w: Float, h: Float) {
        val now = System.currentTimeMillis()
        val mi = moonPhaseInfo(now)
        val main = ink()
        val r = min(h * 0.30f, w * 0.22f)
        val cx = w * 0.28f
        val cy = h * 0.46f
        fillPaint.color = 0xFFE4E9F4.toInt()
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.color = 0x2E93A0BD.toInt()
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.25f, r * 0.18f, fillPaint)
        canvas.drawCircle(cx + r * 0.28f, cy + r * 0.30f, r * 0.24f, fillPaint)
        canvas.drawCircle(cx - r * 0.10f, cy + r * 0.48f, r * 0.12f, fillPaint)
        canvas.drawCircle(cx + r * 0.50f, cy - r * 0.38f, r * 0.10f, fillPaint)
        canvas.save()
        canvas.translate(cx, cy)
        fillPaint.color = 0xFF070A12.toInt()
        canvas.drawPath(moonShadowPath(mi.angleDeg, r), fillPaint)
        canvas.restore()
        strokePaint.color = dimmed(main, 0.25f)
        strokePaint.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, r, strokePaint)

        val tx = w * 0.52f + w * 0.22f
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfSans
        paint.textAlign = Paint.Align.CENTER
        paint.color = main
        paint.textSize = min(h * 0.20f, w * 0.115f)
        drawCenteredAt(canvas, hourMinuteText(), tx, h * 0.22f, paint)
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = min(h * 0.055f, w * 0.042f)
        paint.color = dimmed(main, 0.75f)
        drawCenteredAt(canvas, mi.name, tx, h * 0.45f, paint)
        drawCenteredAt(canvas, "освещено " + (mi.illum * 100).toInt() + "%", tx, h * 0.54f, paint)
        drawCenteredAt(canvas, "возраст " + String.format(Locale.US, "%.1f", mi.ageDays) + " сут", tx, h * 0.63f, paint)
        drawCenteredAt(canvas, dateShortLine(), tx, h * 0.76f, paint)
    }

    private fun dateShortLine(): String =
        SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())

    // ---- 33: solar horizon (NOAA-style approximation) ----

    /** Sun altitude/azimuth in degrees for the saved weather point
     *  (or Amsterdam when none). */
    private fun sunAltAz(nowMs: Long): Pair<Double, Double> {
        val loc = try {
            Prefs.weatherLocation(context)
        } catch (_: Exception) {
            null
        }
        val lat = loc?.first?.toDouble() ?: 52.3676
        val lon = loc?.second?.toDouble() ?: 4.9041
        val jd = nowMs / 86400000.0 + 2440587.5
        val n = jd - 2451545.0
        val t = n / 36525.0
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val mm = 357.52911 + 35999.05029 * t - 0.0001537 * t * t
        val c = 1.914602 * kotlin.math.sin(Math.toRadians(mm)) +
            0.019993 * kotlin.math.sin(Math.toRadians(2 * mm)) +
            0.000289 * kotlin.math.sin(Math.toRadians(3 * mm))
        val omega = 125.04 - 1934.136 * t
        val lambda = l0 + c - 0.00569 - 0.00478 * kotlin.math.sin(Math.toRadians(omega))
        val eps = 23.439291 - 0.0000004 * t
        val dec = kotlin.math.asin(
            kotlin.math.sin(Math.toRadians(eps)) * kotlin.math.sin(Math.toRadians(lambda))
        )
        val ra = kotlin.math.atan2(
            kotlin.math.cos(Math.toRadians(eps)) * kotlin.math.sin(Math.toRadians(lambda)),
            kotlin.math.cos(Math.toRadians(lambda))
        )
        val gmst = (18.697374558 + 24.06570982441908 * n) % 24.0
        var hh = (gmst * 15.0 + lon) - Math.toDegrees(ra)
        hh = (hh % 360.0 + 540.0) % 360.0 - 180.0
        val latR = Math.toRadians(lat)
        val alt = kotlin.math.asin(
            kotlin.math.sin(latR) * kotlin.math.sin(dec) +
                kotlin.math.cos(latR) * kotlin.math.cos(dec) * kotlin.math.cos(Math.toRadians(hh))
        )
        val azRaw = kotlin.math.atan2(
            kotlin.math.sin(Math.toRadians(hh)),
            kotlin.math.cos(Math.toRadians(hh)) * kotlin.math.sin(latR) -
                kotlin.math.tan(dec) * kotlin.math.cos(latR)
        ) + Math.PI
        val az = (Math.toDegrees(azRaw) % 360.0 + 360.0) % 360.0
        return Math.toDegrees(alt) to az
    }

    private fun drawSolarHorizon(canvas: Canvas, w: Float, h: Float) {
        val now = System.currentTimeMillis()
        val (alt, az) = sunAltAz(now)
        val horizonY = h * 0.80f
        val topY = h * 0.08f
        val day = alt > 12
        val gold = alt > 0 && alt <= 12
        val dusk = alt <= 0 && alt > -6
        val topColor = when {
            day -> 0xFF2B6FB5.toInt()
            gold -> 0xFF16345A.toInt()
            dusk -> 0xFF0A1330.toInt()
            else -> 0xFF03060E.toInt()
        }
        val botColor = when {
            day -> 0xFFCDE2F4.toInt()
            gold -> 0xFFF2A45C.toInt()
            dusk -> 0xFF7C4A62.toInt()
            else -> 0xFF131A2C.toInt()
        }
        fillPaint.shader = LinearGradient(0f, topY - h * 0.06f, 0f, horizonY, topColor, botColor, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, horizonY, fillPaint)
        fillPaint.shader = null
        fillPaint.color = 0xFF0A0E14.toInt()
        canvas.drawRect(0f, horizonY, w, h, fillPaint)
        strokePaint.color = 0x40FFFFFF.toInt()
        strokePaint.strokeWidth = dp(1f)
        canvas.drawLine(0f, horizonY, w, horizonY, strokePaint)

        var azOff = az - 180.0
        if (azOff > 180.0) azOff -= 360.0
        if (azOff < -180.0) azOff += 360.0
        val sx = w * (0.5f + (azOff / 360.0)).toFloat()
        val sy = horizonY - (kotlin.math.max(alt, -8.0) / 90.0).toFloat() * (horizonY - topY)
        val r = min(w, h) * 0.045f
        if (alt > -8.0) {
            fillPaint.shader = RadialGradient(sx, sy, r * 3.2f, 0x66FFAE4E.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawCircle(sx, sy, r * 3.2f, fillPaint)
            fillPaint.shader = null
            fillPaint.color = if (day) 0xFFFFF4CD.toInt() else 0xFFFFD279.toInt()
            canvas.drawCircle(sx, sy, r, fillPaint)
        }

        val inkColor = if (day) 0xFF10233C.toInt() else 0xFFF5F7FB.toInt()
        paint.reset()
        paint.isAntiAlias = true
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = tfBold
        paint.textSize = min(h * 0.16f, w * 0.12f)
        paint.color = inkColor
        canvas.drawText(hourMinuteText(), dp(10f), topY + h * 0.10f, paint)
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = min(h * 0.045f, w * 0.036f)
        paint.color = dimmed(inkColor, 0.9f)
        canvas.drawText(String.format(Locale.US, "ALT %+.1f\u00b0  AZ %.0f\u00b0", alt, az), dp(10f), topY + h * 0.16f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(dateShortLine(), w - dp(10f), h - dp(8f), paint)
    }

    // ---- 34: orbits ----

    private fun drawOrbitClock(canvas: Canvas, w: Float, h: Float) {
        val (h24, m, s) = wallTime()
        val main = ink()
        val cx = w / 2f
        val cy = h * 0.52f
        val r1 = min(w, h) * 0.40f
        val r2 = r1 * 0.68f
        val r3 = r1 * 0.40f
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = dimmed(main, 0.25f)
        canvas.drawCircle(cx, cy, r1, strokePaint)
        canvas.drawCircle(cx, cy, r2, strokePaint)
        canvas.drawCircle(cx, cy, r3, strokePaint)
        val tau = 2f * PI.toFloat()
        val angH = ((h24 % 12) + m / 60f) / 12f * tau - PI.toFloat() / 2f
        val angM = (m + s / 60f) / 60f * tau - PI.toFloat() / 2f
        val angS = s / 60f * tau - PI.toFloat() / 2f
        fillPaint.color = main
        canvas.drawCircle(cx + r1 * cos(angH), cy + r1 * sin(angH), dp(4.5f), fillPaint)
        fillPaint.color = 0xFF7DD3FC.toInt()
        canvas.drawCircle(cx + r2 * cos(angM), cy + r2 * sin(angM), dp(3.6f), fillPaint)
        fillPaint.color = 0xFFF472B6.toInt()
        canvas.drawCircle(cx + r3 * cos(angS), cy + r3 * sin(angS), dp(2.8f), fillPaint)
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfSans
        paint.textAlign = Paint.Align.CENTER
        paint.color = main
        paint.textSize = min(h * 0.14f, w * 0.10f)
        drawCenteredAt(canvas, hourMinuteText(), cx, cy, paint)
        paint.typeface = Typeface.MONOSPACE
        paint.textSize *= 0.45f
        paint.color = dimmed(main, 0.7f)
        drawCenteredAt(canvas, ":%02d".format(s), cx, cy + h * 0.11f, paint)
    }

    // ---- 35: day progress along the perimeter ----

    private fun drawPerimeter(canvas: Canvas, w: Float, h: Float) {
        val (h24, m, s) = wallTime()
        val main = ink()
        val inset = dp(10f)
        val rad = dp(14f)
        val frame = Path().apply {
            addRoundRect(inset, inset, w - inset, h - inset, rad, rad, Path.Direction.CW)
        }
        val pm = PathMeasure(frame, false)
        val total = pm.length
        strokePaint.color = dimmed(main, 0.22f)
        strokePaint.strokeWidth = dp(3f)
        strokePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(frame, strokePaint)
        val prog = ((h24 * 3600 + m * 60 + s) / 86400f).coerceIn(0f, 1f)
        if (prog > 0.001f) {
            val seg = Path()
            pm.getSegment(0f, total * prog, seg, true)
            strokePaint.color = main
            canvas.drawPath(seg, strokePaint)
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfSans
        paint.textAlign = Paint.Align.CENTER
        paint.color = main
        paint.textSize = min(h * 0.24f, w * 0.14f)
        drawCenteredAt(canvas, hourMinuteText(), w / 2f, h * 0.44f, paint)
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = min(h * 0.06f, w * 0.045f)
        paint.color = dimmed(main, 0.7f)
        drawCenteredAt(canvas, (prog * 100).toInt().toString() + "% суток", w / 2f, h * 0.62f, paint)
        drawCenteredAt(canvas, dateShortLine(), w / 2f, h * 0.72f, paint)
    }

    // ---- 36: outline type + filled seconds ----

    private fun drawOutlineType(canvas: Canvas, w: Float, h: Float) {
        val (_, _, s) = wallTime()
        val main = ink()
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfBlack
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(h * 0.38f, w * 0.21f)
        paint.color = main
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        drawCenteredAt(canvas, hourMinuteText(), w / 2f, h * 0.44f, paint)
        paint.style = Paint.Style.FILL
        paint.typeface = tfBold
        paint.textSize = min(h * 0.12f, w * 0.08f)
        drawCenteredAt(canvas, "%02d".format(s), w * 0.78f, h * 0.78f, paint)
        paint.textSize = min(h * 0.05f, w * 0.04f)
        paint.color = dimmed(main, 0.65f)
        drawCenteredAt(canvas, dateShortLine(), w * 0.75f, h * 0.88f, paint)
    }

    // ---- 37: dayline - light top / dark bottom ----

    private fun drawDayline(canvas: Canvas, w: Float, h: Float) {
        val mid = h * 0.5f
        fillPaint.color = 0xFFEDE7DA.toInt()
        canvas.drawRect(0f, 0f, w, mid, fillPaint)
        fillPaint.color = 0xFF171B20.toInt()
        canvas.drawRect(0f, mid, w, h, fillPaint)
        fillPaint.color = 0xFFFF5A34.toInt()
        canvas.drawRect(0f, mid - dp(0.75f), w, mid + dp(0.75f), fillPaint)
        val ts = min(w * 0.17f, h * 0.24f)
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfSans
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = ts
        canvas.save()
        canvas.clipRect(0f, 0f, w, mid)
        paint.color = 0xFF15191D.toInt()
        drawCenteredAt(canvas, hourMinuteText(), w / 2f, mid, paint)
        canvas.restore()
        canvas.save()
        canvas.clipRect(0f, mid, w, h)
        paint.color = 0xFFF5F7FB.toInt()
        drawCenteredAt(canvas, hourMinuteText(), w / 2f, mid, paint)
        canvas.restore()
        paint.textSize = ts * 0.22f
        paint.color = 0xB315191D.toInt()
        drawCenteredAt(canvas, dateShortLine(), w * 0.72f, h * 0.14f, paint)
    }

    // ---- 38: neumorphism (a light face - carries into the screensaver) ----

    private fun drawNeumo(canvas: Canvas, w: Float, h: Float) {
        val m = wallTime().second
        val bg = 0xFFDCE2E8.toInt()
        fillPaint.color = bg
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        val inset = dp(16f)
        val rad = dp(26f)
        val l = inset
        val t = inset
        val r0 = w - inset
        val b0 = h * 0.86f
        fillPaint.color = 0x50B8BDC2.toInt()
        canvas.drawRoundRect(l + dp(5f), t + dp(5f), r0 + dp(5f), b0 + dp(5f), rad, rad, fillPaint)
        fillPaint.color = 0x66FFFFFF.toInt()
        canvas.drawRoundRect(l - dp(5f), t - dp(5f), r0 - dp(5f), b0 - dp(5f), rad, rad, fillPaint)
        fillPaint.color = bg
        canvas.drawRoundRect(l, t, r0, b0, rad, rad, fillPaint)
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfSans
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(h * 0.055f, w * 0.045f)
        paint.color = 0xFF8A94A0.toInt()
        drawCenteredAt(canvas, dateShortLine(), w / 2f, t + h * 0.13f, paint)
        paint.textSize = min(h * 0.20f, w * 0.13f)
        paint.color = 0xFF2B333B.toInt()
        drawCenteredAt(canvas, hourMinuteText(), w / 2f, h * 0.40f, paint)
        val railL = l + dp(22f)
        val railR = r0 - dp(22f)
        val railY = b0 - dp(30f)
        val railH = dp(10f)
        fillPaint.color = 0xFFCFD5DB.toInt()
        canvas.drawRoundRect(railL, railY, railR, railY + railH, railH / 2f, railH / 2f, fillPaint)
        val frac = (m / 60f).coerceIn(0f, 1f)
        fillPaint.color = 0xFF65717F.toInt()
        if (frac > 0.02f) {
            canvas.drawRoundRect(railL, railY, railL + (railR - railL) * frac, railY + railH, railH / 2f, railH / 2f, fillPaint)
        }
    }

    // ---- 39: card deck ----

    private fun drawDeck(canvas: Canvas, w: Float, h: Float) {
        val parts = hourMinuteText().split(":")
        if (parts.size < 2) return
        val digits = (parts[0].padStart(2, '0') + parts[1].padStart(2, '0')).toCharArray()
        if (digits.size < 4) return
        val cw = w * 0.24f
        val ch = h * 0.66f
        val cyc = h * 0.50f
        val rots = floatArrayOf(-8f, -2.6f, 2.6f, 8f)
        for (i in 0 until 4) {
            val ccx = w * 0.5f + (i - 1.5f) * cw * 0.60f
            canvas.save()
            canvas.rotate(rots[i], ccx, cyc)
            fillPaint.color = 0x59000000.toInt()
            canvas.drawRoundRect(
                ccx - cw / 2f + dp(3f), cyc - ch / 2f + dp(4f),
                ccx + cw / 2f + dp(3f), cyc + ch / 2f + dp(4f), dp(8f), dp(8f), fillPaint
            )
            fillPaint.color = 0xFFF7F1E4.toInt()
            canvas.drawRoundRect(ccx - cw / 2f, cyc - ch / 2f, ccx + cw / 2f, cyc + ch / 2f, dp(8f), dp(8f), fillPaint)
            strokePaint.color = 0x33000000.toInt()
            strokePaint.strokeWidth = dp(1f)
            canvas.drawRoundRect(ccx - cw / 2f, cyc - ch / 2f, ccx + cw / 2f, cyc + ch / 2f, dp(8f), dp(8f), strokePaint)
            paint.reset()
            paint.isAntiAlias = true
            paint.typeface = tfSerif
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = ch * 0.42f
            paint.color = 0xFF1B1720.toInt()
            drawCenteredAt(canvas, digits[i].toString(), ccx, cyc, paint)
            canvas.restore()
        }
    }

    // ---- 40: odometer drums ----

    private fun drawOdometer(canvas: Canvas, w: Float, h: Float) {
        val parts = hourMinuteText().split(":")
        if (parts.size < 2) return
        val digits = (parts[0].padStart(2, '0') + parts[1].padStart(2, '0')).toCharArray()
        if (digits.size < 4) return
        val colonW = w * 0.08f
        val gap = dp(5f)
        val ww = (w - colonW - gap * 5f) / 4f
        val wh = h * 0.64f
        val top = (h - wh) / 2f
        var x = gap
        for (i in 0 until 4) {
            drawOdoWheel(canvas, x, top, ww, wh, digits[i] - '0')
            x += ww + gap
            if (i == 1) {
                paint.reset()
                paint.isAntiAlias = true
                paint.typeface = Typeface.MONOSPACE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = wh * 0.5f
                paint.color = 0xFFC9C9C9.toInt()
                drawCenteredAt(canvas, ":", x + colonW / 2f, h / 2f, paint)
                x += colonW
            }
        }
    }

    private fun drawOdoWheel(canvas: Canvas, l: Float, t: Float, ww: Float, wh: Float, cur: Int) {
        canvas.save()
        val r = dp(4f)
        val clip = Path().apply {
            addRoundRect(l, t, l + ww, t + wh, r, r, Path.Direction.CW)
        }
        canvas.clipPath(clip)
        fillPaint.color = 0xFF141414.toInt()
        canvas.drawRect(l, t, l + ww, t + wh, fillPaint)
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = Typeface.MONOSPACE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = wh * 0.52f
        paint.color = 0xFFF7EFE0.toInt()
        val cx = l + ww / 2f
        drawCenteredAt(canvas, ((cur + 9) % 10).toString(), cx, t - wh * 0.5f, paint)
        drawCenteredAt(canvas, cur.toString(), cx, t + wh * 0.5f, paint)
        drawCenteredAt(canvas, ((cur + 1) % 10).toString(), cx, t + wh * 1.5f, paint)
        fillPaint.color = 0x59000000.toInt()
        canvas.drawRect(l, t, l + ww, t + wh * 0.16f, fillPaint)
        canvas.drawRect(l, t + wh * 0.84f, l + ww, t + wh, fillPaint)
        fillPaint.color = 0x40FFFFFF.toInt()
        canvas.drawRect(l, t + wh * 0.16f, l + ww, t + wh * 0.16f + dp(1f), fillPaint)
        canvas.restore()
        fillPaint.color = 0xFF8A8983.toInt()
        canvas.drawRect(l, t + wh / 2f - dp(0.5f), l + ww, t + wh / 2f + dp(0.5f), fillPaint)
    }


    // ---------- styles 41-47: the rest of the concept sheet ----------

    // ---- 41: atomic lab; the orbit lines wobble with real mic sound ----

    private fun drawAtomicLab(canvas: Canvas, w: Float, h: Float) {
        MicLevel.poll()
        val level = MicLevel.level
        val phase = SystemClock.uptimeMillis() / 900f
        val cx = w / 2f
        val cy = h * 0.44f
        val base = min(w, h) * 0.33f
        val wave = MicLevel.wave
        val colors = intArrayOf(0xFF65E8FF.toInt(), 0xFFFF5DA2.toInt(), 0xFF9AF2FF.toInt())
        val paths = Array(3) { Path() }
        val n = 48
        for (o in 0 until 3) {
            val rot = o * (PI.toFloat() / 3f) + phase * 0.02f * (o + 1)
            val a = base * (1f + o * 0.04f)
            val b = base * 0.64f * (1f + o * 0.04f)
            val pt = Path()
            for (i in 0..n) {
                val t = i / n.toFloat() * 2f * PI.toFloat()
                var k = 1f + 0.022f * sin(t * 3f + phase + o) +
                    0.016f * sin(t * 5f - phase * 1.3f + o)
                if (level > 0.01f) {
                    val s = wave[(i * wave.size / (n + 1)) % wave.size]
                    k += s * (0.14f + 0.6f * level)
                }
                val x0 = cos(t) * a * k
                val y0 = sin(t) * b * k
                val x = cx + x0 * cos(rot) - y0 * sin(rot)
                val y = cy + x0 * sin(rot) + y0 * cos(rot)
                if (i == 0) pt.moveTo(x, y) else pt.lineTo(x, y)
            }
            pt.close()
            paths[o] = pt
        }
        for (o in 0 until 3) {
            strokePaint.color = colors[o]
            strokePaint.strokeWidth = dp(1.3f)
            canvas.drawPath(paths[o], strokePaint)
        }
        if (level > 0.01f) {
            fillPaint.shader = RadialGradient(
                cx, cy, dp(18f) + dp(40f) * level,
                0x889FF0FF.toInt(), 0x00000000, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, dp(18f) + dp(40f) * level, fillPaint)
            fillPaint.shader = null
        }
        fillPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, dp(3.2f), fillPaint)

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfSans
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = min(h * 0.11f, w * 0.085f)
        paint.color = ink()
        canvas.drawText(hourMinuteText(), dp(10f), h - dp(12f), paint)
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = min(h * 0.04f, w * 0.032f)
        paint.color = dimmed(ink(), 0.7f)
        canvas.drawText(dateShortLine(), dp(10f), h - dp(12f) - h * 0.12f, paint)

        postInvalidateDelayed(50L)
    }

    // ---- 42: real orrery; heliocentric longitudes from JPL elements ----

    private class PlanetEl(
        val name: String,
        val color: Int,
        val sizeDp: Float,
        val a: Double,
        val e: Double,
        val l0: Double,
        val w0: Double,
        val dL: Double,
        val dW: Double
    )

    private val planets = arrayOf(
        PlanetEl("Меркурий", 0xFFCFC7B8.toInt(), 1.5f, 0.38709927, 0.20563593, 252.25032350, 77.45779628, 149472.67411175, 0.16047689),
        PlanetEl("Венера", 0xFFF0D9A0.toInt(), 2.0f, 0.72333566, 0.00677672, 181.97909950, 131.60246718, 58517.81538729, 0.00268329),
        PlanetEl("Земля", 0xFF5FB8FF.toInt(), 2.1f, 1.00000261, 0.01671123, 100.46457166, 102.93768193, 35999.37244981, 0.32327364),
        PlanetEl("Марс", 0xFFE2704A.toInt(), 1.7f, 1.52371034, 0.09339410, -4.55343205, -23.94362959, 19140.30268499, 0.44441088),
        PlanetEl("Юпитер", 0xFFE0B47A.toInt(), 3.6f, 5.20288700, 0.04838624, 34.39644051, 14.72847983, 3034.74612775, 0.21252668),
        PlanetEl("Сатурн", 0xFFE8D59A.toInt(), 3.3f, 9.53667594, 0.05386179, 49.95424423, 92.59887831, 1222.49362201, -0.41897216),
        PlanetEl("Уран", 0xFF9FE8F0.toInt(), 2.6f, 19.18916464, 0.04725744, 313.23810451, 170.95427630, 428.48202785, 0.40805281),
        PlanetEl("Нептун", 0xFF7F9CFF.toInt(), 2.5f, 30.06992276, 0.00859048, -55.12002969, 44.96476227, 218.45945325, -0.32241464)
    )

    /** Heliocentric longitude (deg) and scaled orbit radius for a planet. */
    private fun planetPos(pl: PlanetEl, nowMs: Long): Pair<Double, Double> {
        val t = (nowMs / 86400000.0 + 2440587.5 - 2451545.0) / 36525.0
        val l = pl.l0 + pl.dL * t
        val w = pl.w0 + pl.dW * t
        var m = (l - w) % 360.0
        if (m < 0) m += 360.0
        var e = Math.toRadians(m)
        val er = Math.toRadians(m)
        for (k in 0 until 8) {
            e -= (e - pl.e * sin(e) - er) / (1 - pl.e * cos(e))
        }
        val nu = 2.0 * atan2(
            sqrt(1 + pl.e) * sin(e / 2.0),
            sqrt(1 - pl.e) * cos(e / 2.0)
        )
        val r = pl.a * (1 - pl.e * cos(e))
        val lon = (Math.toDegrees(nu) + w) % 360.0
        val rf = Math.pow(min(r, 30.07) / 30.07, 0.35) * 0.92
        return lon to rf
    }

    private fun drawRealOrrery(canvas: Canvas, w: Float, h: Float) {
        val now = System.currentTimeMillis()
        val cx = w / 2f
        val cy = h * 0.50f
        val rr = min(w, h) * 0.44f
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = 0x2E8FB6E0.toInt()
        for (pl in planets) {
            val (_, rf) = planetPos(pl, now)
            canvas.drawCircle(cx, cy, rr * rf.toFloat(), strokePaint)
        }
        fillPaint.shader = RadialGradient(cx, cy, dp(10f), 0xAAFFC65C.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, dp(10f), fillPaint)
        fillPaint.shader = null
        fillPaint.color = 0xFFFFD36A.toInt()
        canvas.drawCircle(cx, cy, dp(3f), fillPaint)

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = Typeface.MONOSPACE
        paint.textAlign = Paint.Align.LEFT
        val dotDp = dp(1f)
        for (pl in planets) {
            val (lon, rf) = planetPos(pl, now)
            val ang = Math.toRadians(lon - 90.0)
            val px = cx + (cos(ang.toFloat()) * rr * rf.toFloat())
            val py = cy + (sin(ang.toFloat()) * rr * rf.toFloat())
            fillPaint.color = pl.color
            canvas.drawCircle(px, py, pl.sizeDp * dotDp, fillPaint)
            paint.textSize = min(h * 0.028f, w * 0.024f)
            paint.color = 0xC8D6E4F5.toInt()
            canvas.drawText(pl.name, px + dp(6f), py + dp(2.5f), paint)
        }

        paint.typeface = tfSans
        paint.textSize = min(h * 0.11f, w * 0.085f)
        paint.color = ink()
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(hourMinuteText(), dp(10f), h - dp(14f), paint)
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = min(h * 0.035f, w * 0.028f)
        paint.color = dimmed(ink(), 0.65f)
        canvas.drawText("МЕСТНОЕ ВРЕМЯ", dp(10f), h - dp(14f) - h * 0.115f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("долготы JPL", w - dp(10f), h - dp(8f), paint)
    }

    // ---- 43: liquid glass; slow flowing gradient under a frosted lens ----

    private fun drawBlob(
        canvas: Canvas, w: Float, h: Float,
        cx: Float, cy: Float, rad: Float, color: Int
    ) {
        fillPaint.shader = RadialGradient(cx, cy, rad, color, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad, fillPaint)
        fillPaint.shader = null
    }

    private fun drawLiquidGlass(canvas: Canvas, w: Float, h: Float) {
        val t = SystemClock.uptimeMillis() / 1000f
        fillPaint.shader = LinearGradient(
            0f, 0f, w, h,
            0xFF061119.toInt(), 0xFF3A1147.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        fillPaint.shader = null
        drawBlob(canvas, w, h, w * (0.15f + 0.12f * sin(t * 0.21f)), h * (0.25f + 0.10f * cos(t * 0.17f)), w * 0.55f, 0xAA43E6CF.toInt())
        drawBlob(canvas, w, h, w * (0.85f + 0.10f * cos(t * 0.15f)), h * (0.30f + 0.14f * sin(t * 0.19f)), w * 0.55f, 0xAA7357FF.toInt())
        drawBlob(canvas, w, h, w * (0.55f + 0.15f * sin(t * 0.13f + 2f)), h * (0.88f + 0.08f * cos(t * 0.16f)), w * 0.60f, 0x99FF6DBE.toInt())

        val pl = w * 0.11f
        val pt = h * 0.18f
        val pr = w - pl
        val pb = h - pt
        val rad = dp(26f)
        fillPaint.color = 0x18FFFFFF.toInt()
        canvas.drawRoundRect(pl, pt, pr, pb, rad, rad, fillPaint)
        strokePaint.color = 0x45FFFFFF.toInt()
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(pl, pt, pr, pb, rad, rad, strokePaint)
        fillPaint.color = 0x50FFFFFF.toInt()
        canvas.drawRoundRect(pl + dp(2f), pt + dp(2f), pr - dp(2f), pt + dp(5f), dp(2f), dp(2f), fillPaint)

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = Typeface.MONOSPACE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(h * 0.055f, w * 0.042f)
        paint.color = 0xB8FFFFFF.toInt()
        drawCenteredAt(canvas, dateShortLine(), w / 2f, pt + h * 0.10f, paint)
        paint.typeface = tfSans
        paint.textSize = min(h * 0.20f, w * 0.13f)
        paint.color = 0xFFF5F7FB.toInt()
        drawCenteredAt(canvas, hourMinuteText(), w / 2f, h * 0.55f, paint)

        postInvalidateDelayed(100L)
    }

    // ---- 44: gradient mesh; slow color field + huge type ----

    private fun drawGradientMesh(canvas: Canvas, w: Float, h: Float) {
        val t = SystemClock.uptimeMillis() / 1000f
        fillPaint.shader = LinearGradient(
            0f, 0f, w, h,
            0xFF1A1530.toInt(), 0xFF12303A.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        fillPaint.shader = null
        drawBlob(canvas, w, h, w * (0.15f + 0.10f * sin(t * 0.19f)), h * (0.20f + 0.08f * cos(t * 0.15f)), w * 0.6f, 0xE5FF6CAB.toInt())
        drawBlob(canvas, w, h, w * (0.85f + 0.08f * cos(t * 0.17f)), h * (0.25f + 0.10f * sin(t * 0.13f)), w * 0.6f, 0xE57367F0.toInt())
        drawBlob(canvas, w, h, w * (0.55f + 0.12f * sin(t * 0.11f + 3f)), h * (0.88f + 0.07f * cos(t * 0.14f)), w * 0.65f, 0xD92DD4BF.toInt())

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfBlack
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = min(w * 0.17f, h * 0.24f)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(hourMinuteText(), dp(10f), h * 0.90f, paint)
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = min(h * 0.042f, w * 0.034f)
        paint.textAlign = Paint.Align.RIGHT
        paint.color = 0xC8FFFFFF.toInt()
        canvas.drawText(dateShortLine(), w - dp(10f), h * 0.10f, paint)

        postInvalidateDelayed(100L)
    }

    // ---- 45: frosted minimal panel ----

    private fun drawFrosted(canvas: Canvas, w: Float, h: Float) {
        val t = SystemClock.uptimeMillis() / 1000f
        fillPaint.shader = LinearGradient(
            0f, 0f, w, h,
            0xFF0B1020.toInt(), 0xFF101A33.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        fillPaint.shader = null
        drawBlob(canvas, w, h, w * (0.2f + 0.1f * sin(t * 0.18f)), h * 0.3f, w * 0.5f, 0x887367F0.toInt())
        drawBlob(canvas, w, h, w * (0.8f + 0.1f * cos(t * 0.14f)), h * 0.75f, w * 0.5f, 0x882DD4BF.toInt())

        val pl = w * 0.08f
        val pt = h * 0.14f
        val pr = w - pl
        val pb = h - pt
        val rad = dp(24f)
        fillPaint.color = 0x35FFFFFF.toInt()
        canvas.drawRoundRect(pl, pt, pr, pb, rad, rad, fillPaint)
        strokePaint.color = 0x77FFFFFF.toInt()
        strokePaint.strokeWidth = dp(1.2f)
        canvas.drawRoundRect(pl, pt, pr, pb, rad, rad, strokePaint)

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfSans
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(h * 0.20f, w * 0.13f)
        paint.color = 0xFF0F1822.toInt()
        drawCenteredAt(canvas, hourMinuteText(), w / 2f, h * 0.46f, paint)
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = min(h * 0.045f, w * 0.036f)
        paint.color = 0xB80F1822.toInt()
        drawCenteredAt(canvas, dateShortLine(), w / 2f, h * 0.68f, paint)

        // seconds dot with a ring that grows through the second
        val secMs = (System.currentTimeMillis() % 1000L) / 1000f
        val dx = pr - dp(22f)
        val dy = h / 2f
        fillPaint.color = 0xFF0F1822.toInt()
        canvas.drawCircle(dx, dy, dp(5f), fillPaint)
        strokePaint.color = 0x260F1822.toInt()
        strokePaint.strokeWidth = dp(2f)
        canvas.drawCircle(dx, dy, dp(5f) + dp(14f) * secMs, strokePaint)

        postInvalidateDelayed(100L)
    }

    // ---- 46: Swiss railway dial; the seconds hand waits at 12 ----

    private fun drawRailway(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 0.52f
        val r = min(w, h) * 0.42f
        fillPaint.color = 0xFFF1F0EA.toInt()
        canvas.drawCircle(cx, cy, r, fillPaint)
        strokePaint.color = 0x22000000.toInt()
        strokePaint.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, r, strokePaint)

        val tau = 2f * PI.toFloat()
        for (i in 0 until 60) {
            val a = i * tau / 60f - PI.toFloat() / 2f
            val len = if (i % 5 == 0) r * 0.16f else r * 0.07f
            val wd = if (i % 5 == 0) dp(3.2f) else dp(1.2f)
            val sinA = sin(a)
            val cosA = cos(a)
            val x1 = cx + cosA * (r * 0.90f)
            val y1 = cy + sinA * (r * 0.90f)
            val x2 = cx + cosA * (r * 0.90f - len)
            val y2 = cy + sinA * (r * 0.90f - len)
            fillPaint.color = 0xFF111111.toInt()
            strokePaint.color = 0xFF111111.toInt()
            strokePaint.strokeWidth = wd
            strokePaint.strokeCap = Paint.Cap.BUTT
            canvas.drawLine(x1, y1, x2, y2, strokePaint)
        }

        val (h24, m, _) = wallTime()
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = 0xFF111111.toInt()
        val angH = ((h24 % 12) + m / 60f) / 12f * tau - PI.toFloat() / 2f
        strokePaint.strokeWidth = dp(5f)
        canvas.drawLine(
            cx, cy,
            cx + cos(angH) * r * 0.50f, cy + sin(angH) * r * 0.50f, strokePaint
        )
        val angM = m / 60f * tau - PI.toFloat() / 2f
        strokePaint.strokeWidth = dp(4f)
        canvas.drawLine(
            cx, cy,
            cx + cos(angM) * r * 0.78f, cy + sin(angM) * r * 0.78f, strokePaint
        )

        // SBB behaviour: ~58.5 s per revolution, then a pause at 12 o'clock
        val secF = (System.currentTimeMillis() % 60000L) / 1000f
        val sweep = min(secF, 58.5f) / 58.5f
        val angS = sweep * tau - PI.toFloat() / 2f
        strokePaint.color = 0xFFE31D2B.toInt()
        strokePaint.strokeWidth = dp(2f)
        canvas.drawLine(
            cx - cos(angS) * r * 0.18f, cy - sin(angS) * r * 0.18f,
            cx + cos(angS) * r * 0.84f, cy + sin(angS) * r * 0.84f, strokePaint
        )
        fillPaint.color = 0xFFE31D2B.toInt()
        canvas.drawCircle(
            cx + cos(angS) * r * 0.62f, cy + sin(angS) * r * 0.62f,
            r * 0.065f, fillPaint
        )
        fillPaint.color = 0xFF111111.toInt()
        canvas.drawCircle(cx, cy, dp(2.5f), fillPaint)

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = Typeface.MONOSPACE
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = min(h * 0.045f, w * 0.036f)
        paint.color = 0xFF222222.toInt()
        canvas.drawText(dateShortLine(), cx + r * 0.72f, cy + r * 0.72f, paint)

        postInvalidateDelayed(100L)
    }

    // ---- 47: Braun-style functional dial ----

    private fun drawBraun(canvas: Canvas, w: Float, h: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = tfBold
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = min(h * 0.05f, w * 0.04f)
        paint.letterSpacing = 0.14f
        paint.color = 0xFF171715.toInt()
        canvas.drawText("FUNCTION / TIME", dp(10f), h * 0.10f, paint)
        paint.letterSpacing = 0f

        val cx = w / 2f
        val cy = h * 0.54f
        val r = min(w, h) * 0.38f
        fillPaint.color = 0xFFE8E4D8.toInt()
        canvas.drawCircle(cx, cy, r, fillPaint)
        strokePaint.color = 0x22000000.toInt()
        strokePaint.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, r, strokePaint)

        val tau = 2f * PI.toFloat()
        for (i in 0 until 60) {
            val a = i * tau / 60f - PI.toFloat() / 2f
            val hour = i % 5 == 0
            val len = if (hour) r * 0.14f else r * 0.06f
            val wd = if (hour) dp(2.6f) else dp(1f)
            val sinA = sin(a)
            val cosA = cos(a)
            strokePaint.color = 0xFF191919.toInt()
            strokePaint.strokeWidth = wd
            strokePaint.strokeCap = Paint.Cap.BUTT
            canvas.drawLine(
                cx + cosA * (r * 0.92f), cy + sinA * (r * 0.92f),
                cx + cosA * (r * 0.92f - len), cy + sinA * (r * 0.92f - len), strokePaint
            )
        }

        val (h24, m, s) = wallTime()
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = 0xFF191919.toInt()
        val angH = ((h24 % 12) + m / 60f) / 12f * tau - PI.toFloat() / 2f
        strokePaint.strokeWidth = dp(4.5f)
        canvas.drawLine(cx, cy, cx + cos(angH) * r * 0.48f, cy + sin(angH) * r * 0.48f, strokePaint)
        val angM = m / 60f * tau - PI.toFloat() / 2f
        strokePaint.strokeWidth = dp(3.5f)
        canvas.drawLine(cx, cy, cx + cos(angM) * r * 0.74f, cy + sin(angM) * r * 0.74f, strokePaint)

        val secF = s + (System.currentTimeMillis() % 1000L) / 1000f
        val angS = secF / 60f * tau - PI.toFloat() / 2f
        strokePaint.color = 0xFFF7B600.toInt()
        strokePaint.strokeWidth = dp(1.8f)
        canvas.drawLine(
            cx - cos(angS) * r * 0.16f, cy - sin(angS) * r * 0.16f,
            cx + cos(angS) * r * 0.80f, cy + sin(angS) * r * 0.80f, strokePaint
        )
        fillPaint.color = 0xFF191919.toInt()
        canvas.drawCircle(cx, cy, dp(2.5f), fillPaint)

        val dy = cy + r * 0.70f
        strokePaint.color = 0xFF1D1D1B.toInt()
        strokePaint.strokeWidth = dp(1.6f)
        canvas.drawLine(cx + r * 0.30f, dy - dp(8f), cx + r * 0.92f, dy - dp(8f), strokePaint)
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = Typeface.MONOSPACE
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = min(h * 0.045f, w * 0.036f)
        paint.color = 0xFF1D1D1B.toInt()
        canvas.drawText(dateShortLine(), cx + r * 0.92f, dy + dp(4f), paint)

        postInvalidateDelayed(100L)
    }

}

/** Microphone meter for style 41. Started only while that face is shown,
 *  only when RECORD_AUDIO was granted; silent no-op otherwise. */
private object MicLevel {
    private var rec: android.media.AudioRecord? = null
    private val buf = ShortArray(1024)
    val wave = FloatArray(64)
    var level = 0f
        private set
    private var peak = 0f

    fun start(ctx: android.content.Context) {
        if (rec != null) return
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val sr = 16000
            val minBuf = android.media.AudioRecord.getMinBufferSize(
                sr, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            val r = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sr, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 4096) * 2
            )
            if (r.state != android.media.AudioRecord.STATE_INITIALIZED) {
                r.release(); return
            }
            r.startRecording()
            rec = r
        } catch (_: Throwable) {
            rec?.release()
            rec = null
        }
    }

    fun stop() {
        rec?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        rec = null
        level = 0f
    }

    fun poll() {
        val r = rec ?: return
        val n = try { r.read(buf, 0, buf.size) } catch (_: Exception) { 0 }
        java.util.Arrays.fill(wave, 0f)
        if (n <= 0) return
        var sum = 0.0
        var count = 0
        var k = 0
        while (k < n) {
            val v = buf[k] / 32768.0
            sum += v * v
            count++
            val slot = (k * wave.size / n).coerceIn(0, wave.size - 1)
            val av = kotlin.math.abs(v).toFloat()
            if (av > wave[slot]) wave[slot] = av
            k += 2
        }
        val rms = kotlin.math.sqrt(sum / count.toDouble()).toFloat()
        peak = maxOf(rms, peak * 0.995f)
        val ref = maxOf(0.02f, peak * 0.55f)
        level = (rms / ref).coerceIn(0f, 1f)
    }
}
