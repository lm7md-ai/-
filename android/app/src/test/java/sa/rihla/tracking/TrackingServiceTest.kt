package sa.rihla.tracking

import android.Manifest
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import sa.rihla.RihlaApp
import sa.rihla.core.Fix

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class)
class TrackingServiceTest {
    private suspend fun until(condition: suspend () -> Boolean) =
        withTimeout(10000) {
            while (!condition()) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }

    @Test
    fun delayedAutomaticTriggerCannotStartAfterAutomaticSettingWasDisabled() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        Shadows.shadowOf(app)
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            )
        app.settings.automatic(false)
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        try {
            controller
                .get()
                .onStartCommand(
                    Intent(app, TrackingService::class.java).putExtra("automatic", true),
                    0,
                    1,
                )
            until { Shadows.shadowOf(controller.get()).isStoppedBySelf }
            assertNull(app.repository.dao.active())
            assertTrue(app.repository.dao.outings().isEmpty())
        } finally {
            controller.destroy()
            app.settings.clear()
            app.database.close()
        }
    }

    @Test
    fun repeatedAutomaticTriggerKeepsGpsEvidenceAndCreatesOnlyOneAutomaticTrip() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        Shadows.shadowOf(app)
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            )
        app.settings.automatic(true)
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        val service = controller.get()
        try {
            val automatic = Intent(app, TrackingService::class.java).putExtra("automatic", true)
            service.onStartCommand(automatic, 0, 1)
            val interval =
                TrackingService::class.java.getDeclaredField("registeredInterval").apply {
                    isAccessible = true
                }
            until { interval.get(service) != null }
            @Suppress("UNCHECKED_CAST")
            val queue =
                TrackingService::class
                    .java
                    .getDeclaredField("queue")
                    .apply { isAccessible = true }
                    .get(service) as Channel<Fix>
            val candidate =
                TrackingService::class
                    .java
                    .getDeclaredField("candidate")
                    .apply { isAccessible = true }
                    .get(service)
            val origin = candidate.javaClass.getDeclaredField("first").apply { isAccessible = true }
            queue.send(Fix(21.5, 39.2, 1_000_000, 5.0))
            until { origin.get(candidate) != null }
            service.onStartCommand(automatic, 0, 2)
            queue.send(Fix(21.501, 39.2, 1_010_000, 5.0, 11.0))
            until { app.repository.dao.active() != null }
            val trip = app.repository.dao.active()!!
            assertTrue(trip.automatic)
            assertEquals(1_000_000L, trip.startedAt)
            service.onStartCommand(automatic, 0, 3)
            queue.send(Fix(21.502, 39.2, 1_020_000, 5.0, 11.0))
            until { app.repository.dao.active()?.state?.lastTime == 1_020_000L }
            assertEquals(1, app.repository.dao.outings().size)
            assertTrue(app.repository.dao.active()!!.state.meters > 150)
        } finally {
            controller.destroy()
            app.settings.clear()
            app.database.close()
        }
    }

    @Test
    fun automaticStartReplaysTheConfirmedCurveInsteadOfOnlyItsEndpoints() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        Shadows.shadowOf(app)
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            )
        app.settings.automatic(true)
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(
                Intent(app, TrackingService::class.java).putExtra("automatic", true),
                0,
                1,
            )
            val interval =
                TrackingService::class.java.getDeclaredField("registeredInterval").apply {
                    isAccessible = true
                }
            until { interval.get(service) != null }
            @Suppress("UNCHECKED_CAST")
            val queue =
                TrackingService::class
                    .java
                    .getDeclaredField("queue")
                    .apply { isAccessible = true }
                    .get(service) as Channel<Fix>
            val fixes =
                listOf(
                    Fix(21.5, 39.2, 1_000_000, 3.0, 6.0),
                    Fix(21.5003, 39.2, 1_003_000, 3.0, 6.0),
                    Fix(21.5003, 39.2003, 1_006_000, 3.0, 6.0),
                    Fix(21.5006, 39.2003, 1_010_000, 3.0, 6.0),
                )
            fixes.forEach { queue.send(it) }
            until { app.repository.dao.active()?.state?.lastTime == 1_010_000L }
            val trip = app.repository.dao.active()!!
            val points = app.repository.dao.points(trip.id).first()
            assertEquals(fixes.map { it.time }, points.map { it.recordedAt })
            assertEquals(4L, LiveTrackingHealth.state.value.acceptedCount)
            assertTrue(
                trip.state.meters >
                    sa.rihla.core.distance(
                        fixes.first().lat,
                        fixes.first().lon,
                        fixes.last().lat,
                        fixes.last().lon,
                    ) + 15
            )
        } finally {
            controller.destroy()
            app.settings.clear()
            app.database.close()
        }
    }

    @Test
    fun motionTriggerWakesAStoppedTripAndDestroyClearsItsLiveServiceStatus() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        Shadows.shadowOf(app)
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            )
        app.settings.automatic(true)
        val id = app.repository.start(false, 1_000_000)
        app.repository.record(Fix(21.5, 39.2, 1_000_000, 5.0))
        app.repository.record(Fix(21.5, 39.2, 1_180_000, 5.0))
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        val service = controller.get()
        try {
            val interval =
                TrackingService::class.java.getDeclaredField("registeredInterval").apply {
                    isAccessible = true
                }
            service.onStartCommand(Intent(app, TrackingService::class.java), 0, 1)
            until { interval.get(service) == 30000L }
            service.onStartCommand(
                Intent(app, TrackingService::class.java).putExtra("motion_resume", true),
                0,
                2,
            )
            until { interval.get(service) == 5000L }
            assertEquals(id, app.repository.dao.active()!!.id)
            assertEquals(1, app.repository.dao.outings().size)
        } finally {
            controller.destroy()
        }
        assertEquals("stopped", LiveTrackingHealth.state.value.service)
        assertEquals(0L, LiveTrackingHealth.state.value.requestedInterval)
        app.settings.clear()
        app.database.close()
    }

    @Test
    fun realLocationCallbackRejectsSpikesAndRecoversFromGpsToFused() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        Shadows.shadowOf(app)
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            )
        app.repository.start(false)
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(Intent(app, TrackingService::class.java), 0, 1)
            val interval =
                TrackingService::class.java.getDeclaredField("registeredInterval").apply {
                    isAccessible = true
                }
            until { interval.get(service) != null }
            val receive =
                TrackingService::class
                    .java
                    .getDeclaredMethod(
                        "receive",
                        android.location.Location::class.java,
                        String::class.java,
                    )
                    .apply { isAccessible = true }
            val wallBase = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
            fun location(meters: Double): android.location.Location =
                android.location.Location("gps").apply {
                    latitude = 21.5 + meters / 111194.9266
                    longitude = 39.2
                    accuracy = 5f
                    speed = 3f
                    elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    time = wallBase + android.os.SystemClock.elapsedRealtime()
                }
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(10))
            receive.invoke(service, location(0.0), "GPS")
            until { LiveTrackingHealth.state.value.acceptedCount == 1L }
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
            receive.invoke(service, location(1000.0), "GPS")
            assertEquals("JUMP", LiveTrackingHealth.state.value.reason)
            val native =
                TrackingService::class.java.getDeclaredField("nativeActive").apply {
                    isAccessible = true
                }
            native.set(service, true)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(4))
            receive.invoke(service, location(15.0), "Fused")
            until { LiveTrackingHealth.state.value.acceptedCount == 2L }
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3))
            receive.invoke(service, location(24.0), "Fused")
            until { LiveTrackingHealth.state.value.acceptedCount == 3L }
            assertEquals(false, native.get(service))
            val trip = app.repository.dao.active()!!
            assertEquals(3, app.repository.dao.points(trip.id).first().size)
            assertEquals(24.0, trip.state.meters, 1.0)
        } finally {
            controller.destroy()
            app.settings.clear()
            app.database.close()
        }
    }

    @Test
    fun repositoryOutcomeDistinguishesDuplicateRejectionFromASavedFix() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        try {
            app.repository.start(false, 1_000_000)
            val fix = Fix(21.5, 39.2, 1_000_000, 5.0)
            val first = app.repository.recordDetailed(fix)
            assertTrue(first.accepted)
            assertFalse(first.ended)
            assertFalse(app.repository.recordDetailed(fix).accepted)
            app.repository.end()
            assertFalse(app.repository.recordDetailed(fix.copy(time = 1_010_000)).accepted)
            assertEquals(
                1,
                app.repository.dao.points(app.repository.dao.outings().single().id).first().size,
            )
        } finally {
            app.database.close()
        }
    }

    @Test
    fun gradualTurnActuallyChangesBalancedProviderIntervalAndDisablingRestoresIt() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        app.settings.precise(false)
        app.settings.turnDetails(true)
        app.repository.start(false, 1_000_000)
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(Intent(app, TrackingService::class.java), 0, 1)
            val interval =
                TrackingService::class.java.getDeclaredField("registeredInterval").apply {
                    isAccessible = true
                }
            until { interval.get(service) == 5000L }
            @Suppress("UNCHECKED_CAST")
            val queue =
                TrackingService::class
                    .java
                    .getDeclaredField("queue")
                    .apply { isAccessible = true }
                    .get(service) as Channel<Fix>
            for (i in 0..3) queue.send(
                Fix(
                    21.5 + i * .0003,
                    39.2,
                    1_000_000 + i * 3000L,
                    5.0,
                    speed = 10.0,
                    bearing = i * 10.0,
                )
            )
            until { interval.get(service) == 2000L }
            app.settings.turnDetails(false)
            until { interval.get(service) == 5000L }
            assertEquals(
                4,
                app.repository.dao.points(app.repository.dao.active()!!.id).first().size,
            )
        } finally {
            controller.destroy()
            app.settings.clear()
            app.database.close()
        }
    }
}
