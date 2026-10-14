package sa.rihla.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test
    fun automaticDrivingCanUsePositionsWithoutProviderSpeedButRejectsWalkingAndUncertainty() {
        val first = Fix(21.5, 39.2, 1_000_000, 5.0)
        assertTrue(drivingEvidence(first, Fix(21.501, 39.2, 1_010_000, 5.0)))
        assertFalse(drivingEvidence(first, Fix(21.501, 39.2, 1_050_000, 5.0)))
        assertFalse(drivingEvidence(first.copy(accuracy = 40.0), Fix(21.5006, 39.2, 1_010_000, 40.0)))
    }
    @Test
    fun waitingMoreThanNinetySecondsDoesNotPermanentlyPreventAutomaticStart() {
        val candidate = DrivingCandidate()
        assertNull(candidate.accept(Fix(21.5, 39.2, 1_000_000, 5.0)))
        assertNull(candidate.accept(Fix(21.5, 39.2, 1_100_000, 5.0)))
        assertNotNull(candidate.accept(Fix(21.501, 39.2, 1_110_000, 5.0, 11.0)))
        candidate.reset()
        assertNull(candidate.accept(Fix(21.502, 39.2, 1_120_000, 5.0, 11.0)))
    }
    private val engine = TrackingEngine()
    private val home = Area(21.5, 39.2, 200.0)
    private val uni = Area(21.52, 39.2, 300.0)

    private fun fix(lat: Double, time: Long, speed: Double = 0.0, accuracy: Double = 5.0) =
        Fix(lat, 39.2, time, accuracy, speed)

    @Test
    fun haversineAndHighwayDistance() {
        assertEquals(111194.9, distance(0.0, 0.0, 1.0, 0.0), 1.0)
        var s = engine.accept(EngineState(), fix(21.5, 1000000), home, uni).state
        s = engine.accept(s, fix(21.504, 1020000, 22.0), home, uni).state
        assertTrue(s.meters > 400)
    }

    @Test
    fun rejectsBadAccuracyJumpAndDuplicates() {
        val s = engine.accept(EngineState(), fix(21.5, 1000000), home, uni).state
        assertFalse(engine.accept(s, fix(22.5, 1001000, 10.0), home, uni).accepted)
        assertFalse(engine.accept(s, fix(21.5, 1000000), home, uni).accepted)
        assertFalse(engine.accept(s, fix(21.5, 1001000, accuracy = 100.0), home, uni).accepted)
    }

    @Test
    fun driftDoesNotAddDistance() {
        var s = engine.accept(EngineState(), fix(21.5, 1000000), home, uni).state
        s = engine.accept(s, fix(21.50001, 1005000), home, uni).state
        assertEquals(0.0, s.meters, 0.01)
    }

    @Test
    fun shortTrafficLightDoesNotCreateStop() {
        var s = engine.accept(EngineState(), fix(21.51, 1000000), home, uni).state
        s = engine.accept(s, fix(21.51, 1060000), home, uni).state
        assertEquals(0L, s.stopStart)
    }

    @Test
    fun parkedHoursDoNotEndAndResumeSameState() {
        var s = engine.accept(EngineState(), fix(21.51, 1000000), home, uni).state
        s = engine.accept(s, fix(21.51, 1180000), home, uni).state
        assertEquals(1000000L, s.stopStart)
        val parked = engine.accept(s, fix(21.51, 19000000), home, uni)
        assertFalse(parked.autoEnd)
        val resume = engine.accept(parked.state, fix(21.5102, 19010000, 5.0), home, uni)
        assertEquals("TRACKING_DRIVING", resume.state.phase)
        assertEquals(1, resume.events.count { it.type == "STOP" })
        assertTrue(resume.state.stoppedMs > 10000000)
    }

    @Test
    fun homeDriveByNeverEnds() {
        var s = engine.accept(EngineState(), fix(21.51, 1000000, 10.0), home, uni).state
        val r = engine.accept(s, fix(21.5, 1060000, 20.0), home, uni)
        assertFalse(r.autoEnd)
        assertEquals(0L, r.state.homeSince)
    }

    @Test
    fun homeReturnRequiresDwellAndDeparture() {
        var s = engine.accept(EngineState(), fix(21.5, 1000000), home, uni).state
        s = engine.accept(s, fix(21.51, 1060000, 20.0), home, uni).state
        s = engine.accept(s, fix(21.5, 1120000), home, uni).state
        for (t in 1150000L..1420000L step 30000L) {
            val r = engine.accept(s, fix(21.5, t), home, uni)
            assertFalse(r.autoEnd)
            s = r.state
        }
        val end = engine.accept(s, fix(21.5, 1450000), home, uni)
        assertTrue(end.autoEnd)
    }

    @Test
    fun universityReentryMergedAndCountedOnce() {
        var s = engine.accept(EngineState(), fix(21.5, 1000000), home, uni).state
        s = engine.accept(s, fix(21.51, 1060000, 20.0), home, uni).state
        s = engine.accept(s, fix(21.52, 1120000, 20.0), home, uni).state
        s = engine.accept(s, fix(21.52, 1240000), home, uni).state
        assertTrue(s.universityCounted)
        assertTrue(s.visitConfirmed)
        val arrival = s.universityArrival
        s = engine.accept(s, fix(21.524, 1300000, 10.0), home, uni).state
        s = engine.accept(s, fix(21.52, 1420000), home, uni).state
        assertEquals(arrival, s.universityArrival)
        assertEquals(0L, s.universityExit)
        val end = engine.end(s, 1500000)
        assertEquals(1, end.state.visits)
        assertEquals(1, end.events.count { it.type == "VISIT" })
    }

    @Test
    fun startsOutsideHomeWithoutUniversityCommuteCount() {
        var s = engine.accept(EngineState(), fix(21.51, 1000000), home, uni).state
        s = engine.accept(s, fix(21.52, 1060000, 20.0), home, uni).state
        s = engine.accept(s, fix(21.52, 1180000), home, uni).state
        assertTrue(s.visitConfirmed)
        assertFalse(s.universityCounted)
    }

    @Test
    fun tunnelDoesNotInventStraightLineDistance() {
        val s = engine.accept(EngineState(), fix(21.5, 1000000), home, uni).state
        val r = engine.accept(s, fix(21.52, 1300000, 20.0), home, uni)
        assertEquals(0.0, r.state.meters, 0.001)
        assertEquals(300000L, r.state.gapMs)
    }

    @Test
    fun savedSnapshotRecoveryIsDeterministic() {
        val s = engine.accept(EngineState(), fix(21.5, 1000000), home, uni).state
        assertEquals(
            engine.accept(s, fix(21.504, 1030000, 15.0), home, uni),
            TrackingEngine().accept(s.copy(), fix(21.504, 1030000, 15.0), home, uni),
        )
    }

    @Test
    fun polygonContainment() {
        val a = Area(0.0, 0.0, 1.0, listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, 1.0 to 0.0))
        assertTrue(a.contains(Fix(.5, .5, 1, 1.0)))
        assertFalse(a.contains(Fix(2.0, 2.0, 1, 1.0)))
    }

    @Test
    fun fullTanksIncludePartialFillsExcludeInitialTank() {
        val e =
            fuelEconomy(
                listOf(
                    Fill(1, 10000, 50000, true, 0.0),
                    Fill(2, 2000, 10000, false, 100000.0),
                    Fill(3, 8000, 40000, true, 500000.0),
                )
            )!!
        assertEquals(10.0, e.kmPerLiter, 0.001)
        assertEquals(10.0, e.litersPer100Km, 0.001)
        assertEquals(.2, e.sarPerKm, 0.001)
    }

    @Test
    fun insufficientOrUnreliableFuelStaysUnknown() {
        assertNull(fuelEconomy(listOf(Fill(1, 1000, 5000, true, 0.0))))
        assertNull(
            fuelEconomy(
                listOf(Fill(1, 1000, 5000, true, 0.0), Fill(2, 1000, 5000, true, 50000.0, false))
            )
        )
    }

    @Test
    fun decimalPrecisionAndExpenseTotals() {
        assertEquals(12345L, decimalUnits("١٢٣٫٤٥", 2))
        assertEquals(10000L, calculateLiters(2180, 218))
        assertEquals(15075L, listOf(decimalUnits("100.25", 2)!!, decimalUnits("50.50", 2)!!).sum())
        assertNull(decimalUnits("-1", 2))
    }

    @Test
    fun saudiDatePeriodsSpanMidnightCorrectly() {
        val now = ZonedDateTime.of(2026, 10, 5, 0, 30, 0, 0, SaudiZone)
        val bounds = periodBounds(Period.MONTH, now)
        assertEquals(
            LocalDate.of(2026, 10, 1),
            Instant.ofEpochMilli(bounds.first).atZone(SaudiZone).toLocalDate(),
        )
        val previous = periodBounds(Period.MONTH, now, true)
        assertEquals(bounds.first, previous.second)
    }

    @Test
    fun automaticCandidateNeedsRealisticDriving() {
        val first = fix(21.5, 1000000)
        assertTrue(drivingEvidence(first, fix(21.501, 1010000, 11.0)))
        assertFalse(drivingEvidence(first, fix(22.5, 1010000, 11.0)))
        assertFalse(drivingEvidence(first, fix(21.50001, 1010000, 3.0)))
        assertFalse(drivingEvidence(first, fix(21.501, 1010000, 11.0, 80.0)))
    }
}
