package com.aidarbreeze.alwayson.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** What is playing right now (title, artist, playing state, optional art). */
data class NowPlaying(
    val title: String,
    val artist: String,
    val playing: Boolean,
    val art: Bitmap? = null
)

/**
 * Single global snapshot of the current media, shared between the
 * notification listener (which fills it) and every StandBy screen (which
 * reads it). Using one cache means any overlay / preview / screen-saver
 * instance sees the same now-playing card.
 */
object NowPlayingCache {

    @Volatile
    var current: NowPlaying? = null

    @Volatile
    var updatedAt: Long = 0L

    // The standby ticker polls the art every second; without a memo the same
    // large cover would be re-scaled (a fresh bitmap + a full resample) every
    // second. Guarded by [memoLock]: downscale() is called both from the UI
    // thread and from the notification-listener thread.
    private val memoLock = Any()
    private var memoSrc: Bitmap? = null
    private var memoScaled: Bitmap? = null

    /**
     * Downscale arbitrary album art to a small thumbnail (max [maxPx] on the
     * long side) so the StandBy card never holds a large bitmap in memory.
     * Returns null on failure.
     */
    fun downscale(src: Bitmap?, maxPx: Int = 96): Bitmap? {
        if (src == null) return null
        synchronized(memoLock) {
            if (src === memoSrc && memoScaled != null) return memoScaled
        }
        val out = try {
            val side = maxOf(src.width, src.height)
            if (side <= maxPx) src
            else {
                val f = maxPx.toFloat() / side
                Bitmap.createScaledBitmap(
                    src,
                    (src.width * f).toInt().coerceAtLeast(1),
                    (src.height * f).toInt().coerceAtLeast(1),
                    true
                )
            }
        } catch (_: Throwable) {
            // Even an OOM while scaling must not kill the caller thread.
            null
        }
        synchronized(memoLock) {
            memoSrc = src
            memoScaled = out
        }
        return out
    }
}

/**
 * Small now-playing album-art tile for the StandBy card: the bitmap is
 * center-cropped to the square, then desaturated to grayscale so it stays
 * monochrome on the OLED screen. Draws nothing while no art is set.
 */
class MediaArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var art: Bitmap? = null

    // Luminance matrix: r=g=b = 0.299r + 0.587g + 0.114b (classic grayscale).
    private val desat = Paint().apply {
        isFilterBitmap = true
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        )
    }

    fun setArt(bmp: Bitmap?) {
        if (bmp === art) return
        art = bmp
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = art ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f || b.width <= 0 || b.height <= 0) return

        val side = minOf(w, h)
        val sw = b.width.toFloat()
        val sh = b.height.toFloat()
        val scale = maxOf(side / sw, side / sh)
        val dw = sw * scale
        val dh = sh * scale
        val dx = (w - dw) / 2f
        val dy = (h - dh) / 2f
        canvas.save()
        canvas.clipRect((w - side) / 2f, (h - side) / 2f, (w - side) / 2f + side, (h - side) / 2f + side)
        canvas.drawBitmap(b, null, RectF(dx, dy, dx + dw, dy + dh), desat)
        canvas.restore()
    }
}
