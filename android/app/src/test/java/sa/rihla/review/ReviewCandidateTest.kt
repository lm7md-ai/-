package sa.rihla.review

import android.Manifest
import android.content.Intent
import android.os.Looper
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import sa.rihla.RihlaApp
import sa.rihla.tracking.TrackingService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class)
class ReviewCandidateTest {
    private suspend fun until(condition: () -> Boolean) =
        withTimeout(10000) {
            while (!condition()) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }

    private fun expire(withActiveTrip: Boolean) = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        Shadows.shadowOf(app)
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            )
        app.settings.automatic(true)
        val owned = Robolectric.buildService(TrackingService::class.java).create()
        val requested = CompletableDeferred<Long>()
        val elapsed = CompletableDeferred<Unit>()
        try {
            owned.get().candidateWait = { ms ->
                requested.complete(ms)
                elapsed.await()
            }
            owned
                .get()
                .onStartCommand(
                    Intent(app, TrackingService::class.java).putExtra("automatic", true),
                    0,
                    1,
                )
            until { requested.isCompleted }
            assertEquals(150000L, requested.await())
            assertTrue(File("../README.md").readText().contains("150 seconds"))
            val active = if (withActiveTrip) app.repository.start(false) else null
            elapsed.complete(Unit)
            val timeout =
                TrackingService::class
                    .java
                    .getDeclaredField("pendingTimeout")
                    .apply { isAccessible = true }
                    .get(owned.get()) as Job
            until { timeout.isCompleted }
            assertEquals(!withActiveTrip, Shadows.shadowOf(owned.get()).isStoppedBySelf)
            assertEquals(active, app.repository.dao.active()?.id)
        } finally {
            owned.destroy()
            app.settings.clear()
        }
    }

    @Test fun aFailedCandidateExpiresAtTheDocumentedDeadline() = expire(false)

    @Test fun expiryCannotStopOrDeleteAnAlreadyCreatedTrip() = expire(true)
}
