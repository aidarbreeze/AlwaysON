package com.aidarbreeze.alwayson

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager

/**
 * Best-effort "last known" location so weather can show without keeping the
 * GPS running (fine for a forecast point). Returns null when the permission is
 * missing or no provider has a fix yet.
 */
object Location {
    fun lastKnown(ctx: Context): Pair<Double, Double>? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val best = bestFix(lm)
        val loc = best ?: return null
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return null
        return loc.latitude to loc.longitude
    }

    private fun bestFix(lm: LocationManager): android.location.Location? {
        // Passive is cheapest; GPS/passive are the two that are actually
        // useful here. We take the most recent of whichever is available.
        var newest: android.location.Location? = null
        for (provider in arrayOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )) {
            try {
                val l = lm.getLastKnownLocation(provider) ?: continue
                if (newest == null || l.time > newest!!.time) newest = l
            } catch (_: SecurityException) {
                // permission race; ignore
            } catch (_: Exception) {
                // provider disabled
            }
        }
        return newest
    }

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
}
