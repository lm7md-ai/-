package sa.rihla.map

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SaudiMapTest {
    @Test
    fun geometryUsesTheStandardSignedPolylineFormatAndRejectsTruncation() {
        val points = decodeRoad("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertEquals(3.85, points.first().first, .000001)
        assertEquals(-12.02, points.first().second, .000001)
        try {
            decodeRoad("_")
            fail("Truncated geometry accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun bundledCountryDatabaseWorksOfflineForSearchAndRoadQueries() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(SaudiMap.search(context, "الرياض").any { it.name == "الرياض" })
        assertTrue(SaudiMap.search(context, "x' UNION SELECT").isEmpty())
        for ((lat, lon) in
            listOf(24.7136 to 46.6753, 21.49 to 39.18, 28.39 to 36.57, 16.89 to 42.55)) {
            val scene = SaudiMap.scene(context, lon - .01, lat - .01, lon + .01, lat + .01, 16)
            assertTrue("No roads near $lat,$lon", scene.roads.isNotEmpty())
            assertTrue(scene.roads.all { it.points.size >= 2 })
        }
        assertTrue(SaudiMap.file(context).length() > 100_000_000)
    }
}
