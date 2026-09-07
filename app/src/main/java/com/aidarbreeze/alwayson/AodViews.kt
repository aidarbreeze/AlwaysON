package com.aidarbreeze.alwayson

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.View
import android.widget.TextView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Owns the three text views of the AOD layout and refreshes them with the
 * current time / date / battery. Used both by the overlay service and the
 * screen-saver service so the clock looks identical in both modes.
 */
class AodViews(private val context: Context, private val root: View) {

    private val clockText: TextView = root.findViewById(R.id.clockText)
    private val dateText: TextView = root.findViewById(R.id.dateText)
    private val batteryText: TextView = root.findViewById(R.id.batteryText)

    /** Push option changes (24h/seconds/battery visibility) into the views. */
    fun applyOptions() {
        batteryText.visibility =
            if (Prefs.showBattery(context)) View.VISIBLE else View.GONE
    }

    /** Read the clock and repaint the views. Cheap; safe to call every second. */
    fun updateNow() {
        val now = System.currentTimeMillis()

        val use24 = resolve24h()
        val showSeconds = Prefs.showSeconds(context)
        val pattern = when {
            use24 && showSeconds -> "HH:mm:ss"
            use24 -> "HH:mm"
            showSeconds -> "h:mm:ss"
            else -> "h:mm"
        }
        val fmt = SimpleDateFormat(pattern, Locale.getDefault())
        clockText.text = fmt.format(now)

        val dateFmt = DateFormat.getDateInstance(DateFormat.LONG)
        dateText.text = dateFmt.format(now)

        if (batteryText.visibility == View.VISIBLE) {
            batteryText.text = batterySummary()
        }
    }

    private fun resolve24h(): Boolean {
        // Switch ON -> force 24h; switch OFF -> follow the device clock format.
        if (Prefs.force24h(context)) return true
        return android.text.format.DateFormat.is24HourFormat(context)
    }

    private fun batterySummary(): String {
        val ifl = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(null, ifl) ?: return ""
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (level < 0 || scale <= 0) return ""
        val percent = (level * 100f / scale).toInt()
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return if (charging) "$percent% (AC/charging)" else "$percent%"
    }
}
