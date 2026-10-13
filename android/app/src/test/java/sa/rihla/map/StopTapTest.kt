package sa.rihla.map

import android.app.Application
import android.os.Looper
import android.view.MotionEvent
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sa.rihla.core.StopDetails

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StopTapTest {
    @Test
    fun aRealSingleTapOpensTheStopAndPlacePickingKeepsItsCoordinates() {
        val map = RouteMapView(RuntimeEnvironment.getApplication())
        map.layout(0, 0, 1000, 600)
        map.center(21.5, 39.2)
        val stop = StopDetails(1, 1000, 5000, 21.5, 39.2)
        map.stops = listOf(stop)
        var selected: StopDetails? = null
        map.onStopPick = { selected = it }
        fun tap(time: Long) {
            val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 510f, 300f, 0)
            val up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, 510f, 300f, 0)
            try {
                map.onTouchEvent(down)
                map.onTouchEvent(up)
            } finally {
                down.recycle()
                up.recycle()
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        }
        tap(android.os.SystemClock.uptimeMillis())
        assertEquals(stop, selected)
        selected = null
        var coordinate: Pair<Double, Double>? = null
        map.onPick = { a, b -> coordinate = a to b }
        tap(android.os.SystemClock.uptimeMillis())
        assertNull(selected)
        assertNotNull(coordinate)
        assertEquals(21.5, coordinate!!.first, 0.001)
        map.pickStopsWithSelection = true
        coordinate = null
        tap(android.os.SystemClock.uptimeMillis())
        assertEquals(stop, selected)
        assertNull(coordinate)
    }
}
