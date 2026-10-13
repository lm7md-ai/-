package sa.rihla.tracking

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import sa.rihla.MainActivity
import sa.rihla.R
import sa.rihla.RihlaApp
import sa.rihla.core.*

class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Fix>(Channel.UNLIMITED)
    private val app
        get() = application as RihlaApp

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val native by lazy { getSystemService(LocationManager::class.java) }
    private val listener = LocationListener { l ->
        queue.trySend(
            Fix(
                l.latitude,
                l.longitude,
                l.time,
                l.accuracy.toDouble(),
                l.speed.toDouble(),
                l.altitude,
                l.bearing.toDouble(),
            )
        )
    }
    private var candidate: Fix? = null
    private var autoPending = false
    private var slow = false
    private var registeredInterval: Long? = null
    private val callback =
        object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                r.locations.forEach { l ->
                    queue.trySend(
                        Fix(
                            l.latitude,
                            l.longitude,
                            l.time,
                            l.accuracy.toDouble(),
                            l.speed.toDouble(),
                            l.altitude,
                            l.bearing.toDouble(),
                        )
                    )
                }
            }
        }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                "tracking",
                getString(R.string.tracking_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val end =
            PendingIntent.getService(
                this,
                1,
                Intent(this, TrackingService::class.java).setAction("END"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(this, "tracking")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(getString(R.string.tracking_active))
                .setContentText(getString(R.string.tracking_notice))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(null, getString(R.string.end_journey), end).build()
                )
                .build()
        try {
            startForeground(10, notification)
        } catch (_: SecurityException) {
            stopSelf()
            return
        } catch (_: IllegalStateException) {
            stopSelf()
            return
        }
        scope.launch {
            for (f in queue) {
                if (autoPending && app.repository.dao.active() == null) {
                    if (f.accuracy > 40) continue
                    val first = candidate
                    if (first == null) {
                        candidate = f
                        continue
                    }
                    val dt = f.time - first.time
                    if (drivingEvidence(first, f)) {
                        app.repository.start(true, first.time)
                        app.repository.record(first)
                        autoPending = false
                    } else if (dt > 120000) {
                        stopSelf()
                        break
                    } else continue
                }
                if (app.repository.record(f)) {
                    stopSelf()
                    break
                }
                val state = app.repository.dao.active()?.state ?: continue
                val shouldSlow = state.stopStart > 0
                if (shouldSlow != slow) {
                    slow = shouldSlow
                    withContext(Dispatchers.Main) { requestUpdates() }
                }
            }
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == "END") {
            scope.launch {
                app.repository.end()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        autoPending = i?.getBooleanExtra("automatic", false) == true
        scope.launch {
            if (!autoPending && app.repository.dao.active() == null) {
                if (i == null) {
                    stopSelf()
                    return@launch
                }
                app.repository.start(false)
            }
            withContext(Dispatchers.Main) { requestUpdates() }
        }
        if (autoPending)
            scope.launch {
                delay(150000)
                if (app.repository.dao.active() == null) stopSelf()
            }
        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun requestUpdates() {
        if (!fineAllowed(this)) {
            stopSelf()
            return
        }
        val interval = if (slow) 30000L else 5000L
        if (registeredInterval == interval) return
        client.removeLocationUpdates(callback)
        native.removeUpdates(listener)
        registeredInterval = interval
        val request =
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                .setMinUpdateIntervalMillis(if (slow) 15000L else 2000L)
                .setMaxUpdateDelayMillis(interval)
                .build()
        try {
            if (
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) ==
                    ConnectionResult.SUCCESS
            )
                client
                    .requestLocationUpdates(request, callback, Looper.getMainLooper())
                    .addOnFailureListener { nativeUpdates(interval) }
            else nativeUpdates(interval)
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    @Suppress("MissingPermission")
    private fun nativeUpdates(interval: Long) {
        if (registeredInterval != interval) return
        try {
            native.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                interval,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        native.removeUpdates(listener)
        client.removeLocationUpdates(callback)
        queue.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun fineAllowed(c: Context) =
            c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        fun backgroundAllowed(c: Context) =
            fineAllowed(c) &&
                (Build.VERSION.SDK_INT < 29 ||
                    c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED)

        fun launch(c: Context, automatic: Boolean = false): Boolean =
            try {
                c.startForegroundService(
                    Intent(c, TrackingService::class.java).putExtra("automatic", automatic)
                )
                true
            } catch (_: IllegalStateException) {
                false
            } catch (_: SecurityException) {
                false
            }
    }
}
