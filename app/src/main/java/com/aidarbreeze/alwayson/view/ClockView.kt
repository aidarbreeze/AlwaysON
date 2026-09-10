package com.aidarbreeze.alwayson.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.aidarbreeze.alwayson.Prefs

/**
 * Big clock face that can render the time in several visual styles:
 *
 *  0 - NORMAL  : plain light text (the default look).
 *  1 - OUTLINE : the digit shapes drawn as hollow contours; the line thickness
 *                is chosen by the user.
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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setTime(text: String) {
        if (text != timeText) {
            timeText = text
            invalidate()
        }
    }

    fun refresh() {
        invalidate()
    }

    private fun style(): Int = Prefs.clockStyle(context)

    private fun thicknessPx(): Float {
        val dp = Prefs.clockThickness(context).toFloat()
        return dp * resources.displayMetrics.density
    }

    /** Biggest digit text size (px) for one line that fits [availW], capped.
     *  The width is measured on a width-stable reference (every digit ->
     *  "8", the widest glyph) so the chosen size — and therefore the clock
     *  width — never jumps when the digits change (e.g. 11:11 -> 12:45). */
    private fun fitTextSize(availW: Float, capPx: Float): Float {
        paint.textSize = 1000f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val ref = timeText.map { if (it.isDigit()) '8' else it }.joinToString("")
        val w1000 = paint.measureText(ref)
        if (w1000 <= 0f) return capPx
        val fromWidth = (availW * 1000f / w1000) * 0.97f
        return fromWidth.coerceAtMost(capPx)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = android.view.View.MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val density = resources.displayMetrics.density
        // Cap roughly like the previous large clock so the digits never blow up
        // into a huge tower on very short strings.
        val capPx = 118f * density
        val pad = dp(4f)
        val textSize = fitTextSize(availW - pad * 2f, capPx)

        // Grid-glyph styles (dots, seven-seg LED, square matrix) are sized by
        // the view height directly; the font-drawn styles need font metrics.
        val height = when (style()) {
            2, 4, 9 -> textSize
            else -> textHeight(textSize) + dp(6f)
        }
        setMeasuredDimension(availW.toInt(), height.toInt())
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

        when (style()) {
            1 -> drawOutline(canvas, w, h)
            2 -> drawDotGrid(canvas, w, h, square = false)
            3 -> drawFlip(canvas, w, h)
            4 -> drawSevenSegment(canvas, w, h)
            5 -> drawNeon(canvas, w, h)
            6 -> drawBlocks(canvas, w, h)
            7 -> drawSerif(canvas, w, h)
            8 -> drawItalic(canvas, w, h)
            9 -> drawDotGrid(canvas, w, h, square = true)
            10 -> drawPlain(canvas, w, h, Typeface.create("sans-serif", Typeface.NORMAL))
            11 -> drawPlain(canvas, w, h, Typeface.create("sans-serif", Typeface.BOLD))
            12 -> drawPlain(canvas, w, h, Typeface.MONOSPACE)
            13 -> drawSoftRounded(canvas, w, h)
            else -> drawNormal(canvas, w, h)
        }
    }

    // ---------- style 0: plain text ----------

    private fun drawNormal(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w, cap())
        val tf = Typeface.create("sans-serif-light", Typeface.NORMAL)
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tf
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        drawCenteredText(canvas, timeText, w, h, paint)
    }

    // ---------- style 1: hollow outline digits ----------

    private fun drawOutline(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w, cap())
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = thicknessPx()
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        drawCenteredText(canvas, timeText, w, h, paint)
    }

    // ---------- style 3: flip-clock ----------

    private fun drawFlip(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w, cap())
        val tf = Typeface.create("sans-serif", Typeface.BOLD)
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tf
        paint.color = Color.WHITE
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
        paint.color = Color.WHITE
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

        val seg = Paint(Paint.ANTI_ALIAS_FLAG)
        seg.style = Paint.Style.STROKE
        seg.strokeCap = Paint.Cap.ROUND
        seg.color = Color.WHITE

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
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        // Bloom: draw several translucent copies shrinking from a wide faint
        // halo down to a crisp bright core.
        val layers = 16
        for (i in 0 until layers) {
            val f = i / (layers - 1f) // 0 = outermost, 1 = core
            paint.textSize = core * (1.18f - 0.18f * f)
            val alpha = (8 + (255 - 8) * f * f).toInt().coerceIn(0, 255)
            paint.color = Color.argb(alpha, 255, 255, 255)
            val fm = paint.fontMetrics
            val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(timeText, w / 2f, baseline, paint)
        }
    }

    // ---------- style 6: minimal rounded "chips" ----------

    private fun drawBlocks(canvas: Canvas, w: Float, h: Float) {
        val capPx = cap()
        val digitPad = dp(11f)
        val tf = Typeface.create("sans-serif", Typeface.BOLD)
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
        while (size > 6f && total(size) > w) size -= 2f
        paint.textSize = size
        val fm = paint.fontMetrics
        val textH = fm.bottom - fm.top
        val chipH = textH + dp(12f)
        val yTop = h / 2f - chipH / 2f
        val baseline = yTop + chipH / 2f - (fm.ascent + fm.descent) / 2f
        val radius = dp(10f)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.style = Paint.Style.FILL
        bg.color = 0x26FFFFFF

        var x = (w - total(size)) / 2f
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
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
        val tf = Typeface.create("serif", Typeface.NORMAL)
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tf
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        drawCenteredText(canvas, timeText, w, h, paint)
    }

    // ---------- style 8: heavy slanted italic ----------

    private fun drawItalic(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w, cap())
        val tf = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tf
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        drawCenteredText(canvas, timeText, w, h, paint)
    }

    // ---------- styles 10/11/12: plain digits in a chosen typeface ----------
    // 10 Classic Digital (regular sans), 11 Bold Digital (bold sans),
    // 12 Monospaced (fixed-width, the clock never shifts as digits change).

    private fun drawPlain(canvas: Canvas, w: Float, h: Float, tf: Typeface) {
        var size = fitTextSize(w, cap())
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = tf
        // The base fit was computed on a BOLD sans reference; re-check on the
        // actual typeface (e.g. monospace digits are wider) and shrink if
        // needed so the line always fits the column.
        val ref = timeText.map { if (it.isDigit()) '8' else it }.joinToString("")
        val measured = paint.measureText(ref)
        val avail = w - dp(4f)
        if (measured > avail && measured > 0f) size *= avail / measured
        paint.textSize = size
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        drawCenteredText(canvas, timeText, w, h, paint)
    }

    // ---------- style 13: soft rounded ("pillow") digits ----------

    private fun drawSoftRounded(canvas: Canvas, w: Float, h: Float) {
        val size = fitTextSize(w, cap())
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.color = Color.WHITE
        // FILL_AND_STROKE with round joins/caps rounds the corners of the
        // glyphs into a soft, rounded look.
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = (size * 0.10f).coerceIn(2f, dp(10f))
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        drawCenteredText(canvas, timeText, w, h, paint)
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

    private fun cap(): Float = 118f * resources.displayMetrics.density

    // Standard 5x7 (columns packed in low bits, LSB = leftmost) glyphs.
    private fun glyph(ch: Char): Array<Int>? {
        return when (ch) {
            '0' -> arrayOf(
                0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110
            )
            '1' -> arrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110)
            '2' -> arrayOf(
                0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111
            )
            '3' -> arrayOf(
                0b11110, 0b00001, 0b00001, 0b01110, 0b00001, 0b00001, 0b11110
            )
            '4' -> arrayOf(
                0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010
            )
            '5' -> arrayOf(
                0b11111, 0b10000, 0b10000, 0b11110, 0b00001, 0b00001, 0b11110
            )
            '6' -> arrayOf(
                0b01110, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110
            )
            '7' -> arrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000)
            '8' -> arrayOf(
                0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110
            )
            '9' -> arrayOf(
                0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00001, 0b01110
            )
            else -> null
        }
    }
}
