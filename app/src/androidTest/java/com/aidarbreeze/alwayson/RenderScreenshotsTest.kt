package com.aidarbreeze.alwayson

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aidarbreeze.alwayson.stock.Candle
import com.aidarbreeze.alwayson.view.ClockView
import com.aidarbreeze.alwayson.view.MonthCalendarView
import com.aidarbreeze.alwayson.view.StockChartView
import com.aidarbreeze.alwayson.view.WeatherPanelView
import com.aidarbreeze.alwayson.weather.WeatherDay
import com.aidarbreeze.alwayson.weather.WeatherHour
import com.aidarbreeze.alwayson.weather.WeatherInfo
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Render verification: draws every custom view in every style at portrait
 * and landscape panel sizes and saves labeled contact sheets + full-size
 * PNGs, plus real MainActivity screenshots in both orientations.
 * Nothing is asserted — a human reviews the pictures for layout bugs.
 */
@RunWith(AndroidJUnit4::class)
class RenderScreenshotsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val outDir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
    }

    /** Measure (width EXACT, height AT_MOST — like the real wrap_content
     *  panels, so onMeasure bugs show) and draw the view to a bitmap. */
    private fun render(view: View, wPx: Int, hMaxPx: Int): Bitmap {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hMaxPx, View.MeasureSpec.AT_MOST)
        )
        val w = view.measuredWidth.coerceAtLeast(1)
        val h = view.measuredHeight.coerceAtLeast(1)
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        view.draw(c)
        return bmp
    }

    private fun save(bmp: Bitmap, name: String) {
        File(outDir, "$name.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** Labeled contact sheet from (label, bitmap) cells; thumbs scaled to
     *  [thumbW]. Cells are recycled after composing. */
    private fun sheet(cells: List<Pair<String, Bitmap>>, cols: Int, thumbW: Int, name: String) {
        val rows = ceil(cells.size / cols.toDouble()).toInt().coerceAtLeast(1)
        val labelH = 44
        val pad = 12
        val scales = cells.map { thumbW.toFloat() / it.second.width.toFloat() }
        val th = cells.mapIndexed { i, cell ->
            (cell.second.height * scales[i]).toInt().coerceAtLeast(1)
        }
        val rowH = IntArray(rows) { r ->
            var m = 0
            for (c in 0 until cols) {
                val i = r * cols + c
                if (i < cells.size) m = max(m, th[i])
            }
            m
        }
        val sheetW = cols * (thumbW + pad) + pad
        var sheetH = pad
        for (r in 0 until rows) sheetH += labelH + rowH[r] + pad
        val sheetBmp = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheetBmp)
        c.drawColor(0xFF111111.toInt())
        var y = pad
        for (r in 0 until rows) {
            for (cc in 0 until cols) {
                val i = r * cols + cc
                if (i >= cells.size) break
                val x = pad + cc * (thumbW + pad)
                c.drawText(cells[i].first, x.toFloat(), (y + 32).toFloat(), labelPaint)
                val src = cells[i].second
                val dst = Rect(x, y + labelH, x + thumbW, y + labelH + th[i])
                c.drawBitmap(src, null, dst, null)
                src.recycle()
            }
            y += labelH + rowH[r] + pad
        }
        save(sheetBmp, name)
        sheetBmp.recycle()
    }

    // ---------------- clock faces ----------------

    private fun clockCells(time: String, wPx: Int, hMaxPx: Int, tag: String): List<Pair<String, Bitmap>> {
        val names = ctx.resources.getStringArray(R.array.clock_style_entries)
        val cells = ArrayList<Pair<String, Bitmap>>()
        for (s in 0..20) {
            Prefs.setClockStyle(ctx, s)
            val v = ClockView(ctx)
            v.setTime(time)
            val bmp = render(v, wPx, hMaxPx)
            save(bmp, "clock_${tag}_%02d".format(s))
            cells.add("$s ${names.getOrElse(s) { "?" }}" to bmp)
        }
        return cells
    }

    @Test
    fun clockPortrait() {
        Prefs.reset(ctx)
        Prefs.setForce24h(ctx, true)
        Prefs.setClockSize(ctx, 1)
        Prefs.setClockColor(ctx, 0)
        sheet(clockCells("12:34", 1000, 1200, "p"), 3, 380, "sheet_clock_portrait")
    }

    @Test
    fun clockPortraitSeconds() {
        Prefs.reset(ctx)
        Prefs.setForce24h(ctx, true)
        Prefs.setClockSize(ctx, 1)
        Prefs.setClockColor(ctx, 0)
        sheet(clockCells("9:08:07", 1000, 1200, "psec"), 3, 380, "sheet_clock_portrait_sec")
    }

    @Test
    fun clockPortraitBlue() {
        Prefs.reset(ctx)
        Prefs.setForce24h(ctx, true)
        Prefs.setClockSize(ctx, 1)
        Prefs.setClockColor(ctx, 1) // blue accent on every face
        sheet(clockCells("12:34", 1000, 1200, "pblue"), 3, 380, "sheet_clock_portrait_blue")
    }

    @Test
    fun clockLandscape() {
        Prefs.reset(ctx)
        Prefs.setForce24h(ctx, true)
        Prefs.setClockSize(ctx, 1)
        Prefs.setClockColor(ctx, 0)
        sheet(clockCells("12:34", 1500, 700, "l"), 3, 380, "sheet_clock_landscape")
    }

    // ---------------- calendar ----------------

    @Test
    fun calendarBoth() {
        Prefs.reset(ctx)
        val names = ctx.resources.getStringArray(R.array.calendar_style_entries)
        for ((tag, w, hMax) in listOf(Triple("p", 1000, 800), Triple("l", 1500, 600))) {
            val cells = ArrayList<Pair<String, Bitmap>>()
            for (s in 0..9) {
                Prefs.setCalendarStyle(ctx, s)
                val v = MonthCalendarView(ctx)
                v.setFirstDayOfWeek(0)
                val bmp = render(v, w, hMax)
                save(bmp, "cal_${tag}_%02d".format(s))
                cells.add("$s ${names.getOrElse(s) { "?" }}" to bmp)
            }
            sheet(cells, 3, 380, "sheet_cal_$tag")
        }
    }

    // ---------------- weather ----------------

    private fun fakeWeather(): WeatherInfo {
        val tz = TimeZone.getTimeZone("Europe/Sofia")
        val cal = Calendar.getInstance(tz)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val base = cal.timeInMillis
        val codes = intArrayOf(0, 1, 2, 3, 61, 80, 95, 2, 1, 0)
        val hours = ArrayList<WeatherHour>()
        for (i in 0 until 48) {
            hours.add(WeatherHour(base + i * 3600_000L, 14 + (i % 9), codes[i % codes.size]))
        }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        val dayBase = cal.timeInMillis
        val days = ArrayList<WeatherDay>()
        for (i in 0 until 7) {
            days.add(WeatherDay(dayBase + i * 86_400_000L, codes[(i * 3) % codes.size], 12 + i, 20 + i))
        }
        cal.set(Calendar.HOUR_OF_DAY, 6)
        cal.set(Calendar.MINUTE, 12)
        val rise = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 20)
        cal.set(Calendar.MINUTE, 41)
        val set = cal.timeInMillis
        return WeatherInfo("София", 21, 2, hours, days, 19, rise, set, "Europe/Sofia")
    }

    @Test
    fun weatherBoth() {
        Prefs.reset(ctx)
        Prefs.setTempUnit(ctx, 0)
        val data = fakeWeather()
        val names = ctx.resources.getStringArray(R.array.weather_style_entries)
        for ((tag, w, hMax) in listOf(Triple("p", 1000, 900), Triple("l", 1500, 700))) {
            val cells = ArrayList<Pair<String, Bitmap>>()
            for (s in 0..9) {
                val v = WeatherPanelView(ctx)
                v.show(data, s, false)
                val bmp = render(v, w, hMax)
                save(bmp, "wx_${tag}_%02d".format(s))
                cells.add("$s ${names.getOrElse(s) { "?" }}" to bmp)
            }
            sheet(cells, 3, 380, "sheet_wx_$tag")
        }
    }

    // ---------------- stocks ----------------

    private fun fakeCandles(): List<Candle> {
        val tz = TimeZone.getTimeZone("Europe/Moscow")
        val out = ArrayList<Candle>()
        var px = 280.0
        // Two 10-min sessions with an overnight gap between them.
        for (day in 0..1) {
            val cal = Calendar.getInstance(tz)
            cal.add(Calendar.DAY_OF_YEAR, -1 + day)
            cal.set(Calendar.HOUR_OF_DAY, 10)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            for (i in 0 until 30) {
                val o = px
                px += ((i * 37 + day * 11) % 5 - 2) * 0.35
                val c = px
                val hi = max(o, c) + 0.2
                val lo = min(o, c) - 0.2
                out.add(Candle(o, hi, lo, c, cal.timeInMillis))
                cal.add(Calendar.MINUTE, 10)
            }
        }
        return out
    }

    @Test
    fun stockCharts() {
        Prefs.reset(ctx)
        val candles = fakeCandles()
        for (type in 0..1) {
            Prefs.setStockType(ctx, type)
            for ((tag, w, hMax) in listOf(Triple("p", 1000, 700), Triple("l", 1500, 600))) {
                val v = StockChartView(ctx)
                v.setData("SBER", 275.0, "10m", candles, 600)
                val bmp = render(v, w, hMax)
                save(bmp, "stock_${tag}_type$type")
                bmp.recycle()
            }
        }
    }

    // ---------------- real settings screen ----------------

    private fun activityShot(a: MainActivity, name: String) {
        val v = a.window.decorView
        val bmp = Bitmap.createBitmap(
            v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888
        )
        v.draw(Canvas(bmp))
        save(bmp, name)
        bmp.recycle()
    }

    @Test
    fun mainActivityBothOrientations() {
        Prefs.reset(ctx)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        Thread.sleep(2500)
        scenario.onActivity { activityShot(it, "activity_portrait") }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        Thread.sleep(2500)
        scenario.onActivity { activityShot(it, "activity_landscape") }
        scenario.close()
    }
}
