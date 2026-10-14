package sa.rihla.tracking

import android.app.Application
import android.location.Location
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocationPickerTest {
    @Test
    fun currentLocationCannotConfigureHomeFromMockStaleOrInvalidFix() {
        val location = Location("gps").apply {
            accuracy = 5f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        assertTrue(LocationPicker.usable(location))
        location.accuracy = 0f
        assertFalse(LocationPicker.usable(location))
        location.accuracy = 5f
        location.isMock = true
        assertFalse(LocationPicker.usable(location))
        location.isMock = false
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 61_000_000_000
        assertFalse(LocationPicker.usable(location))
    }
}
