package sa.rihla.tracking

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
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
import sa.rihla.audio.AutoStopBookmarkManager
import sa.rihla.audio.SpeedAdaptiveAudio
import sa.rihla.core.*
import sa.rihla.data.UnifiedBackupManager

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
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val listener = LocationListener { l -> receive(l, "GPS") }
    @Volatile private var lastProviderFix = SystemClock.elapsedRealtime()
    @Volatile private var nativeActive = false
    @Volatile private var precise = false
    @Volatile private var stationaryDeepSleepEnabled = true
    @Volatile private var speedAdaptiveAudioEnabled = true
    private var pendingTimeout: Job? = null
    private val qualityGate = FixQualityGate()
    private var generation = 0L
    private var fusedRegistered = false
    private var lastFusedAttempt = 0L
    private var fusedRecoveryAt = 0L
    private var lastHeartbeat = 0L
    private var lastSpeed = 0.0
    private var lastAccuracy = 15.0
    private val turnDetector = TurnDetailDetector()
    private var turnDetails = true
    private var turnBoostUntil = 0L
    private var boostUntil = 0L
    private var instantWakeUntil = 0L
    @Volatile private var inDeepSleep = false
    @Volatile private var bluetoothDisconnectWallMs = 0L
    private var autoPendingStartedAt = 0L
    private var motionArmed = false

    private val significantMotionListener =
        object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                motionArmed = false
                wakeFromDeepSleep(this@TrackingService, "significant_motion")
            }
        }

    private fun armSignificantMotionIfAvailable() {
        if (motionArmed) return
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        motionArmed = runCatching { sensorManager.requestTriggerSensor(significantMotionListener, sensor) }.getOrDefault(false)
    }

    private fun disarmSignificantMotion() {
        if (!motionArmed) return
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (sensor != null) runCatching { sensorManager.cancelTriggerSensor(significantMotionListener, sensor) }
        motionArmed = false
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
        val allowIndoorHome = l.accuracy in 60.0f..160.0f && (!l.hasSpeed() || l.speed < 1.5f)
        if (!usableLocation(age, l.accuracy.toDouble(), mock) && !(allowIndoorHome && !mock && age in 0..60_000)) {
            rejected(if (mock) "MOCK" else if (age !in 0..60_000) "STALE" else "ACCURACY")
            return
        }
        val fix = l.trackingFix(source)
        val reason = qualityGate.inspect(fix, allowRelaxedIndoorAccuracy = allowIndoorHome)
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
                liveSpeedKmh = if (fix.speed.isFinite() && fix.speed > 0.5) (fix.speed * 3.6).toFloat() else 0f,
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

        // Main fix processing loop
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
                            autoPendingStartedAt = 0L
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
                            liveSpeedKmh = if (accepted && f.speed > 0.5) (f.speed * 3.6).toFloat() else 0f,
                        )
                    }
                    if (outcome.enteredUniversity) {
                        scope.launch { AutoStopBookmarkManager.onStopOrArrival(app, "وصول للجامعة", f.time) }
                    } else if (outcome.confirmedStop) {
                        scope.launch { AutoStopBookmarkManager.onStopOrArrival(app, "توقف بالسيارة", f.time) }
                    }
                    if (outcome.ended) {
                        SpeedAdaptiveAudio.clearOuting()
                        scope.launch {
                            AutoStopBookmarkManager.onStopOrArrival(app, "وصول للبيت", f.time)
                            runCatching { UnifiedBackupManager(app).runAutoBackupIfDue(forceOnHomeArrival = true) }
                        }
                        stopSelf()
                        return@withLock true
                    }
                    val state = app.repository.dao.active()?.state ?: return@withLock false
                    if (state.phase == "TRACKING_DRIVING" && bluetoothDisconnectWallMs > 0L) {
                        bluetoothDisconnectWallMs = 0L
                    }
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
                        lastAccuracy = f.accuracy
                        SpeedAdaptiveAudio.updateSpeed(
                            speedMetersPerSec = if (state.stopStart > 0) 0.0 else f.speed,
                            outingActive = true,
                            enabled = speedAdaptiveAudioEnabled,
                        )
                    }
                    val engineRef = TrackingEngine()
                    val wantDeepSleep =
                        stationaryDeepSleepEnabled &&
                            nowElapsed >= instantWakeUntil &&
                            nowElapsed >= boostUntil &&
                            engineRef.shouldEnterStationaryDeepSleep(state, f.time)
                    val shouldSlow =
                        state.stopStart > 0 &&
                            nowElapsed >= boostUntil &&
                            nowElapsed >= turnBoostUntil &&
                            nowElapsed >= instantWakeUntil
                    val nextInterval =
                        adaptiveTrackingInterval(
                            stopped = shouldSlow,
                            precise = precise,
                            speed = lastSpeed,
                            boost = nowElapsed < boostUntil,
                            turn = turnDetails && nowElapsed < turnBoostUntil,
                            straightRoad = turnDetails && turnDetector.isStraightRoad,
                            accuracy = lastAccuracy,
                            deepSleep = wantDeepSleep,
                            instantWakeup = nowElapsed < instantWakeUntil,
                        )
                    if (wantDeepSleep != inDeepSleep || shouldSlow != slow || registeredInterval != nextInterval) {
                        slow = shouldSlow
                        if (wantDeepSleep && !inDeepSleep) {
                            val sleepLat = if (state.stopStart > 0 && state.stillLat != 0.0) state.stillLat else (state.lastLat ?: f.lat)
                            val sleepLon = if (state.stopStart > 0 && state.stillLon != 0.0) state.stillLon else (state.lastLon ?: f.lon)
                            HomeGeofences.registerStationaryWake(this@TrackingService, sleepLat, sleepLon)
                            armSignificantMotionIfAvailable()
                            SpeedAdaptiveAudio.updateSpeed(0.0, true, speedAdaptiveAudioEnabled)
                        } else if (!wantDeepSleep && inDeepSleep) {
                            HomeGeofences.clearStationaryWake(this@TrackingService)
                            disarmSignificantMotion()
                        }
                        inDeepSleep = wantDeepSleep
                        LiveTrackingHealth.state.update { it.copy(deepSleep = inDeepSleep) }
                        withContext(Dispatchers.Main) { requestUpdates() }
                    }
                    false
                }
                if (ended) break
            }
        }

        // Independent 30-second Watchdog Coroutine Ticker (never depends on LocationCallback)
        scope.launch {
            val engineRef = TrackingEngine()
            while (isActive) {
                delay(30_000L)
                val stoppedService = commandLock.withLock {
                    val nowWall = System.currentTimeMillis()
                    val nowElapsed = SystemClock.elapsedRealtime()
                    val active = app.repository.dao.active()

                    // 1. Clean up stuck checking_driving candidate if no trip confirmed after 3 minutes
                    if (active == null) {
                        if (autoPending && autoPendingStartedAt > 0L && nowElapsed - autoPendingStartedAt >= 180_000L) {
                            autoPending = false
                            candidate.reset()
                            pendingFixes.clear()
                            stopSelf()
                            return@withLock true
                        }
                        return@withLock false
                    }

                    // 2. Complete AT_HOME_PENDING_END or Bluetooth-disconnect near-home auto-end without needing new fixes
                    if (app.repository.evaluateWatchdogAutoEnd(nowWall, bluetoothDisconnectWallMs)) {
                        SpeedAdaptiveAudio.clearOuting()
                        scope.launch {
                            AutoStopBookmarkManager.onStopOrArrival(app, "وصول للبيت", nowWall)
                            runCatching { UnifiedBackupManager(app).runAutoBackupIfDue(forceOnHomeArrival = true) }
                        }
                        stopSelf()
                        return@withLock true
                    }

                    // 3. Enter Stationary Deep Sleep if parked > 5 min and GPS stopped sending fixes
                    if (
                        stationaryDeepSleepEnabled &&
                            !inDeepSleep &&
                            nowElapsed >= instantWakeUntil &&
                            engineRef.shouldEnterStationaryDeepSleep(active.state, nowWall)
                    ) {
                        inDeepSleep = true
                        slow = true
                        val st = active.state
                        val sleepLat = if (st.stopStart > 0 && st.stillLat != 0.0) st.stillLat else st.lastLat
                        val sleepLon = if (st.stopStart > 0 && st.stillLon != 0.0) st.stillLon else st.lastLon
                        if (sleepLat != null && sleepLon != null) {
                            HomeGeofences.registerStationaryWake(this@TrackingService, sleepLat, sleepLon)
                        }
                        armSignificantMotionIfAvailable()
                        LiveTrackingHealth.state.update { it.copy(deepSleep = true, liveSpeedKmh = 0f) }
                        SpeedAdaptiveAudio.updateSpeed(0.0, true, speedAdaptiveAudioEnabled)
                        withContext(Dispatchers.Main) { requestUpdates() }
                        return@withLock false
                    }

                    // 4. Provider stall recovery (switch/rebind Fused -> GPS_PROVIDER when not in deep sleep)
                    if (!inDeepSleep && registeredInterval != null) {
                        val stallThreshold = if (slow) 90_000L else 30_000L
                        if (!nativeActive && nowElapsed - lastProviderFix > stallThreshold) {
                            withContext(Dispatchers.Main) {
                                registeredInterval?.let { nativeUpdates(it) }
                            }
                        }
                        if (nativeActive && !fusedRegistered && nowElapsed - lastFusedAttempt > 120_000L) {
                            withContext(Dispatchers.Main) {
                                registeredInterval?.let { requestFused(it, generation) }
                            }
                        }
                    }
                    false
                }
                if (stoppedService) break
            }
        }

        // Fast 15-second provider fallback check while actively driving
        scope.launch {
            while (isActive) {
                delay(15_000)
                if (
                    !inDeepSleep &&
                        !nativeActive &&
                        registeredInterval != null &&
                        SystemClock.elapsedRealtime() - lastProviderFix >
                            (if (slow) 90_000 else 30_000)
                )
                    withContext(Dispatchers.Main) {
                        registeredInterval?.let { nativeUpdates(it) }
                    }
            }
        }

        // Live settings sync
        scope.launch {
            app.settings.flow.collect { preferences ->
                commandLock.withLock {
                    val changed =
                        preferences.preciseTracking != precise ||
                            preferences.turnDetails != turnDetails ||
                            preferences.stationaryDeepSleep != stationaryDeepSleepEnabled
                    precise = preferences.preciseTracking
                    turnDetails = preferences.turnDetails
                    stationaryDeepSleepEnabled = preferences.stationaryDeepSleep
                    speedAdaptiveAudioEnabled = preferences.speedAdaptiveAudio
                    SpeedAdaptiveAudio.setEnabled(preferences.speedAdaptiveAudio)
                    if (!stationaryDeepSleepEnabled && inDeepSleep) {
                        inDeepSleep = false
                        HomeGeofences.clearStationaryWake(this@TrackingService)
                        disarmSignificantMotion()
                        LiveTrackingHealth.state.update { it.copy(deepSleep = false) }
                    }
                    if (changed) {
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
                    SpeedAdaptiveAudio.clearOuting()
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }
        if (i?.action == "BT_DISCONNECTED") {
            val ts = i.getLongExtra("disconnect_time", System.currentTimeMillis())
            scope.launch {
                commandLock.withLock {
                    val active = app.repository.dao.active() ?: return@withLock
                    bluetoothDisconnectWallMs = ts
                    // If already dwelling at home for >= 60s when Lancer turns off, finish immediately!
                    val home = app.repository.area(app.repository.dao.places().find { it.type == "HOME" })
                    val st = active.state
                    if (
                        home != null &&
                            st.lastLat != null &&
                            st.lastLon != null &&
                            (st.leftHome || st.meters >= 100.0) &&
                            home.distanceOutsideBoundaryMeters(st.lastLat, st.lastLon) <= 60.0 &&
                            st.homeSince > 0L &&
                            ts - st.homeSince >= 60_000L
                    ) {
                        if (app.repository.evaluateWatchdogAutoEnd(ts + 60_000L, ts)) {
                            SpeedAdaptiveAudio.clearOuting()
                            AutoStopBookmarkManager.onStopOrArrival(app, "وصول للبيت", ts)
                            runCatching { UnifiedBackupManager(app).runAutoBackupIfDue(forceOnHomeArrival = true) }
                            stopSelf()
                        }
                    }
                }
            }
            return START_STICKY
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
                val isWakeupSignal =
                    i?.getBooleanExtra("bluetooth_wakeup", false) == true ||
                        i?.getBooleanExtra("geofence_exit_wakeup", false) == true
                val isMotionResume = i?.getBooleanExtra("motion_resume", false) == true
                if (
                    i?.getBooleanExtra("automatic", false) == true &&
                        autoPending &&
                        registeredInterval != null &&
                        !isWakeupSignal
                )
                    return@withLock
                autoPending =
                    i?.getBooleanExtra("automatic", false) == true &&
                        app.repository.dao.active() == null
                if (autoPending) {
                    candidate.reset()
                    pendingFixes.clear()
                    autoPendingStartedAt = SystemClock.elapsedRealtime()
                }
                health(this@TrackingService, if (autoPending) "checking_driving" else "running")
                precise = preferences.preciseTracking
                turnDetails = preferences.turnDetails
                stationaryDeepSleepEnabled = preferences.stationaryDeepSleep
                speedAdaptiveAudioEnabled = preferences.speedAdaptiveAudio
                slow = app.repository.dao.active()?.state?.stopStart?.let { it > 0 } == true
                if (isWakeupSignal || isMotionResume) {
                    val wasSleeping = inDeepSleep
                    inDeepSleep = false
                    slow = false
                    bluetoothDisconnectWallMs = 0L
                    HomeGeofences.clearStationaryWake(this@TrackingService)
                    disarmSignificantMotion()
                    LiveTrackingHealth.state.update { it.copy(deepSleep = false) }
                    if (isWakeupSignal || wasSleeping) {
                        // 1s High-Accuracy instant wake-up burst on Lancer BT connect / geofence exit / deep sleep wake
                        instantWakeUntil = SystemClock.elapsedRealtime() + 15_000L
                    }
                    boostUntil = SystemClock.elapsedRealtime() + 30_000L
                }
                pendingTimeout?.cancel()
                if (autoPending)
                    pendingTimeout = scope.launch {
                        delay(300_000)
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
        val nowElapsed = SystemClock.elapsedRealtime()
        val interval =
            adaptiveTrackingInterval(
                stopped = slow,
                precise = precise,
                speed = lastSpeed,
                boost = nowElapsed < boostUntil,
                turn = turnDetails && nowElapsed < turnBoostUntil,
                straightRoad = turnDetails && turnDetector.isStraightRoad,
                accuracy = lastAccuracy,
                deepSleep = inDeepSleep,
                instantWakeup = nowElapsed < instantWakeUntil,
            )
        if (registeredInterval == interval) return
        generation++
        val current = generation
        client.removeLocationUpdates(callback)
        native.removeUpdates(listener)
        registeredInterval = interval
        LiveTrackingHealth.state.update {
            it.copy(requestedInterval = interval, deepSleep = inDeepSleep)
        }
        nativeActive = false
        fusedRegistered = false
        fusedRecoveryAt = 0
        lastProviderFix = nowElapsed
        // NOTE: GnssStatus.Callback is intentionally NOT registered in background TrackingService
        // to eliminate continuous 1Hz CPU wakeups on RedMagic 11 Pro; it lives strictly in TrackingDiagnosticsScreen.
        requestFused(interval, current)
    }

    @Suppress("MissingPermission")
    private fun requestFused(interval: Long, current: Long) {
        if (registeredInterval != interval || generation != current) return
        lastFusedAttempt = SystemClock.elapsedRealtime()
        val priority =
            if (inDeepSleep) Priority.PRIORITY_BALANCED_POWER_ACCURACY
            else Priority.PRIORITY_HIGH_ACCURACY
        val minInterval =
            when {
                inDeepSleep -> 60_000L
                slow -> if (precise) 5000L else 15000L
                else -> 1000L
            }
        val request =
            LocationRequest.Builder(priority, interval)
                .setMinUpdateIntervalMillis(minInterval)
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
                            if (!inDeepSleep) nativeUpdates(interval)
                        }
                    }
            } else if (!inDeepSleep) {
                nativeUpdates(interval)
            } else {
                nativePassiveUpdates(interval)
            }
        } catch (_: SecurityException) {
            health(this, "permission")
            stopSelf()
        }
    }

    @Suppress("MissingPermission")
    private fun nativePassiveUpdates(interval: Long) {
        if (registeredInterval != interval || nativeActive) return
        nativeActive = true
        runCatching {
            native.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                interval,
                50f,
                listener,
                Looper.getMainLooper(),
            )
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
        inDeepSleep = false
        HomeGeofences.clearStationaryWake(this)
        disarmSignificantMotion()
        SpeedAdaptiveAudio.clearOuting()
        val previousStatus = LiveTrackingHealth.state.value.service
        health(
            this,
            if (previousStatus in setOf("permission", "blocked", "error")) previousStatus
            else "stopped",
        )
        LiveTrackingHealth.state.update {
            it.copy(requestedInterval = 0, deepSleep = false, liveSpeedKmh = 0f)
        }
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

        fun launch(
            c: Context,
            automatic: Boolean = false,
            motionResume: Boolean = false,
            bluetoothWakeup: Boolean = false,
        ): Boolean =
            try {
                c.startForegroundService(
                    Intent(c, TrackingService::class.java)
                        .putExtra("automatic", automatic)
                        .putExtra("motion_resume", motionResume)
                        .putExtra("bluetooth_wakeup", bluetoothWakeup)
                )
                true
            } catch (_: IllegalStateException) {
                health(c, "blocked")
                false
            } catch (_: SecurityException) {
                health(c, "permission")
                false
            }

        fun wakeFromDeepSleep(c: Context, reason: String) {
            if (!fineAllowed(c)) return
            runCatching {
                c.startForegroundService(
                    Intent(c, TrackingService::class.java)
                        .putExtra("motion_resume", true)
                        .putExtra("geofence_exit_wakeup", reason == "geofence_exit" || reason == "significant_motion")
                        .putExtra("bluetooth_wakeup", reason == "bluetooth_connected")
                )
            }
        }

        fun notifyBluetoothDisconnected(c: Context, timestampMs: Long = System.currentTimeMillis()) {
            runCatching {
                c.startService(
                    Intent(c, TrackingService::class.java)
                        .setAction("BT_DISCONNECTED")
                        .putExtra("disconnect_time", timestampMs)
                )
            }
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
