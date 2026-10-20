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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sa.rihla.MainActivity
import sa.rihla.R
import sa.rihla.RihlaApp
import sa.rihla.core.*

class TrackingService : Service() {
    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO +
                CoroutineExceptionHandler { _, _ ->
                    health(this, "error")
                    stopSelf()
                }
        )
    private val queue = Channel<Fix>(256)
    private val commandLock = Mutex()
    private val app
        get() = application as RihlaApp

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val native by lazy { getSystemService(LocationManager::class.java) }
    private val listener = LocationListener { l -> receive(l, "GPS") }
    @Volatile private var lastProviderFix = SystemClock.elapsedRealtime()
    @Volatile private var nativeActive = false
    @Volatile private var precise = false
    private var pendingTimeout: Job? = null
    private val qualityGate = FixQualityGate()
    private var generation = 0L
    private var fusedRegistered = false
    private var lastFusedAttempt = 0L
    private var fusedRecoveryAt = 0L
    private var lastHeartbeat = 0L
    private var lastSpeed = 0.0
    private val turnDetector = TurnDetailDetector()
    private var turnDetails = true
    private var turnBoostUntil = 0L
    private var boostUntil = 0L
    private var gnssRegistered = false
    private val gnss =
        object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                val used = (0 until status.satelliteCount).filter { status.usedInFix(it) }
                val frequency = used.count {
                    status.hasCarrierFrequencyHz(it) &&
                        status.getCarrierFrequencyHz(it) in 1.16e9f..1.22e9f
                }
                LiveTrackingHealth.state.update {
                    it.copy(
                        satellites = status.satelliteCount,
                        usedSatellites = used.size,
                        averageCn0 =
                            if (used.isEmpty()) 0.0
                            else used.map { status.getCn0DbHz(it).toDouble() }.average(),
                        dualFrequency = frequency,
                    )
                }
            }
        }

    private fun rejected(reason: String) {
        LiveTrackingHealth.state.update {
            it.copy(accepted = false, reason = reason, rejectedCount = it.rejectedCount + 1)
        }
    }

    private fun receive(l: android.location.Location, source: String) {
        if (registeredInterval == null) return
        val age = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        @Suppress("DEPRECATION")
        val mock = if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider
        if (!usableLocation(age, l.accuracy.toDouble(), mock)) {
            rejected(if (mock) "MOCK" else if (age !in 0..60_000) "STALE" else "ACCURACY")
            return
        }
        val fix = l.trackingFix(source)
        val reason = qualityGate.inspect(fix)
        if (reason != null) {
            rejected(reason)
            return
        }
        lastProviderFix = SystemClock.elapsedRealtime()
        if (source == "Fused" && nativeActive) {
            if (fusedRecoveryAt == 0L) fusedRecoveryAt = SystemClock.elapsedRealtime()
            else if (SystemClock.elapsedRealtime() - fusedRecoveryAt >= 2000) {
                native.removeUpdates(listener)
                nativeActive = false
                fusedRecoveryAt = 0
            }
        }
        LiveTrackingHealth.state.update {
            it.copy(
                receivedAt = fix.time,
                accuracy = fix.accuracy,
                provider = source,
                updatedElapsed = SystemClock.elapsedRealtime(),
                reason = "",
                accepted = false,
            )
        }
        val accepted = if (qualityGate.reacquired) fix.copy(discontinuous = true) else fix
        if (queue.trySend(accepted).isFailure) rejected("QUEUE")
    }

    private val candidate = DrivingCandidate()
    private val pendingFixes = java.util.ArrayDeque<Fix>()
    private var autoPending = false
    @Volatile private var slow = false
    @Volatile private var registeredInterval: Long? = null
    private val callback =
        object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                r.locations
                    .sortedBy { it.elapsedRealtimeNanos }
                    .forEach { l -> receive(l, "Fused") }
            }
        }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LiveTrackingHealth.state.value = TrackingHealth(service = "starting")
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
            health(this, "running")
        } catch (_: SecurityException) {
            health(this, "permission")
            stopSelf()
            return
        } catch (_: IllegalStateException) {
            health(this, "blocked")
            stopSelf()
            return
        }
        scope.launch {
            for (f in queue) {
                val ended = commandLock.withLock {
                    if (autoPending && app.repository.dao.active() == null) {
                        pendingFixes.addLast(f)
                        while (
                            pendingFixes.size > 128 ||
                                (pendingFixes.firstOrNull()?.time ?: f.time) < f.time - 90000
                        ) pendingFixes.removeFirst()
                        val first = candidate.accept(f)
                        if (first != null) {
                            app.repository.start(true, first.time)
                            var restored =
                                if (app.repository.recordDetailed(first).accepted) 1L else 0L
                            pendingFixes
                                .filter { it.time > first.time && it.time < f.time }
                                .forEach {
                                    if (app.repository.recordDetailed(it).accepted) restored++
                                }
                            LiveTrackingHealth.state.update {
                                it.copy(acceptedCount = it.acceptedCount + restored)
                            }
                            pendingFixes.clear()
                            autoPending = false
                            health(this@TrackingService, "running")
                        } else return@withLock false
                    }
                    val outcome = app.repository.recordDetailed(f)
                    val accepted = outcome.accepted
                    val nowElapsed = SystemClock.elapsedRealtime()
                    LiveTrackingHealth.state.update {
                        it.copy(
                            receivedAt = f.time,
                            accuracy = f.accuracy,
                            provider = f.provider,
                            accepted = accepted,
                            reason = if (accepted) "" else "ENGINE",
                            acceptedCount = it.acceptedCount + if (accepted) 1 else 0,
                            rejectedCount = it.rejectedCount + if (accepted) 0 else 1,
                            updatedElapsed = nowElapsed,
                        )
                    }
                    if (outcome.ended) {
                        stopSelf()
                        return@withLock true
                    }
                    val state = app.repository.dao.active()?.state ?: return@withLock false
                    if (nowElapsed - lastHeartbeat >= 15000) {
                        health(
                            this@TrackingService,
                            if (autoPending) "checking_driving" else "running",
                        )
                        lastHeartbeat = nowElapsed
                    }
                    if (accepted) {
                        if (turnDetails && turnDetector.inspect(f))
                            turnBoostUntil = nowElapsed + 15_000
                        if (f.speed >= 4 && f.accuracy > 25) boostUntil = nowElapsed + 30_000
                        lastSpeed = f.speed
                    }
                    val shouldSlow =
                        state.stopStart > 0 &&
                            nowElapsed >= boostUntil &&
                            nowElapsed >= turnBoostUntil
                    if (
                        shouldSlow != slow ||
                            registeredInterval !=
                                adaptiveTrackingInterval(
                                    shouldSlow,
                                    precise,
                                    lastSpeed,
                                    nowElapsed < boostUntil,
                                    turnDetails && nowElapsed < turnBoostUntil,
                                )
                    ) {
                        slow = shouldSlow
                        withContext(Dispatchers.Main) { requestUpdates() }
                    }
                    false
                }
                if (ended) break
            }
        }
        scope.launch {
            while (isActive) {
                delay(15_000)
                if (
                    !nativeActive &&
                        registeredInterval != null &&
                        SystemClock.elapsedRealtime() - lastProviderFix >
                            (if (slow) 90_000 else 30_000)
                )
                    withContext(Dispatchers.Main) {
                        registeredInterval?.let { nativeUpdates(it) }
                    }
                if (
                    nativeActive &&
                        !fusedRegistered &&
                        SystemClock.elapsedRealtime() - lastFusedAttempt > 120_000
                )
                    withContext(Dispatchers.Main) {
                        registeredInterval?.let { requestFused(it, generation) }
                    }
            }
        }
        scope.launch {
            app.settings.flow.collect { preferences ->
                commandLock.withLock {
                    if (
                        preferences.preciseTracking != precise ||
                            preferences.turnDetails != turnDetails
                    ) {
                        precise = preferences.preciseTracking
                        turnDetails = preferences.turnDetails
                        if (!turnDetails) {
                            turnBoostUntil = 0
                            turnDetector.reset()
                        }
                        if (registeredInterval != null)
                            withContext(Dispatchers.Main) { requestUpdates() }
                    }
                    if (!preferences.auto && autoPending && app.repository.dao.active() == null)
                        stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == "END") {
            scope.launch {
                commandLock.withLock {
                    app.repository.end()
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }
        scope.launch {
            commandLock.withLock {
                val preferences = app.settings.flow.first()
                if (
                    i?.getBooleanExtra("automatic", false) == true &&
                        !preferences.auto &&
                        app.repository.dao.active() == null
                ) {
                    stopSelf()
                    return@withLock
                }
                // Repeated triggers must not discard the evidence already collected.
                if (
                    i?.getBooleanExtra("automatic", false) == true &&
                        autoPending &&
                        registeredInterval != null
                )
                    return@withLock
                autoPending =
                    i?.getBooleanExtra("automatic", false) == true &&
                        app.repository.dao.active() == null
                if (autoPending) {
                    candidate.reset()
                    pendingFixes.clear()
                }
                health(this@TrackingService, if (autoPending) "checking_driving" else "running")
                precise = preferences.preciseTracking
                turnDetails = preferences.turnDetails
                slow = app.repository.dao.active()?.state?.stopStart?.let { it > 0 } == true
                if (i?.getBooleanExtra("motion_resume", false) == true) {
                    slow = false
                    boostUntil = SystemClock.elapsedRealtime() + 30_000
                }
                pendingTimeout?.cancel()
                if (autoPending)
                    pendingTimeout = scope.launch {
                        delay(600_000)
                        commandLock.withLock {
                            if (app.repository.dao.active() == null) stopSelf()
                        }
                    }
                if (!autoPending && app.repository.dao.active() == null) {
                    if (i == null) {
                        stopSelf()
                        return@withLock
                    }
                    app.repository.start(false)
                }
                withContext(Dispatchers.Main) { requestUpdates() }
            }
        }
        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun requestUpdates() {
        if (!fineAllowed(this)) {
            stopSelf()
            return
        }
        val interval =
            adaptiveTrackingInterval(
                slow,
                precise,
                lastSpeed,
                SystemClock.elapsedRealtime() < boostUntil,
                turnDetails && SystemClock.elapsedRealtime() < turnBoostUntil,
            )
        if (registeredInterval == interval) return
        generation++
        val current = generation
        client.removeLocationUpdates(callback)
        native.removeUpdates(listener)
        registeredInterval = interval
        LiveTrackingHealth.state.update { it.copy(requestedInterval = interval) }
        nativeActive = false
        fusedRegistered = false
        fusedRecoveryAt = 0
        lastProviderFix = SystemClock.elapsedRealtime()
        if (!gnssRegistered)
            runCatching {
                gnssRegistered =
                    native.registerGnssStatusCallback(gnss, Handler(Looper.getMainLooper()))
            }
        requestFused(interval, current)
    }

    @Suppress("MissingPermission")
    private fun requestFused(interval: Long, current: Long) {
        if (registeredInterval != interval || generation != current) return
        lastFusedAttempt = SystemClock.elapsedRealtime()
        val request =
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                .setMinUpdateIntervalMillis(
                    if (slow) {
                        if (precise) 5000L else 15000L
                    } else 1000L
                )
                .setMaxUpdateDelayMillis(interval)
                .setWaitForAccurateLocation(false)
                .build()
        try {
            if (
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) ==
                    ConnectionResult.SUCCESS
            ) {
                fusedRegistered = true
                client
                    .requestLocationUpdates(request, callback, Looper.getMainLooper())
                    .addOnFailureListener {
                        if (current == generation && registeredInterval == interval) {
                            fusedRegistered = false
                            nativeUpdates(interval)
                        }
                    }
            } else nativeUpdates(interval)
        } catch (_: SecurityException) {
            health(this, "permission")
            stopSelf()
        }
    }

    @Suppress("MissingPermission")
    private fun nativeUpdates(interval: Long) {
        if (registeredInterval != interval || nativeActive) return
        nativeActive = true
        try {
            native.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                interval,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        } catch (_: Exception) {
            health(this, "error")
            stopSelf()
        }
    }

    override fun onDestroy() {
        generation++
        registeredInterval = null
        fusedRegistered = false
        if (gnssRegistered) runCatching { native.unregisterGnssStatusCallback(gnss) }
        gnssRegistered = false
        val previousStatus = LiveTrackingHealth.state.value.service
        health(
            this,
            if (previousStatus in setOf("permission", "blocked", "error")) previousStatus
            else "stopped",
        )
        LiveTrackingHealth.state.update { it.copy(requestedInterval = 0) }
        runCatching { native.removeUpdates(listener) }
        runCatching { client.removeLocationUpdates(callback) }
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

        fun launch(c: Context, automatic: Boolean = false, motionResume: Boolean = false): Boolean =
            try {
                c.startForegroundService(
                    Intent(c, TrackingService::class.java)
                        .putExtra("automatic", automatic)
                        .putExtra("motion_resume", motionResume)
                )
                true
            } catch (_: IllegalStateException) {
                health(c, "blocked")
                false
            } catch (_: SecurityException) {
                health(c, "permission")
                false
            }

        private fun health(c: Context, status: String) {
            LiveTrackingHealth.state.update { it.copy(service = status) }
            c.getSharedPreferences("tracking_health", Context.MODE_PRIVATE)
                .edit()
                .putString("service", status)
                .putLong("updated", System.currentTimeMillis())
                .apply()
        }
    }
}
