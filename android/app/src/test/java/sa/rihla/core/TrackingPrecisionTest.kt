package sa.rihla.core

import org.junit.Assert.*
import org.junit.Test
import sa.rihla.data.RoutePoint

class TrackingPrecisionTest {
    private fun fix(meters: Double, seconds: Long, speed: Double = 2.0, accuracy: Double = 15.0) =
        Fix(
            21.5 + meters / 111194.9266,
            39.2,
            1_000_000 + seconds * 1000,
            accuracy,
            speed,
            elapsedNanos = (1000 + seconds) * 1_000_000_000,
        )

    @Test
    fun shortStepsAccumulateAndSurviveAnEngineRestart() {
        var state = EngineState()
        for (i in 0..100) {
            state = TrackingEngine().accept(state.copy(), fix(i * 6.0, i * 3L), null, null).state
        }
        assertEquals(600.0, state.meters, 20.0)
        assertEquals(0L, state.stopStart)
    }

    @Test
    fun creepingTrafficCountsButStationaryJitterDoesNot() {
        var state = EngineState()
        for (i in 0..100) state =
            TrackingEngine().accept(state, fix(i * 4.5, i * 3L, 1.5, 10.0), null, null).state
        assertEquals(450.0, state.meters, 15.0)
        var parked = EngineState()
        for (i in 0..200) parked =
            TrackingEngine()
                .accept(parked, fix((i % 5 - 2) * 2.0, i * 5L, 0.0, 8.0), null, null)
                .state
        assertEquals(0.0, parked.meters, .001)
        assertTrue(parked.stopStart > 0)
    }

    @Test
    fun missingSpeedCanAccumulateEvidenceWithoutInventingParkedDistance() {
        var moving = EngineState()
        for (i in 0..100) moving =
            TrackingEngine()
                .accept(moving, fix(i * 12.0, i * 3L, 0.0).copy(hasSpeed = false), null, null)
                .state
        assertEquals(1200.0, moving.meters, 35.0)
        var parked = EngineState()
        for (i in 0..100) parked =
            TrackingEngine()
                .accept(parked, fix((i % 3) * 4.0, i * 3L, 0.0).copy(hasSpeed = false), null, null)
                .state
        assertEquals(0.0, parked.meters, .001)
    }

    @Test
    fun invalidSpeedsAndRepeatedProvidersCannotPoisonTheAnchor() {
        val gate = FixQualityGate()
        assertNull(gate.inspect(fix(0.0, 0)))
        assertEquals("DUPLICATE", gate.inspect(fix(1.0, 0)))
        assertEquals("INVALID", gate.inspect(fix(1.0, 1).copy(speed = Double.NaN)))
        assertEquals("JUMP", gate.inspect(fix(1000.0, 1, 30.0)))
        assertNull(gate.inspect(fix(10.0, 5)))
        val s = TrackingEngine().accept(EngineState(), fix(0.0, 0), null, null).state
        assertFalse(
            TrackingEngine().accept(s, fix(1.0, 5).copy(speed = Double.NaN), null, null).accepted
        )
        assertFalse(TrackingEngine().accept(s, fix(1.0, 5).copy(speed = -1.0), null, null).accepted)
    }

    @Test
    fun isolatedUrbanSpikeIsRejectedAndConfirmedRelocationBreaksThePath() {
        val gate = FixQualityGate()
        assertNull(gate.inspect(fix(0.0, 0, 0.0, 5.0)))
        assertEquals("SUSPECT", gate.inspect(fix(80.0, 5, 0.0, 5.0)))
        assertNull(gate.inspect(fix(1.0, 10, 0.0, 5.0)))
        assertFalse(gate.reacquired)
        assertEquals("SUSPECT", gate.inspect(fix(100.0, 15, 0.0, 5.0)))
        assertNull(gate.inspect(fix(102.0, 20, 0.0, 5.0)))
        assertTrue(gate.reacquired)
        val initial = TrackingEngine().accept(EngineState(), fix(0.0, 0), null, null).state
        val after =
            TrackingEngine()
                .accept(initial, fix(102.0, 20, 0.0, 5.0).copy(discontinuous = true), null, null)
        assertTrue(after.accepted)
        assertEquals(0.0, after.state.meters, .001)
        assertEquals(20000L, after.state.gapMs)
    }

    @Test
    fun precisionCadenceAdaptsWithoutChangingBalancedBatteryPolicy() {
        assertEquals(2000L, adaptiveTrackingInterval(false, true, 25.0, false))
        assertEquals(2000L, adaptiveTrackingInterval(false, true, 5.0, true))
        assertEquals(3000L, adaptiveTrackingInterval(false, true, 5.0, false))
        assertEquals(5000L, adaptiveTrackingInterval(false, false, 25.0, true))
        assertEquals(30000L, adaptiveTrackingInterval(true, false, 0.0, false))
        assertEquals(15000L, adaptiveTrackingInterval(true, true, 0.0, false))
    }

    @Test
    fun qualityReportSeparatesAccuracyGapsAndStartTailDelays() {
        fun point(t: Long, accuracy: Double, split: Boolean = false) =
            RoutePoint(
                outingId = 1,
                latitude = 21.5,
                longitude = 39.2,
                accuracy = accuracy,
                speed = 0.0,
                altitude = 0.0,
                bearing = 0.0,
                recordedAt = t,
                breakBefore = split,
            )
        val report =
            routeQuality(
                listOf(point(2000, 5.0), point(5000, 15.0), point(150000, 40.0, true)),
                1000,
                250000,
            )
        assertEquals(3, report.points)
        assertEquals(15.0, report.medianAccuracy!!, .001)
        assertEquals(2, report.goodFixes)
        assertEquals(1, report.gaps)
        assertEquals(145000L, report.gapMs)
        assertEquals(1000L, report.startDelayMs)
        assertEquals(100000L, report.tailDelayMs)
        assertNull(routeQuality(emptyList(), 0, 1000).medianAccuracy)
    }

    @Test
    fun qualityReportCountsExplicitUncertaintyEvenWithoutABreakFlag() {
        val start =
            RoutePoint(
                outingId = 1,
                latitude = 21.5,
                longitude = 39.2,
                accuracy = 5.0,
                speed = 1.0,
                altitude = 0.0,
                bearing = 0.0,
                recordedAt = 1000,
            )
        val end = start.copy(recordedAt = 11000, uncertainGap = true, breakBefore = false)
        val report = routeQuality(listOf(start, end), 1000, 11000)
        assertEquals(1, report.gaps)
        assertEquals(10000L, report.gapMs)
    }
}
