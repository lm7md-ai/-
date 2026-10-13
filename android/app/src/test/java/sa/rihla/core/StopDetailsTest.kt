package sa.rihla.core

import org.junit.Assert.*
import org.junit.Test
import sa.rihla.data.*

class StopDetailsTest {
    private val outing =
        Outing(
            id = 7,
            startedAt = 1000,
            state = EngineState(stopStart = 10000, stillLat = 21.5, stillLon = 39.2),
        )
    private val events =
        listOf(
            JourneyEvent(1, 7, "STOP", 2000, 5000, 21.0, 39.0, true),
            JourneyEvent(2, 7, "VISIT", 2000, 5000, 21.0, 39.0, true),
        )

    @Test
    fun includesCompletedAndLiveStopsWithoutDuplicatingVisits() {
        val stops = stopDetails(outing, events)
        assertEquals(2, stops.size)
        assertEquals(3000L, stops[0].durationMs(20000))
        assertNull(stops[1].endedAt)
        assertEquals(10000L, stops[1].durationMs(20000))
        assertEquals(11000L, stops[1].durationMs(21000))
        assertEquals(0L, stops[1].durationMs(9000))
        assertEquals(1, stopDetails(outing.copy(endedAt = 11000), events).size)
    }

    @Test
    fun touchTargetSelectsNearestAndMostRecentOverlappingStop() {
        val stops = stopDetails(outing, events)
        assertEquals(stops.last(), hitStop(stops, 110f, 100f, 24f) { _, _ -> 100f to 100f })
        assertNull(hitStop(stops, 125f, 100f, 24f) { _, _ -> 100f to 100f })
        assertEquals(
            stops.first(),
            hitStop(stops, 10f, 10f, 24f) { lat, _ ->
                if (lat == 21.0) 11f to 10f else 20f to 10f
            },
        )
    }
}
