package sa.rihla.tracking

import android.app.PendingIntent
import android.content.*
import android.os.Build
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import sa.rihla.RihlaApp
import sa.rihla.data.SavedPlace

object HomeGeofences {
    const val STATIONARY_WAKE_ID = "STATIONARY_WAKE_80M"

    private fun pending(c: Context) =
        PendingIntent.getBroadcast(
            c,
            42,
            Intent(c, HomeGeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
        )

    fun register(c: Context, enabled: Boolean, home: SavedPlace?, failure: (Exception) -> Unit = {}) {
        val client = LocationServices.getGeofencingClient(c)
        if (!enabled || home == null || !TrackingService.backgroundAllowed(c)) {
            client.removeGeofences(listOf("HOME")).addOnSuccessListener { status(c, "inactive") }
            return
        }
        @Suppress("MissingPermission")
        client
            .addGeofences(request(home), pending(c))
            .addOnSuccessListener { status(c, "registered") }
            .addOnFailureListener {
                status(c, "error")
                failure(it)
            }
    }

    @Suppress("MissingPermission")
    fun registerStationaryWake(c: Context, lat: Double, lon: Double) {
        if (!TrackingService.fineAllowed(c)) return
        val req =
            GeofencingRequest.Builder()
                .setInitialTrigger(0)
                .addGeofence(
                    Geofence.Builder()
                        .setRequestId(STATIONARY_WAKE_ID)
                        .setCircularRegion(lat, lon, 80f)
                        .setExpirationDuration(12 * 3600_000L)
                        .setNotificationResponsiveness(5_000)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                        .build()
                )
                .build()
        runCatching {
            LocationServices.getGeofencingClient(c).addGeofences(req, pending(c))
        }
    }

    fun clearStationaryWake(c: Context) {
        runCatching {
            LocationServices.getGeofencingClient(c).removeGeofences(listOf(STATIONARY_WAKE_ID))
        }
    }

    internal fun request(home: SavedPlace): GeofencingRequest =
        GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(
                Geofence.Builder()
                    .setRequestId("HOME")
                    .setCircularRegion(
                        home.latitude,
                        home.longitude,
                        home.radius.coerceAtLeast(100.0).toFloat(),
                    )
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setNotificationResponsiveness(30_000)
                    .setTransitionTypes(
                        Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_ENTER
                    )
                    .build()
            )
            .build()

    private fun status(c: Context, value: String) {
        c.getSharedPreferences("tracking_health", Context.MODE_PRIVATE)
            .edit()
            .putString("home", value)
            .apply()
    }

    suspend fun restore(c: Context) {
        val app = c.applicationContext as RihlaApp
        register(
            c,
            app.settings.flow.first().auto,
            app.repository.dao.places().find { it.type == "HOME" },
        )
    }
}

class HomeGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        if (ids.isEmpty()) return
        if (
            HomeGeofences.STATIONARY_WAKE_ID in ids &&
                event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT
        ) {
            TrackingService.wakeFromDeepSleep(c, "geofence_exit")
            return
        }
        if ("HOME" !in ids) return
        if (
            event.geofenceTransition !in
                listOf(Geofence.GEOFENCE_TRANSITION_EXIT, Geofence.GEOFENCE_TRANSITION_ENTER)
        )
            return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = c.applicationContext as RihlaApp
                if (!app.settings.flow.first().auto || !TrackingService.backgroundAllowed(c))
                    return@launch
                val active = app.repository.dao.active()
                if (active != null || event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT)
                    TrackingService.launch(c, active == null)
            } finally {
                pending.finish()
            }
        }
    }
}
