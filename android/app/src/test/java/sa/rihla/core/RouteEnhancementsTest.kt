package sa.rihla.core

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test
import sa.rihla.data.*

class RouteEnhancementsTest {
    @Test
    fun gradualTurnsAndWraparoundTriggerShortDetailRequestsWhileNoiseDoesNot() {
        val detector = TurnDetailDetector()
        fun fix(i: Int, bearing: Double) =
            Fix(21.5, 39.2, i * 3000L + 1000, 5.0, speed = 10.0, bearing = bearing)
        assertFalse(detector.inspect(fix(0, 350.0)))
        assertFalse(detector.inspect(fix(1, 0.0)))
        assertFalse(detector.inspect(fix(2, 10.0)))
        assertTrue(detector.inspect(fix(3, 20.0)))
        detector.reset()
        (0..20).forEach { assertFalse(detector.inspect(fix(it, if (it % 2 == 0) 0.0 else 10.0))) }
        assertEquals(2000L, adaptiveTrackingInterval(false, false, 10.0, false, true))
        assertEquals(5000L, adaptiveTrackingInterval(false, false, 10.0, true, false))
        assertEquals(30000L, adaptiveTrackingInterval(true, false, 0.0, false, true))
    }

    @Test
    fun stopGapsAndPoorAccuracyCannotCarryHeadingHistoryIntoANewTurn() {
        val detector = TurnDetailDetector()
        val f = Fix(21.5, 39.2, 1000, 5.0, speed = 10.0, bearing = 0.0)
        assertFalse(detector.inspect(f))
        assertFalse(detector.inspect(f.copy(time = 4000, bearing = 15.0)))
        assertFalse(detector.inspect(f.copy(time = 7000, speed = 0.0, bearing = 100.0)))
        assertFalse(detector.inspect(f.copy(time = 10000, bearing = 30.0)))
        assertFalse(detector.inspect(f.copy(time = 13000, accuracy = 45.0, bearing = 80.0)))
        assertFalse(detector.inspect(f.copy(time = 16000, bearing = 60.0)))
        assertFalse(detector.inspect(f.copy(time = 50000, bearing = 160.0)))
        assertFalse(detector.inspect(f.copy(time = 53000, bearing = 250.0, discontinuous = true)))
    }

    @Test
    fun missingProviderBearingUsesMovingGeometry() {
        val detector = TurnDetailDetector()
        val coords = listOf(21.5 to 39.2, 21.5 to 39.201, 21.5 to 39.202, 21.501 to 39.202)
        val output = coords.mapIndexed { i, p ->
            detector.inspect(
                Fix(p.first, p.second, 1000 + i * 5000L, 5.0, speed = 10.0, hasBearing = false)
            )
        }
        assertEquals(listOf(false, false, false, true), output)
    }

    private fun record(
        id: Long,
        shift: Double = 0.0,
        detour: Boolean = false,
        hour: Int = 8,
        duration: Long = 600000,
    ): Pair<Outing, List<RoutePoint>> {
        val start =
            ZonedDateTime.of(2026, 10, id.toInt(), hour, 0, 0, 0, SaudiZone)
                .toInstant()
                .toEpochMilli()
        val trip =
            Outing(
                id,
                start,
                start + duration,
                state = EngineState(meters = 2100.0, stoppedMs = 60000),
            )
        val points =
            (0..20).map { i ->
                RoutePoint(
                    outingId = id,
                    latitude = 21.5000 + shift + if (detour && i in 6..14) .004 else 0.0,
                    longitude = 39.2 + i * .001,
                    accuracy = 5.0,
                    speed = 8.0,
                    altitude = 0.0,
                    bearing = 90.0,
                    recordedAt = start + i * 30000L,
                )
            }
        return trip to points
    }

    @Test
    fun comparisonsTolerateGridBoundaryNoiseButSeparateDetoursAndReverseTrips() {
        val a = record(1)
        val b = record(2, shift = .00005)
        val c = record(3, detour = true)
        val reversed =
            record(4).let { (o, p) ->
                o to
                    p.reversed().mapIndexed { i, v ->
                        v.copy(recordedAt = o.startedAt + i * 30000L)
                    }
            }
        val groups = routeComparisons(listOf(a, b, c, reversed))
        assertEquals(3, groups.size)
        assertEquals(2, groups.first().trips)
        assertNull(
            routeSample(
                a.first,
                a.second.mapIndexed { i, p -> if (i == 5) p.copy(uncertainGap = true) else p },
            )
        )
    }

    @Test
    fun comparisonsReportTimeStopsAndDepartureHourOnlyWithEnoughEvidence() {
        val records =
            listOf(
                record(1, duration = 600000),
                record(2, duration = 660000),
                record(3, duration = 720000),
                record(4, hour = 9, duration = 300000),
            )
        val samples = records.mapNotNull { (o, p) -> routeSample(o, p, 2) }
        val value = compareRouteSamples(samples).single()
        assertEquals(4, value.trips)
        assertEquals(300000, value.fastestMs)
        assertEquals(720000, value.slowestMs)
        assertEquals(60000, value.averageStoppedMs)
        assertEquals(2.0, value.averageStops, 0.0)
        assertEquals(8, value.bestHour!!.hour)
        assertEquals(3, value.bestHour!!.trips)
        assertNull(compareRouteSamples(samples.take(2)).single().bestHour)
    }
}
