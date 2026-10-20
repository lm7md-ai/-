package sa.rihla.map

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
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
import sa.rihla.data.RoutePoint

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RouteInspectionTest {
    @Test
    fun tapAndDragInspectOriginalPointsAfterSimplificationAndDoNotPickAPlace() {
        val map = RouteMapView(RuntimeEnvironment.getApplication())
        map.layout(0, 0, 1000, 600)
        val points =
            (0..100).map { i ->
                RoutePoint(
                    outingId = 1,
                    latitude = 21.5,
                    longitude = 39.2 + (i - 50) * .00001,
                    accuracy = 5.0,
                    speed = 10.0,
                    altitude = 7.0,
                    bearing = 90.0,
                    recordedAt = 1000 + i * 3000L,
                )
            }
        map.points = points
        map.display = RouteDisplayOptions(inspect = true)
        map.navigate(21.5, 39.2)
        var picked: RoutePointSelection? = null
        var place = false
        map.onPointPick = { picked = it }
        map.onPick = { _, _ -> place = true }
        map.draw(Canvas(Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)))
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!map.isRouteReady && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        assertTrue(map.isRouteReady)
        assertTrue(map.displayStats!!.drawn < points.size)
        fun event(action: Int, x: Float, time: Long) {
            val e = MotionEvent.obtain(1000, time, action, x, 300f, 0)
            try {
                map.onTouchEvent(e)
            } finally {
                e.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, 500f, 1000)
        assertEquals(points[50], picked!!.point)
        event(MotionEvent.ACTION_MOVE, 520f, 1050)
        assertTrue(picked!!.index > 50)
        event(MotionEvent.ACTION_UP, 520f, 1100)
        assertFalse(place)
        RouteMapView::class
            .java
            .getDeclaredMethod("onDetachedFromWindow")
            .apply { isAccessible = true }
            .invoke(map)
    }

    @Test
    fun matchingQueriesUseLocalRawGeometryAndRejectWholeCountryBounds() {
        val context = RuntimeEnvironment.getApplication()
        val roads = SaudiMap.matchingRoads(context, 39.175, 21.485, 39.185, 21.495)
        assertFalse(roads.clipped)
        assertTrue(roads.roads.isNotEmpty())
        assertTrue(roads.roads.none { it.kind == "coast" })
        assertTrue(RoadMatcher(roads.roads).available)
        assertTrue(SaudiMap.matchingRoads(context, 34.0, 16.0, 56.0, 33.0).clipped)
    }
}
