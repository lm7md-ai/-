package sa.rihla.map

import org.junit.Assert.*
import org.junit.Test
import sa.rihla.core.StopDetails
import sa.rihla.data.RoutePoint

class RoutePresentationTest {
    private fun point(i: Int, lat: Double = 21.5, lon: Double = 39.2 + i * .0001) =
        RoutePoint(
            outingId = 1,
            latitude = lat,
            longitude = lon,
            accuracy = 5.0,
            speed = 8.0,
            altitude = 12.0,
            bearing = 90.0,
            recordedAt = 1000L + i * 5000,
        )

    @Test
    fun weakConfidenceChangesStyleAndEveryKindOfGapRemainsDisconnected() {
        val raw = (0..8).map { point(it) }.toMutableList()
        raw[2] = raw[2].copy(accuracy = 40.0)
        raw[4] = raw[4].copy(breakBefore = true)
        raw[6] = raw[6].copy(uncertainGap = true)
        raw[8] = raw[8].copy(recordedAt = 200000)
        val prepared = prepareRoute(raw, emptyList(), RouteDisplayOptions(simplify = false), 1.0)
        assertTrue(prepared.strokes.any { it.dashed })
        for (index in listOf(4, 6, 8)) {
            assertFalse(
                prepared.strokes.any { stroke ->
                    stroke.coordinates.zipWithNext().any { (a, b) ->
                        a == (raw[index - 1].latitude to raw[index - 1].longitude) &&
                            b == (raw[index].latitude to raw[index].longitude)
                    }
                }
            )
        }
        assertFalse(
            prepareRoute(raw, emptyList(), RouteDisplayOptions(confidence = false), 1.0)
                .strokes
                .any { it.dashed }
        )
    }

    @Test
    fun stationaryCleaningDoesNotModifyOriginalsOrCollapseDepartingAndGapPoints() {
        val raw =
            (0..8).map {
                point(it, lon = 39.2 + if (it % 2 == 0) .00002 else -.00002).copy(speed = 0.0)
            }
        val original = raw.toList()
        val stops = listOf(StopDetails(1, 1000, 41000, 21.5, 39.2))
        val prepared = prepareRoute(raw, stops, RouteDisplayOptions(), 2.0)
        assertEquals(original, raw)
        assertTrue(prepared.stats.cleaned > 0)
        assertEquals(raw.first().longitude, prepared.points.first().longitude, 0.0)
        assertFalse(prepared.points.last().stopped)
        val gap = raw.mapIndexed { i, p -> if (i == 4) p.copy(breakBefore = true) else p }
        val fixed = prepareRoute(gap, stops, RouteDisplayOptions(), 2.0)
        assertEquals(gap[4].longitude, fixed.points[4].longitude, 0.0)
        assertEquals(gap[3].longitude, fixed.points[3].longitude, 0.0)
    }

    @Test
    fun aHundredThousandPointsSimplifyWithoutLosingOriginalInspectionPoints() {
        val raw = (0 until 100000).map { point(it, lon = 39.2 + it * .000001) }
        val prepared = prepareRoute(raw, emptyList(), RouteDisplayOptions(), 5.0)
        assertEquals(100000, prepared.points.size)
        assertTrue(prepared.stats.drawn < 110)
        assertTrue(
            prepared.hitIndex
                .candidates(raw[50001].latitude, raw[50001].longitude, 2.0)
                .contains(50001)
        )
        assertEquals(
            raw.first().longitude,
            prepared.strokes.first().coordinates.first().second,
            0.0,
        )
        assertEquals(raw.last().longitude, prepared.strokes.last().coordinates.last().second, 0.0)
    }

    @Test
    fun simplificationRetainsTurnAndColorBoundariesAndCanBeDisabled() {
        val raw =
            (0..20).map { i ->
                point(
                        i,
                        lat = 21.5 + if (i > 10) (i - 10) * .0001 else 0.0,
                        lon = 39.2 + minOf(i, 10) * .0001,
                    )
                    .copy(speed = if (i < 10) 2.0 else 20.0)
            }
        val simple =
            prepareRoute(raw, emptyList(), RouteDisplayOptions(color = RouteColorMode.SPEED), 5.0)
        assertEquals(2, simple.strokes.map { it.color }.distinct().size)
        assertTrue(
            simple.strokes.any { it.coordinates.contains(raw[10].latitude to raw[10].longitude) }
        )
        val original =
            prepareRoute(
                raw,
                emptyList(),
                RouteDisplayOptions(cleanStops = false, simplify = false),
                1000.0,
            )
        assertEquals(raw.size, original.strokes.single().coordinates.size)
        assertEquals(raw.map { it.latitude to it.longitude }, original.strokes.single().coordinates)
    }

    @Test
    fun timeAccuracyAndStopLayersHaveDistinctMeaning() {
        val p = point(1)
        assertNotEquals(
            routeSegmentColor(RouteColorMode.ACCURACY, p, false, 0, 100),
            routeSegmentColor(RouteColorMode.ACCURACY, p.copy(accuracy = 40.0), false, 0, 100),
        )
        assertNotEquals(
            routeSegmentColor(RouteColorMode.STOPS, p, true, 0, 100),
            routeSegmentColor(RouteColorMode.STOPS, p, false, 0, 100),
        )
        assertNotEquals(
            routeSegmentColor(RouteColorMode.TIME, p.copy(recordedAt = 0), false, 0, 100),
            routeSegmentColor(RouteColorMode.TIME, p.copy(recordedAt = 100), false, 0, 100),
        )
    }

    @Test
    fun conservativeMatchingRejectsParallelAmbiguityPerpendicularDirectionAndRemotePoints() {
        fun road(lat: Double) =
            RoadFeature("residential", "طريق", listOf(lat to 39.2, lat to 39.21))
        val direction = (21.5 to 39.2) to (21.5 to 39.21)
        val matcher = RoadMatcher(listOf(road(21.5)))
        val match = matcher.match(21.50005, 39.205, 10.0, direction)!!
        assertEquals(21.5, match.latitude, .000001)
        assertTrue(match.offsetMeters in 5.0..6.0)
        assertNull(matcher.match(21.51, 39.205, 10.0, direction))
        assertNull(matcher.match(21.50005, 39.205, 40.0, direction))
        assertNull(matcher.match(21.50005, 39.205, 10.0, (21.49 to 39.205) to (21.51 to 39.205)))
        assertNull(
            RoadMatcher(listOf(road(21.5), road(21.5001))).match(21.50005, 39.205, 10.0, direction)
        )
        val hairpin =
            RoadFeature(
                "residential",
                "منعطف عائد",
                listOf(21.5 to 39.2, 21.5 to 39.21, 21.5001 to 39.21, 21.5001 to 39.2),
            )
        assertNull(RoadMatcher(listOf(hairpin)).match(21.50005, 39.205, 10.0, direction))
        assertFalse(RoadMatcher(emptyList()).available)
    }

    @Test
    fun matchingFollowsSharedRoadGeometryAndNeverChangesDistanceLedger() {
        val road =
            RoadFeature("primary", "منحنى", listOf(21.5 to 39.2, 21.5002 to 39.205, 21.5 to 39.21))
        val matcher = RoadMatcher(listOf(road))
        val raw =
            listOf(
                point(0, lon = 39.201).copy(distanceDelta = 17.0),
                point(1, lon = 39.209).copy(distanceDelta = 28.0),
            )
        val prepared =
            prepareRoute(
                raw,
                emptyList(),
                RouteDisplayOptions(matchRoads = true, simplify = false),
                0.0,
                matcher,
            )
        assertEquals(2, prepared.stats.matched)
        assertTrue(prepared.strokes.single().coordinates.contains(road.points[1]))
        assertEquals(45.0, raw.sumOf { it.distanceDelta }, 0.0)
        assertTrue(prepared.points.all { it.match != null })
    }
}
