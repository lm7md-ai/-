package sa.rihla.core

import org.junit.Assert.*
import org.junit.Test
import sa.rihla.data.*

class TemporaryCommutesTest {
    private val type = temporaryPlaceType(false, 1000, 900000, "استراحة اختبار")

    private fun point(i: Int, lat: Double = 21.5) =
        RoutePoint(
            outingId = 1,
            latitude = lat,
            longitude = 39.2,
            accuracy = 5.0,
            speed = 0.0,
            altitude = 0.0,
            bearing = 0.0,
            recordedAt = 10000 + i * 60000L,
            distanceDelta = if (i > 2) 100.0 else 0.0,
        )

    private val place = SavedPlace(type, 21.5, 39.2, 100.0)
    private val points = (0..6).map { point(it, if (it < 3) 21.5 else 21.51 + it * .001) }
    private val trip = Outing(1, 10000, 400000)
    private val visit =
        JourneyEvent(1, 1, "VISIT", points[5].recordedAt, points[6].recordedAt, 21.52, 39.2, true)

    @Test
    fun confirmedDepartureAndCampusArrivalCreateASeparateLegWithoutChangingTheTrip() {
        val before = points.toList()
        val legs = temporaryCommutes(trip, points, listOf(visit), listOf(place))
        assertEquals(1, legs.size)
        assertEquals(2, legs.single().from)
        assertEquals(5, legs.single().to)
        assertEquals(points[2].recordedAt, legs.single().departure)
        assertEquals(before, points)
        assertEquals(10000, trip.startedAt)
        assertTrue(
            temporaryCommutes(trip, points, listOf(visit.copy(confirmed = false)), listOf(place))
                .isEmpty()
        )
    }

    @Test
    fun passingByOrExpiredPlacesOrUncertainDwellDoNotCreateFalseCommutes() {
        val passing = points.mapIndexed { i, p -> if (i == 1) p.copy(latitude = 21.6) else p }
        assertTrue(temporaryCommutes(trip, passing, listOf(visit), listOf(place)).isEmpty())
        assertTrue(
            temporaryCommutes(
                    trip,
                    points,
                    listOf(visit),
                    listOf(place.copy(type = temporaryPlaceType(true, 1000, 2000, "منزل اختبار"))),
                )
                .isEmpty()
        )
        val broken = points.mapIndexed { i, p -> if (i == 1) p.copy(breakBefore = true) else p }
        assertTrue(temporaryCommutes(trip, broken, listOf(visit), listOf(place)).isEmpty())
    }

    @Test
    fun activeConfirmedCampusVisitsAreIncludedAndTemporaryTypeRemainsOnlyAPlaceDefinition() {
        val active =
            trip.copy(
                endedAt = null,
                state = EngineState(universityArrival = visit.startedAt, visitConfirmed = true),
            )
        assertEquals(1, temporaryCommutes(active, points, emptyList(), listOf(place)).size)
        assertEquals("استراحة مؤقتة: استراحة اختبار", placeDisplayName(type))
        assertNull(temporaryPlaceInfo("TEMP_HOME:invalid"))
        assertNotEquals("HOME", type)
    }

    @Test
    fun temporaryRestOverlappingHomePreservesTripUntilAfterExpiryAndFreshHomeDwell() {
        val engine = TrackingEngine()
        val home = Area(place.latitude, place.longitude, place.radius)
        var normal = EngineState(leftHome = true, originKnown = true, meters = 5000.0)
        var resting = normal
        for (i in 0..30) {
            val fix = Fix(place.latitude, place.longitude, 10000 + i * 30000L, 5.0)
            val allow = !insideActiveTemporaryPlace(listOf(place), fix)
            val usual = engine.accept(normal, fix, home, null)
            val update = engine.accept(resting, fix, home, null, allowHomeEnd = allow)
            if (i == 12) assertTrue(usual.autoEnd)
            assertFalse(update.autoEnd)
            assertEquals(5000.0, update.state.meters, 0.0)
            assertFalse(update.state.startedHome)
            normal = usual.state
            resting = update.state
        }
        val expired = Fix(place.latitude, place.longitude, 1210000, 5.0)
        assertFalse(insideActiveTemporaryPlace(listOf(place), expired))
        for (i in 31..40) {
            val update =
                engine.accept(
                    resting,
                    Fix(place.latitude, place.longitude, 10000 + i * 30000L, 5.0),
                    home,
                    null,
                )
            assertEquals(i == 40, update.autoEnd)
            resting = update.state
        }
    }

    @Test
    fun expiryWhileStillInsideDoesNotFabricateADepartureToCampus() {
        val until = place.copy(type = temporaryPlaceType(false, 1000, 140000, "مؤقت"))
        val stillInside = (0..9).map { point(it, if (it < 7) 21.5 else 21.55) }
        val arrival =
            visit.copy(startedAt = stillInside[8].recordedAt, endedAt = stillInside[9].recordedAt)
        assertTrue(temporaryCommutes(trip, stillInside, listOf(arrival), listOf(until)).isEmpty())
    }

    @Test
    fun longTemporaryAnalysisRespondsToCancellationWithoutChangingRecords() {
        val original = points.toList()
        var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            temporaryCommutes(trip, points, listOf(visit), listOf(place)) {
                checks++
                throw java.util.concurrent.CancellationException()
            }
        }
        assertTrue(checks > 0)
        assertEquals(original, points)
    }
}
