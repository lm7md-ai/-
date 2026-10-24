package sa.rihla.map

import org.junit.Assert.*
import org.junit.Test
import sa.rihla.core.StopDetails
import sa.rihla.data.Outing
import sa.rihla.data.RoutePoint

class StopRouteSectionsTest {
    @Test
    fun fittingAStopRangeKeepsHighlightWithoutOpeningAPointInspector() {
        val state = SectionSelectionState()
        state.stopRange = 3..7
        assertEquals(3..7, state.range(null))
        state.reset()
        assertNull(state.range(null))
    }
    private fun points(count: Int = 11) =
        (0 until count).map { i ->
            RoutePoint(
                outingId = 1,
                latitude = 21.0,
                longitude = 39.0 + i * .001,
                accuracy = 5.0,
                speed = 10.0,
                altitude = 0.0,
                bearing = 90.0,
                recordedAt = 1000 + i * 10000L,
                distanceDelta = 100.0,
            )
        }

    private val stops =
        listOf(
            StopDetails(1, 11000, 31000, 21.0, 39.001),
            StopDetails(2, 71000, 81000, 21.0, 39.007),
        )

    private fun selected(raw: List<RoutePoint>, edge: Int) =
        RoutePointSelection(
            raw[edge],
            edge,
            DisplayPoint(edge, raw[edge].latitude, raw[edge].longitude),
            edge,
        )

    private fun legs(raw: List<RoutePoint> = points(), finished: Boolean = true) =
        StopRouteSections(
            RouteAnalysis(raw, stops),
            Outing(
                id = 1,
                startedAt = 1000,
                endedAt = if (finished) 101000 else null,
            ),
        )

    @Test
    fun lineTapSelectsWholeDepartureToArrivalAndExcludesEndpointWaiting() {
        val raw = points()
        val before = raw.toList()
        val leg = legs(raw).at(selected(raw, 5))!!
        assertEquals(40000L, leg.elapsedMs)
        assertEquals(3..7, leg.range)
        assertEquals(400.0, leg.samples!!.recordedMeters, 0.0)
        assertEquals(0L, leg.samples.stoppedMs)
        assertEquals(0, leg.fromStop)
        assertEquals(1, leg.toStop)
        assertEquals(before, raw)
        assertEquals(leg, legs(raw).at(selected(raw, 7))) // tap on the arrival edge
    }

    @Test
    fun previousNextAndTripBoundarySectionsAreChronological() {
        val legs = legs()
        assertEquals(10000L, legs.before(stops.first())!!.elapsedMs)
        assertNull(legs.before(stops.first())!!.fromStop)
        assertEquals(legs.after(stops.first()), legs.before(stops.last()))
        assertEquals(20000L, legs.after(stops.last())!!.elapsedMs)
        assertNull(legs.after(stops.last())!!.toStop)
        assertNull(legs.toLatest(stops.first())) // historical trip cannot pretend to be live
    }

    @Test
    fun liveRangeGrowsAndIncludesIntermediateStopsButNeverChangesTheLedger() {
        val short = legs(points(10), false).toLatest(stops.first())!!
        val current = legs(points(11), false).toLatest(stops.first())!!
        assertEquals(10000L, current.elapsedMs - short.elapsedMs)
        assertEquals(3..10, current.range)
        assertEquals(10000L, current.samples!!.stoppedMs)
        assertTrue(current.liveEnd)
        assertEquals(101000L, current.endedAt)
    }

    @Test
    fun gapsKeepElapsedTimeButNeverGainDistance() {
        val raw =
            points().mapIndexed { i, p ->
                if (i == 5) p.copy(breakBefore = true, distanceDelta = 99999.0) else p
            }
        val leg = legs(raw).after(stops.first())!!
        assertEquals(40000L, leg.elapsedMs)
        assertEquals(300.0, leg.samples!!.recordedMeters, 0.0)
        assertEquals(10000L, leg.samples.gapMs)
    }

    @Test
    fun nonAlignedStopTimesExposeUnsampledEdgesWithoutInventingDistance() {
        val shifted = listOf(stops[0].copy(endedAt = 35000), stops[1].copy(startedAt = 68000))
        val leg =
            StopRouteSections(RouteAnalysis(points(), shifted), Outing(1, 1000, 101000))
                .after(shifted[0])!!
        assertEquals(33000L, leg.elapsedMs)
        assertEquals(4..6, leg.range)
        assertEquals(200.0, leg.samples!!.recordedMeters, 0.0)
        assertEquals(13000L, leg.unsampledMs)
    }

    @Test
    fun emptyOrSingleFixDoesNotReportZeroAsMeasuredDistance() {
        for (raw in listOf(emptyList(), points().filter { it.recordedAt == 51000L })) {
            val leg = legs(raw).after(stops[0])!!
            assertNull(leg.samples)
            assertNull(leg.range)
            assertEquals(40000L, leg.unsampledMs)
        }
    }

    @Test
    fun openStopHasNoDepartureLegAndTapInsideStopFallsBackToGpsInspection() {
        val open = stops.first().copy(endedAt = null)
        val raw = points()
        val legs = StopRouteSections(RouteAnalysis(raw, listOf(open)), Outing(1, 1000))
        assertNull(legs.after(open))
        assertNull(legs.toLatest(open))
        assertNull(legs.at(selected(raw, 5)))
    }

    @Test
    fun repeatedCoordinatesUseTimeNotNearestGeographicStopAndNoStopsUseFullTrip() {
        val raw = points().map { it.copy(latitude = 21.0, longitude = 39.0) }
        assertEquals(3..7, legs(raw).at(selected(raw, 5))!!.range)
        val whole = StopRouteSections(RouteAnalysis(raw, emptyList()), Outing(1, 1000, 101000))
        assertEquals(0..10, whole.at(selected(raw, 5))!!.range)
    }
}
