package sa.rihla.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import sa.rihla.core.Fix

object LocationPicker {
    internal fun usable(l: Location): Boolean {
        @Suppress("DEPRECATION")
        val mock = if (android.os.Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider
        return sa.rihla.core.usableLocation(
            (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000,
            l.accuracy.toDouble(),
            mock,
        ) && l.latitude in -90.0..90.0 && l.longitude in -180.0..180.0
    }

    @SuppressLint("MissingPermission")
    suspend fun current(c: Context): Fix? {
        if (!TrackingService.fineAllowed(c)) return null
        if (
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c) ==
                ConnectionResult.SUCCESS
        ) {
            val result =
                withTimeoutOrNull(20000) {
                    suspendCancellableCoroutine<Fix?> { cont ->
                        val token = CancellationTokenSource()
                        cont.invokeOnCancellation { token.cancel() }
                        try {
                            LocationServices.getFusedLocationProviderClient(c)
                                .getCurrentLocation(
                                    CurrentLocationRequest.Builder()
                                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                                        .setMaxUpdateAgeMillis(15000)
                                        .setDurationMillis(18000)
                                        .build(),
                                    token.token,
                                )
                                .addOnSuccessListener {
                                    if (cont.isActive)
                                        cont.resume(it?.takeIf(::usable)?.let { it.trackingFix() })
                                }
                                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                        } catch (_: SecurityException) {
                            cont.resume(null)
                        }
                    }
                }
            if (result != null) return result
        }
        return withTimeoutOrNull(20000) {
            suspendCancellableCoroutine { cont ->
                val manager = c.getSystemService(LocationManager::class.java)
                val listener =
                    object : LocationListener {
                        override fun onLocationChanged(l: Location) {
                            if (usable(l) && cont.isActive) {
                                manager.removeUpdates(this)
                                cont.resume(l.trackingFix())
                            }
                        }
                    }
                cont.invokeOnCancellation { manager.removeUpdates(listener) }
                try {
                    val last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    if (
                        last != null &&
                            System.currentTimeMillis() - last.time in 0..15000 &&
                            usable(last)
                    )
                        cont.resume(last.trackingFix())
                    else
                        manager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            1000L,
                            0f,
                            listener,
                            Looper.getMainLooper(),
                        )
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}

/** Missing speed is kept distinct from a measured stationary fix. */
internal fun Location.trackingFix(source: String = provider.orEmpty()): Fix =
    Fix(
        latitude,
        longitude,
        time,
        accuracy.toDouble(),
        if (hasSpeed()) speed.toDouble() else 0.0,
        if (hasAltitude()) altitude else 0.0,
        if (hasBearing()) bearing.toDouble() else 0.0,
        hasSpeed = hasSpeed(),
        speedAccuracy = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null,
        elapsedNanos = elapsedRealtimeNanos,
        provider = source,
        hasBearing = hasBearing(),
    )
