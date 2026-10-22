package sa.rihla.map

import org.junit.Assert.*
import org.junit.Test
import sa.rihla.core.*
import sa.rihla.data.*

class RouteAnalysisTest {
    private fun point(i: Int) =
        RoutePoint(
            outingId = 1,
            latitude = 21.5,
            longitude = 39.2 + i * .001,
            accuracy = 5.0 + i,
            speed = 10.0 + i,
            altitude = 10.0,
            bearing = 90.0,
            recordedAt = 1000 + i * 10000L,
            distanceDelta = 100.0,
        )

    @Test
    fun exactEdgeReportsDifferentDistanceMeaningsAndKeepsOriginals() {
        val raw = (0..3).map(::point)
        val before = raw.toList()
        val section = RouteAnalysis(raw, emptyList()).summary(1, 2)!!
        assertEquals(100.0, section.recordedMeters, 0.0)
        assertTrue(section.gpsMeters in 103.0..104.0)
        assertEquals(section.directMeters, section.gpsMeters, .001)
        assertEquals(10000L, section.elapsedMs)
        assertEquals(10000L, section.knownMs)
        assertEquals(12.0, section.maxSpeed, 0.0)
        assertEquals(6.5, section.averageAccuracy, 0.0)
        assertEquals(before, raw)
        assertEquals(section, RouteAnalysis(raw, emptyList()).summary(2, 1))
    }

    @Test
    fun gapsNeverContributeDistanceSpeedOrInventMovementAndStopsDoNotDoubleSubtract() {
        val raw =
            (0..3).map(::point).mapIndexed { i, p ->
                if (i == 2) p.copy(breakBefore = true, distanceDelta = 9999.0) else p
            }
        val stops =
            listOf(StopDetails(1, 6000, 26000, 21.5, 39.2), StopDetails(2, 9000, 21000, 21.5, 39.2))
        val section = RouteAnalysis(raw, stops).summary(0, 3)!!
        assertEquals(200.0, section.recordedMeters, 0.0)
        assertEquals(30000L, section.elapsedMs)
        assertEquals(20000L, section.knownMs)
        assertEquals(10000L, section.gapMs)
        assertEquals(20000L, section.stoppedMs)
        assertEquals(10000L, section.movingMs)
        assertEquals(1, section.stops)
        val gap = RouteAnalysis(raw, stops).summary(1, 2)!!
        assertEquals(0.0, gap.gpsMeters, 0.0)
        assertNull(gap.averageGpsKmh)
        assertEquals(0, adjacentRouteSegment(raw, 0)?.minus(1))
        assertEquals(3, adjacentRouteSegment(raw, 2))
    }

    @Test
    fun stopBoundariesAndOpenStopsClipToSelectedRangeAndLastFix() {
        val raw = (0..4).map(::point)
        val analysis =
            RouteAnalysis(
                raw,
                listOf(
                    StopDetails(1, 11000, 21000, 21.5, 39.2),
                    StopDetails(2, 31000, null, 21.5, 39.2),
                ),
            )
        assertEquals(0L, analysis.summary(0, 1)!!.stoppedMs)
        assertEquals(10000L, analysis.summary(1, 2)!!.stoppedMs)
        assertEquals(0L, analysis.summary(2, 3)!!.stoppedMs)
        assertEquals(10000L, analysis.summary(3, 4)!!.stoppedMs)
        assertEquals(0, analysis.summary(2, 2)!!.stops)
    }

    @Test
    fun fuelUsesHistoricalSnapshotAndRefusesMissingLedgerOrGaps() {
        val raw = (0..2).map(::point)
        val trip =
            Outing(
                1,
                1000,
                21000,
                consumptionMinMl = 8000,
                consumptionMaxMl = 8000,
                fuelPriceHalala = 218,
            )
        assertEquals(
            .016,
            RouteAnalysis(raw, emptyList()).summary(0, 2)!!.fuel(trip)!!.minLiters,
            .000001,
        )
        assertNull(
            RouteAnalysis(raw.map { it.copy(distanceDelta = 0.0) }, emptyList())
                .summary(0, 2)!!
                .fuel(trip)
        )
        assertNull(
            RouteAnalysis(
                    raw.mapIndexed { i, p -> if (i == 1) p.copy(uncertainGap = true) else p },
                    emptyList(),
                )
                .summary(0, 2)!!
                .fuel(trip)
        )
        assertNull(
            RouteAnalysis(raw, emptyList()).summary(0, 2)!!.fuel(trip.copy(fuelPriceHalala = 0))
        )
    }

    @Test
    fun indexedRangesAndSlowSectionsWorkForLargeRecordingsWithoutCrossingGaps() {
        val raw =
            (0 until 100000).map {
                point(it)
                    .copy(
                        longitude = 39.2 + it * .00001,
                        recordedAt = 1000 + it * 1000L,
                        speed = 5.0,
                        accuracy = 8.0,
                        distanceDelta = 1.0,
                    )
            }
        val analysis = RouteAnalysis(raw, emptyList())
        repeat(1000) { i ->
            val value = analysis.summary(i, 99999 - i)!!
            assertEquals((99999 - 2 * i).toDouble(), value.recordedMeters, 0.0)
        }
        assertTrue(analysis.slowSections.isNotEmpty())
        assertTrue(analysis.slowSections.all { it.gaps == 0 && it.gpsMeters >= 250 })
        val broken =
            raw.take(800).mapIndexed { i, p -> if (i == 400) p.copy(uncertainGap = true) else p }
        assertTrue(
            RouteAnalysis(broken, emptyList()).slowSections.none { it.from < 400 && it.to >= 400 }
        )
    }

    @Test
    fun sharingOmitsCoordinatesAndEmptyOrInvalidRangesDoNotCrash() {
        val analysis = RouteAnalysis((0..2).map(::point), emptyList())
        val text = sectionShareText(analysis.summary(0, 2)!!)
        assertFalse(text.contains("21.5"))
        assertFalse(text.contains("39.2"))
        assertTrue(text.contains("00:00:20"))
        assertNull(analysis.summary(-1, 1))
        assertNull(RouteAnalysis(emptyList(), emptyList()).summary(0, 0))
        assertEquals(0L, analysis.summary(1, 1)!!.elapsedMs)
        assertNull(adjacentRouteSegment(listOf(point(0)), 0))
    }

    @Test
    fun selectedOverlayKeepsWeakConfidenceRunsAndNeverConnectsAGap() {
        val raw =
            (0..8).map(::point).mapIndexed { i, p ->
                when (i) {
                    2 -> p.copy(accuracy = 40.0)
                    5 -> p.copy(breakBefore = true)
                    else -> p
                }
            }
        val prepared = prepareRoute(raw, emptyList(), RouteDisplayOptions(), 2.0)
        val strokes = selectionRouteStrokes(prepared, raw, 0..8, 2.0, true)
        assertTrue(strokes.any { it.dashed })
        assertTrue(strokes.any { !it.dashed })
        assertFalse(
            strokes.any { stroke ->
                stroke.coordinates.zipWithNext().any { (a, b) ->
                    a.second == raw[4].longitude && b.second == raw[5].longitude
                }
            }
        )
        assertTrue(selectionRouteStrokes(prepared, raw, IntRange.EMPTY, 2.0, true).isEmpty())
    }
}
