package sa.rihla.tracking

import android.Manifest
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
    private suspend fun until(condition: suspend () -> Boolean) = withTimeout(10000) {
        while (!condition()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            delay(10)
        }
    }

    @Test
    fun delayedAutomaticTriggerCannotStartAfterAutomaticSettingWasDisabled() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        app.settings.automatic(false)
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(app, TrackingService::class.java).putExtra("automatic", true), 0, 1)
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
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        app.settings.automatic(true)
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        val service = controller.get()
        try {
            val automatic = Intent(app, TrackingService::class.java).putExtra("automatic", true)
            service.onStartCommand(automatic, 0, 1)
            val interval = TrackingService::class.java.getDeclaredField("registeredInterval").apply { isAccessible = true }
            until { interval.get(service) != null }
            @Suppress("UNCHECKED_CAST")
            val queue = TrackingService::class.java.getDeclaredField("queue").apply { isAccessible = true }.get(service) as Channel<Fix>
            val candidate = TrackingService::class.java.getDeclaredField("candidate").apply { isAccessible = true }.get(service)
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
}
