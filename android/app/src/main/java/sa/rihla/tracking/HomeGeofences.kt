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
    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 42,
        Intent(c, HomeGeofenceReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)

    fun register(c: Context, enabled: Boolean, home: SavedPlace?, failure: (Exception) -> Unit = {}) {
        val client = LocationServices.getGeofencingClient(c)
        if (!enabled || home == null || !TrackingService.backgroundAllowed(c)) {
            client.removeGeofences(pending(c)).addOnSuccessListener { status(c, "inactive") }
            return
        }
        @Suppress("MissingPermission")
        client.addGeofences(request(home), pending(c))
            .addOnSuccessListener { status(c, "registered") }
            .addOnFailureListener { status(c, "error"); failure(it) }
    }

    internal fun request(home: SavedPlace): GeofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(Geofence.Builder().setRequestId("HOME")
                .setCircularRegion(home.latitude, home.longitude, home.radius.coerceAtLeast(100.0).toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setNotificationResponsiveness(30_000)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()).build()

    private fun status(c: Context, value: String) {
        c.getSharedPreferences("tracking_health", Context.MODE_PRIVATE).edit().putString("home", value).apply()
    }

    suspend fun restore(c: Context) {
        val app = c.applicationContext as RihlaApp
        register(c, app.settings.flow.first().auto, app.repository.dao.places().find { it.type == "HOME" })
    }
}

class HomeGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.triggeringGeofences?.none { it.requestId == "HOME" } != false) return
        if (event.geofenceTransition !in listOf(Geofence.GEOFENCE_TRANSITION_EXIT, Geofence.GEOFENCE_TRANSITION_ENTER)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = c.applicationContext as RihlaApp
                if (!app.settings.flow.first().auto || !TrackingService.backgroundAllowed(c)) return@launch
                val active = app.repository.dao.active()
                // Entry resumes an existing trip; GPS still confirms arrival and the five-minute stop.
                if (active != null || event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT)
                    TrackingService.launch(c, active == null)
            } finally { pending.finish() }
        }
    }
}
