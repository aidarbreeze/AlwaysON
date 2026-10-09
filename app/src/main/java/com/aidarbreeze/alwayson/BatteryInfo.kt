package com.aidarbreeze.alwayson

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import kotlin.math.abs

/**
 * Battery readings, kept separate from the UI so the same robust logic is used
 * by the overlay, the preview and the system screen saver, and so it can be
 * probed from the settings screen for diagnostics.
 *
 * The charge current is the tricky part: [BatteryManager] only reports it when
 * the kernel exposes it through the battery HAL, and the sign is not defined
 * consistently across devices (some report it negative while charging). As a
 * fallback we read the raw nodes under /sys/class/power_supply (the same
 * source AIDA64 / Ampere use): every battery appears there as a symlink, so a
 * single directory scan covers all device-specific paths (battery, bms, main,
 * usb, ac, dc, charger, wireless and the various fuel-gauge chips).
 */
object BatteryInfo {

    // File names tried for each power-supply, in order of preference. Most
    // kernels name the live current "current_now"; several OEM builds use one
    // of the others listed here.
    private val CURRENT_FILES = arrayOf(
        "current_now",      // standard (µA; negative = charging on many ROMs)
        "current_avg",      // older/OEM spelling
        "current_average",  // Android HAL average
        "current",          // some fuel-gauges
        "batt_current",     // older Samsung/OnePlus-ish gauges
        "batt_current_now",
        "batt_chg_current",
        "charger_current",
        "BatteryAverageCurrent"
    )

    // Supply directory names to try by direct path. On many ROMs (e.g.
    // OxygenOS/ColorOS) an app is NOT allowed to *list* /sys/class/power_supply
    // (readdir -> EACCES, so a dynamic scan finds 0 entries) but IS allowed to
    // open a specific known file. So we always try these well-known names
    // directly in addition to whatever a listing gives us.
    private val SUPPLY_DIRS = arrayOf(
        "battery", "Battery", "bms", "gauge",
        "charger", "main", "usb", "ac", "dc", "pc_port", "wireless"
    )

    /** Highest-resolution single measurement: current in mA, or <=0 if the
     *  device does not report it. Sign is normalised to positive. */
    fun readCurrentMa(context: Context): Int {
        // 1) BatteryManager property (µA). Charge current is reported as
        //    negative on many devices, so always take the absolute value.
        val bm = context.applicationContext
            .getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val propertyMa = propertyCurrentMa(bm)
        if (propertyMa > 0) return propertyMa

        // 2) sysfs (AIDA64/Ampere style). Walk every power-supply we can find
        //    (dynamic listing where allowed, plus the well-known direct paths
        //    where the listing is blocked). Battery first, then charger/input.
        for (dir in candidateSupplies()) {
            val ma = sysfsMa(dir.absolutePath)
            if (ma > 0) return ma
        }
        return 0
    }

    /**
     * The raw live value straight from
     * [BatteryManager.BATTERY_PROPERTY_CURRENT_NOW], preserved as-is (no unit
     * scaling). On many ROMs a negative value means the battery is charging.
     * Returns null when the device does not report a reading
     * (0 / Int.MIN_VALUE / unsupported).
     */
    fun readCurrentNowRaw(context: Context): Int? {
        val bm = context.applicationContext
            .getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        return try {
            val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (ua == 0 || ua == Int.MIN_VALUE) null else ua
        } catch (_: Throwable) {
            null
        }
    }

    private fun propertyCurrentMa(bm: BatteryManager?): Int {
        if (bm == null) return 0
        return try {
            val now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val avg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            val ua = firstPositiveAbs(now, avg)
            if (ua > 0) ua / 1000 else 0
        } catch (_: Throwable) {
            0
        }
    }

    /** Supply dirs to probe: the known well-known names (always tried, since a
     *  listing may be blocked) merged with a dynamic listing when it works.
     *  Sorted so battery/gauge come first, then charger/input sources. */
    private fun candidateSupplies(): List<File> {
        val known = SUPPLY_DIRS.map { File("/sys/class/power_supply", it) }
        val dynamic = powerSupplies()
        return (known + dynamic)
            .distinctBy { it.absolutePath }
            .sortedWith(compareBy({ supplyOrder(it.name) }, { it.name }))
    }

    private fun supplyOrder(name: String): Int = when {
        name.contains("battery", ignoreCase = true) -> 0
        name.equals("bms", ignoreCase = true) -> 0
        name.contains("gauge") -> 1
        name.contains("charger") -> 2
        name.contains("main") -> 3
        name.contains("usb") || name.contains("ac") || name.contains("dc") ||
            name.contains("pc_port") -> 4
        name.contains("wireless") -> 5
        else -> 6
    }

    /** Reads the best current file under one supply dir, returning mA. */
    private fun sysfsMa(dirPath: String): Int {
        val dir = File(dirPath)
        if (!dir.isDirectory) return 0
        for (name in CURRENT_FILES) {
            val value = readLong(File(dir, name)) ?: continue
            if (value == 0L) continue
            return normalizeToMa(value)
        }
        return 0
    }

    private fun firstPositiveAbs(a: Int, b: Int): Int {
        val aa = abs(a)
        val ab = abs(b)
        // int min has no positive form; guard it.
        return when {
            a != Int.MIN_VALUE && aa > 0 -> aa
            b != Int.MIN_VALUE && ab > 0 -> ab
            else -> 0
        }
    }

    /** sysfs reports µA (usually thousands, possibly negative while charging)
     *  but a few ROMs already give mA. Normalise to mA, always positive. */
    private fun normalizeToMa(raw: Long): Int {
        val v = abs(raw)
        val ma = if (v > 200_000L) v / 1000L else v
        return ma.toInt().coerceAtMost(100_000)
    }

    private fun readLong(file: File): Long? {
        return try {
            if (!file.exists()) return null
            val content = file.readText().trim()
            // sysfs files are a single integer, but be defensive and take the
            // first signed integer token in case of surrounding noise.
            val digits = Regex("""-?\d+""").find(content)?.value ?: return null
            digits.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun powerSupplies(): List<File> {
        return try {
            File("/sys/class/power_supply").listFiles { f -> f.isDirectory }?.toList()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** True while the phone is on a working charger. Checks EXTRA_PLUGGED
     *  (AC / USB / wireless) in addition to the battery status: a FULL status
     *  alone does not prove the device is still powered, and a full battery
     *  can report NOT_CHARGING while sitting on the charger. */
    fun isCharging(context: Context): Boolean {
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return false

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

        val isPlugged =
            plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS

        return isPlugged &&
            status != BatteryManager.BATTERY_STATUS_DISCHARGING &&
            status != BatteryManager.BATTERY_STATUS_NOT_CHARGING &&
            status != BatteryManager.BATTERY_STATUS_UNKNOWN
    }

    /** True when the battery is draining (not on a working charger). */
    fun isDischarging(context: Context): Boolean =
        status(context) == BatteryManager.BATTERY_STATUS_DISCHARGING

    private fun status(context: Context): Int {
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return -1
        return intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    }

    /** Diagnostic dump for the settings screen: reports each candidate source
     *  so we can see on a given device why the current is (or is not) found. */
    fun probe(context: Context): List<String> {
        val out = ArrayList<String>()
        val bm = context.applicationContext
            .getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val status = status(context)
        out.add("EXTRA_STATUS=$status (2=charging, 5=full)")
        out.add("isCharging=${isCharging(context)}")

        try {
            val now = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val avg = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            out.add("BatteryManager CURRENT_NOW=$now")
            out.add("BatteryManager CURRENT_AVERAGE=$avg")
        } catch (t: Throwable) {
            out.add("BatteryManager property threw: ${t.message}")
        }

        out.add("raw power_supply listing: ${powerSupplies().size} entries")

        // Probe each candidate node (direct known paths + any listed supply)
        // and report exactly what the app can open without root.
        val supplies = candidateSupplies()
        out.add("probed supply dirs: ${supplies.size}")
        var anyReadableNode = false
        for (dir in supplies) {
            for (name in CURRENT_FILES) {
                val f = File(dir, name)
                if (!f.exists()) continue
                val value = readLong(f)
                if (value != null) {
                    anyReadableNode = true
                    out.add("${f.path} = $value")
                } else {
                    out.add("${f.path} exists but is NOT readable (permission)")
                }
            }
        }
        if (!anyReadableNode) {
            out.add("no battery/current node readable without root")
        }
        out.add("final readCurrentMa = ${readCurrentMa(context)}")
        return out
    }
}
