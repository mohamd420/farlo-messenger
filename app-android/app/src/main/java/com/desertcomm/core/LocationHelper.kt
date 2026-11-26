package com.desertcomm.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager

object LocationHelper {
    @SuppressLint("MissingPermission")
    fun getBestLastKnownLocation(context: Context): Result<Pair<Double, Double>> = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || loc.accuracy < best.accuracy) {
                best = loc
            }
        }
        val finalLoc = best ?: error("فعّل الـ GPS وقف في مكان مكشوف لثوانٍ ثم أعد المحاولة")
        finalLoc.latitude to finalLoc.longitude
    }
}