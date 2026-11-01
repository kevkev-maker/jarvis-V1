package com.example.myapp.jarvis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Finds where you are, for "near me" questions.
 * Android limits location in the background, so every time the JARVIS screen
 * opens we save a fresh location as a backup.
 */
object LocationHelper {

    data class Fix(val lat: Double, val lon: Double, val ageMinutes: Long)

    private const val PREFS = "jarvis_location"

    fun hasPermission(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Fire-and-forget refresh (call when the app screen opens). */
    fun refresh(ctx: Context) {
        if (!hasPermission(ctx)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = provider(lm) ?: return
        try {
            LocationManagerCompat.getCurrentLocation(
                lm, provider, null, ContextCompat.getMainExecutor(ctx)
            ) { loc -> if (loc != null) save(ctx, loc) }
        } catch (_: Exception) { }
    }

    /** Best available location: fresh → last known → saved. null if nothing at all. */
    suspend fun best(ctx: Context): Fix? {
        val app = ctx.applicationContext
        if (hasPermission(app)) {
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = provider(lm)
            if (provider != null) {
                val fresh = withTimeoutOrNull(6_000) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        try {
                            LocationManagerCompat.getCurrentLocation(
                                lm, provider, null, ContextCompat.getMainExecutor(app)
                            ) { loc -> if (cont.isActive) cont.resume(loc) }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
                if (fresh != null) {
                    save(app, fresh)
                    return Fix(fresh.latitude, fresh.longitude, 0)
                }
            }
            val last = lastKnown(lm)
            if (last != null) {
                save(app, last)
                return Fix(last.latitude, last.longitude, ageMinutes(last.time))
            }
        }
        return saved(app)
    }

    private fun provider(lm: LocationManager): String? = when {
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> null
    }

    private fun lastKnown(lm: LocationManager): Location? {
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        return best
    }

    private fun save(ctx: Context, l: Location) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lat", l.latitude.toString())
            .putString("lon", l.longitude.toString())
            .putLong("time", if (l.time > 0) l.time else System.currentTimeMillis())
            .apply()
    }

    private fun saved(ctx: Context): Fix? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return null
        return Fix(lat, lon, ageMinutes(p.getLong("time", 0)))
    }

    private fun ageMinutes(time: Long): Long =
        ((System.currentTimeMillis() - time) / 60_000).coerceAtLeast(0)

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
}
