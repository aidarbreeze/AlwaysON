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
 *  0 - NORMAL : plain light text (the default look).
 *  1 - OUTLINE: the digit shapes drawn as hollow contours; the line thickness
 *               ("сколько пикселей закрашивать") is chosen by the user.
 *  2 - DOTS   : "comic" dot-matrix digits — each digit is built from many
 *               small dots with gaps between them.
 *  3 - FLIP   : an old "откидные часы" flip-clock — each digit is split into
 *               a top and bottom half with a seam, like a mechanical flipper.
 *
 * The view is sized to fill its column horizontally and picks the biggest
 * legible digit size that still fits, then centres the time.
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

    /** Biggest digit text size (px) for one line that fits [availW], capped. */
    private fun fitTextSize(availW: Float, capPx: Float): Float {
        paint.textSize = 1000f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val w1000 = paint.measureText(timeText)
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

        val height = if (style() == 2) textSize else textHeight(textSize) + dp(6f)
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
            2 -> drawDots(canvas, w, h)
            3 -> drawFlip(canvas, w, h)
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
        dimPaint.color = Color.parseColor("#33FFFFFF")
        dimPaint.strokeWidth = dp(1f)
        canvas.drawLine(0f, midY, w, midY, dimPaint)
    }

    // ---------- style 2: dot-matrix "comic" digits ----------

    private fun drawDots(canvas: Canvas, w: Float, h: Float) {
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
                drawDotGlyph(canvas, x, yCentre, digitH, c.char)
                x += digitH * 0.62f * 1.06f
            } else {
                // colon -> two dots at vertical middle
                val r = digitH * 0.09f
                val cx = x + digitH * 0.62f * 0.25f
                canvas.drawCircle(cx, yCentre - digitH * 0.22f, r, paint)
                canvas.drawCircle(cx, yCentre + digitH * 0.22f, r, paint)
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

    /** Draws one digit glyph (5 wide x 7 tall) centred at yCentre. */
    private fun drawDotGlyph(
        canvas: Canvas,
        left: Float,
        yCentre: Float,
        digitH: Float,
        ch: Char
    ) {
        val pat = glyph(ch) ?: return
        val dw = digitH * 0.62f
        val rows = 7
        val cols = 5
        val rowSpacing = digitH / (rows + 1f)
        val colSpacing = dw / (cols + 1f)
        val r = (rowSpacing.coerceAtMost(colSpacing)) * 0.34f
        val top = yCentre - digitH * 0.5f
        for (row in 0 until rows) {
            val bits = pat[row]
            for (col in 0 until cols) {
                if (((bits shr col) and 1) != 0) {
                    val cx = left + (col + 1) * colSpacing
                    val cy = top + (row + 1) * rowSpacing
                    canvas.drawCircle(cx, cy, r, paint)
                }
            }
        }
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        w: Float,
        h: Float,
        p: Paint
    ) {
        val fm = p.fontMetrics
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
