package sa.rihla.map

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sa.rihla.data.RoutePoint

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouteAiTest {
    private fun section() =
        RouteAnalysis(
                (0..2).map {
                    RoutePoint(
                        outingId = 987,
                        latitude = 21.123456,
                        longitude = 39.654321 + it * .001,
                        accuracy = 7.0,
                        speed = 8.0,
                        altitude = 12.0,
                        bearing = 90.0,
                        recordedAt = 1000 + it * 10000L,
                        distanceDelta = 80.0,
                    )
                },
                emptyList(),
            )
            .summary(0, 2)!!

    @Test
    fun factsExcludeLocationsAndIdentifiersAndKeepMeasuredMetrics() {
        val json = org.json.JSONObject(routeAiFacts(section()))
        assertEquals(160.0, json.getDouble("recorded_meters"), 0.0)
        assertEquals(20000L, json.getLong("elapsed_ms"))
        assertFalse(json.has("latitude"))
        assertFalse(json.has("outingId"))
        assertFalse(json.toString().contains("21.123456"))
    }

    @Test
    fun inferenceProbeRunsBeforeReportAndValidOutputIsDisplayed() = runBlocking {
        var calls = 0
        val result =
            generateRouteReport(section()) { _, prompt ->
                calls++
                if (calls == 1) "{\"ok\":true}"
                else {
                    assertTrue(prompt.contains("recorded_meters"))
                    "{\"observations\":[\"ملاحظة اختبارية\"],\"limitations\":[\"بيانات محدودة\"]}"
                }
            }
        assertEquals(2, calls)
        assertTrue(result.contains("بيانات محدودة"))
    }

    @Test
    fun failedProbeDoesNotPretendTheModelWorksOrRequestAReport() = runBlocking {
        var calls = 0
        try {
            generateRouteReport(section()) { _, _ ->
                calls++
                "{\"ok\":false}"
            }
            fail("Failed probe must reject inference")
        } catch (_: IllegalStateException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun malformedOrEmptyReportIsRejected() = runBlocking {
        var calls = 0
        try {
            generateRouteReport(section()) { _, _ ->
                if (++calls == 1) "{\"ok\":true}"
                else "{\"observations\":[],\"limitations\":[\"test\"]}"
            }
            fail("Empty output must fail")
        } catch (_: IllegalArgumentException) {
            assertEquals(2, calls)
        }
    }
}
