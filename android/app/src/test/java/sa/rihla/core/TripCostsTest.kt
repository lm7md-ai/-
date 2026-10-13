package sa.rihla.core

import org.junit.Assert.*
import org.junit.Test
import sa.rihla.data.*
import sa.rihla.ui.outingEstimate

class TripCostsTest {
    @Test
    fun confirmingStopAfterGpsGapDoesNotEraseDrivingTime() {
        val engine = TrackingEngine()
        var state = engine.accept(EngineState(), Fix(21.5, 39.2, 1000000, 5.0), null, null).state
        state = engine.accept(state, Fix(21.504, 39.2, 1020000, 5.0, 22.0), null, null).state
        state = engine.accept(state, Fix(21.504, 39.2, 1030000, 5.0), null, null).state
        val known = state.drivingMs
        state = engine.accept(state, Fix(21.504, 39.2, 1330000, 5.0), null, null).state
        assertTrue(state.stopStart > 0)
        assertEquals(known, state.drivingMs)
    }

    @Test
    fun durationsCarrySecondsMinutesAndHours() {
        assertEquals(DurationParts(0, 0, 0), durationParts(-1))
        assertEquals(DurationParts(0, 0, 59), durationParts(59999))
        assertEquals(DurationParts(0, 1, 0), durationParts(60000))
        assertEquals(DurationParts(1, 0, 0), durationParts(3600000))
        assertEquals(DurationParts(25, 2, 3), durationParts(90123000))
    }

    @Test
    fun officialSaudiPricesBothGrades() {
        assertEquals(218L, saudiPrice("91"))
        assertEquals(233L, saudiPrice("95"))
    }

    @Test
    fun referenceCostIncludesDecimalPrecision() {
        val estimate = estimateFuel(100000.0, ConsumptionProfile(7800, 7800, "REFERENCE"), 218)!!
        assertEquals(7.8, estimate.minLiters, .000001)
        assertEquals(1700L, estimate.minHalala)
        assertEquals(estimate.minHalala, estimate.maxHalala)
    }

    @Test
    fun unknownEngineRemainsRange() {
        val p = consumptionProfile("UNKNOWN", 8000, null)
        assertEquals("UNKNOWN", p.source)
        val e = estimateFuel(100000.0, p, 233)!!
        assertEquals(1282L, e.minHalala)
        assertEquals(1817L, e.maxHalala)
    }

    @Test
    fun measuredConsumptionOverridesReference() {
        val p = consumptionProfile("UNKNOWN", 8000, Economy(10.0, 10.0, .2))
        assertEquals("MEASURED", p.source)
        assertEquals(10000L, p.minMlPer100Km)
        assertEquals(2180L, estimateFuel(100000.0, p, 218)!!.minHalala)
    }

    @Test
    fun invalidInputsDoNotProduceFakeCosts() {
        assertNull(estimateFuel(Double.NaN, ConsumptionProfile(7800, 7800, "REFERENCE"), 218))
        assertNull(estimateFuel(-1.0, ConsumptionProfile(7800, 7800, "REFERENCE"), 218))
        assertNull(estimateFuel(1000.0, ConsumptionProfile(7800, 7000, "REFERENCE"), 218))
        assertNull(estimateFuel(1000.0, ConsumptionProfile(7800, 7800, "REFERENCE"), 0))
        assertEquals(
            0L,
            estimateFuel(0.0, ConsumptionProfile(7800, 7800, "REFERENCE"), 218)!!.minHalala,
        )
    }

    @Test
    fun archivedTripPriceAndProfileAreStable() {
        val o =
            Outing(
                startedAt = 1,
                state = EngineState(meters = 100000.0),
                fuelPriceHalala = 218,
                consumptionMinMl = 7800,
                consumptionMaxMl = 7800,
                estimateSource = "REFERENCE",
            )
        val newerSettings =
            Preferences(fuelPriceHalala = 233, engine = "CUSTOM", customConsumptionMl = 20000)
        assertEquals(1700L, outingEstimate(o, newerSettings, Economy(10.0, 10.0, .2))!!.minHalala)
    }

    @Test
    fun legacyTripFallsBackWithoutLosingItsData() {
        val o = Outing(startedAt = 1, state = EngineState(meters = 100000.0))
        val e = outingEstimate(o, Preferences(engine = "LANCER_20_CVT"), null)!!
        assertEquals(1700L, e.minHalala)
        assertEquals(100000.0, o.state.meters, 0.0)
    }
}
