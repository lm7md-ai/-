package sa.rihla.core

import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test
import sa.rihla.data.*

class AdvancedFeaturesTest {
    @Test
    fun odometerMeasuresFuelEvenWhenGpsHasGapsAndIncludesPartialFills() {
        val fills =
            listOf(
                Fill(1, 10000, 45000, true, 0.0, false, 100_000_000.0),
                Fill(2, 2180, 10000, false, 100.0, false),
                Fill(3, 6540, 30000, true, 200.0, false, 100_500_000.0),
            )
        val economy = fuelEconomy(fills)!!
        assertEquals(8.0, economy.litersPer100Km, .00001)
        assertEquals(.1744, economy.sarPerKm, .00001)
        assertNull(
            fuelEconomy(fills.dropLast(1) + fills.last().copy(odometerMeters = 99_500_000.0))
        )
        assertNull(fuelEconomy(fills.map { it.copy(odometerMeters = null) }))
    }

    @Test
    fun merchantRulesChooseTheMostSpecificMatchAndLeaveUnknownMerchantsAlone() {
        val rules = listOf(MerchantRule(1, "store", "OTHER"), MerchantRule(2, "test store", "FOOD"))
        assertEquals("FOOD", merchantCategory("TEST STORE", rules))
        assertEquals("OTHER", merchantCategory("unknown", rules))
        assertEquals(
            "FUEL",
            merchantCategory("محطة ١٢٣", listOf(MerchantRule(1, "محطة 123", "FUEL"))),
        )
    }

    @Test
    fun monthlySchedulesClampShortMonthsAndHaveDistinctPaymentKeys() {
        val item = RecurringExpense(1, "جدول", "OTHER", 1000, 31)
        assertEquals("2028-02-29", recurringDate(item, YearMonth.of(2028, 2)).toString())
        assertEquals("2026-02-28", recurringDate(item, YearMonth.of(2026, 2)).toString())
        assertNotEquals(
            recurringKey(1, YearMonth.of(2026, 1)),
            recurringKey(1, YearMonth.of(2026, 2)),
        )
    }

    @Test
    fun trackingRejectsOldMockAndPoorSignalsWithoutRejectingGoodFixes() {
        assertTrue(usableLocation(10_000, 10.0, false))
        assertFalse(usableLocation(60_001, 10.0, false))
        assertFalse(usableLocation(1000, 10.0, true))
        assertFalse(usableLocation(-1, 10.0, false))
        assertFalse(usableLocation(1000, Double.NaN, false))
        assertFalse(usableLocation(1000, 61.0, false))
        assertEquals(15000L, trackingInterval(true, true))
        assertEquals(30000L, trackingInterval(true, false))
        assertEquals(3000L, trackingInterval(false, true))
        assertEquals(5000L, trackingInterval(false, false))
    }

    @Test
    fun corridorAnalysisGroupsRepeatedRoutesButSeparatesDifferentRoadsAndGaps() {
        fun route(id: Long, middle: Double, gap: Long = 0) =
            Outing(id, 1000, 11000, state = EngineState(meters = 5000.0, gapMs = gap)) to
                listOf(
                    RoutePoint(
                        outingId = id,
                        latitude = 21.0,
                        longitude = 39.0,
                        accuracy = 5.0,
                        speed = 10.0,
                        altitude = 0.0,
                        bearing = 0.0,
                        recordedAt = 1000,
                    ),
                    RoutePoint(
                        outingId = id,
                        latitude = middle,
                        longitude = 39.05,
                        accuracy = 5.0,
                        speed = 10.0,
                        altitude = 0.0,
                        bearing = 0.0,
                        recordedAt = 5000,
                    ),
                    RoutePoint(
                        outingId = id,
                        latitude = 21.1,
                        longitude = 39.1,
                        accuracy = 5.0,
                        speed = 10.0,
                        altitude = 0.0,
                        bearing = 0.0,
                        recordedAt = 11000,
                    ),
                )
        val groups =
            routeComparisons(
                listOf(route(1, 21.05), route(2, 21.05), route(3, 21.09), route(4, 21.05, 1000))
            )
        assertEquals(2, groups.size)
        assertEquals(2, groups.first().trips)
        assertEquals(10000L, groups.first().averageMs)
    }
}
