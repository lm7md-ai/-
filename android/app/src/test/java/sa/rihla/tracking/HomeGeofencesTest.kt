package sa.rihla.tracking

import android.app.Application
import com.google.android.gms.location.Geofence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sa.rihla.data.SavedPlace

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeGeofencesTest {
    @Test
    fun homeTriggerListensForDepartureAndReturnWithoutSyntheticInitialDeparture() {
        val request = HomeGeofences.request(SavedPlace("HOME", 21.5, 39.2, 150.0))
        assertEquals(0, request.initialTrigger)
        assertEquals(1, request.geofences.size)
        val fence = request.geofences.single()
        assertEquals("HOME", fence.requestId)
        assertEquals(Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_ENTER, fence.transitionTypes)
        assertEquals(150.0, fence.radius.toDouble(), 0.01)
        assertEquals(Geofence.NEVER_EXPIRE, fence.expirationTime)
    }

    @Test
    fun smallHomeHasHundredMeterWakeupRadiusToReduceMissedTriggers() {
        val fence = HomeGeofences.request(SavedPlace("HOME", 21.5, 39.2, 50.0)).geofences.single()
        assertEquals(100.0, fence.radius.toDouble(), 0.01)
    }
}
