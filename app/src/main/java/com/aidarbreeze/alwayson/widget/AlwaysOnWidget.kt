package com.aidarbreeze.alwayson.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.SystemClock
import android.text.format.DateFormat
import android.widget.RemoteViews
import com.aidarbreeze.alwayson.Prefs
import com.aidarbreeze.alwayson.R
import com.aidarbreeze.alwayson.StandbyActivity
import com.aidarbreeze.alwayson.weather.WeatherLabel
import com.aidarbreeze.alwayson.weather.WeatherSharedCache
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home-screen widget: the same clock the app is named after. Black background,
 * white text — time (large), date and a status line (battery + last known
 * weather from the shared cache; the widget itself never does networking).
 *
 * The clock refreshes on a 60 s repeating alarm in addition to the system
 * widget update (whose minimum period is 30 minutes).
 */
class AlwaysOnWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_TICK = "com.aidarbreeze.alwayson.WIDGET_TICK"
        private const val TICK_MS = 60_000L
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TICK) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, AlwaysOnWidget::class.java))
            for (id in ids) update(context, mgr, id)
        }
    }

    override fun onEnabled(context: Context) {
        // First widget added: arm the per-minute clock tick.
        startTicks(context)
    }

    override fun onDisabled(context: Context) {
        // Last widget removed: stop the tick.
        cancelTicks(context)
    }

    private fun tickPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AlwaysOnWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun startTicks(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = SystemClock.elapsedRealtime()
        am.setRepeating(
            AlarmManager.ELAPSED_REALTIME,
            now + TICK_MS,
            TICK_MS,
            tickPendingIntent(context)
        )
    }

    private fun cancelTicks(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(tickPendingIntent(context))
    }

    private fun update(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_alwayson)

        val now = Calendar.getInstance()
        val millis = now.timeInMillis
        val use24 = DateFormat.is24HourFormat(context)
        val pattern = if (use24) "HH:mm" else "h:mm"
        views.setTextViewText(
            R.id.widgetTime,
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
        )

        val locale = Locale.getDefault()
        val datePattern = if (locale.language.equals("ru", ignoreCase = true))
            "EEEE, d MMMM" else "EEEE, MMMM d"
        views.setTextViewText(
            R.id.widgetDate,
            SimpleDateFormat(datePattern, locale).format(Date(millis))
        )

        // Status line: battery + last known weather (shared cache).
        val parts = StringBuilder()
        val pct = batteryPct(context)
        if (pct >= 0) parts.append(pct).append("%")
        val snap = WeatherSharedCache.load(context)
        if (snap != null) {
            if (parts.isNotEmpty()) parts.append("  ·  ")
            if (snap.city.isNotBlank()) parts.append(snap.city.trim()).append("  ")
            val c = snap.tempNowC
            // Round to the nearest degree (truncation is off by 1° sometimes).
            val shown = if (Prefs.tempUnit(context) == 1)
                kotlin.math.round(c * 9f / 5f + 32f).toInt()
            else c
            parts.append(shown).append("°")
            val label = WeatherLabel.of(snap.codeNow)
            if (label.isNotEmpty()) parts.append("  ").append(label)
        }
        views.setTextViewText(R.id.widgetStatus, parts.toString())

        // Tap -> the full StandBy preview.
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, StandbyActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, open)

        mgr.updateAppWidget(id, views)
    }

    private fun batteryPct(context: Context): Int {
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) -1 else (level * 100 / scale)
    }
}
