package sa.rihla.core

import java.time.Instant
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import sa.rihla.data.*

class ProfessionalFeaturesTest {
    @Test
    fun spendingUsesSaudiDayBoundariesAndIncludesEmptyDays() {
        val time = Instant.parse("2026-10-13T21:30:00Z").toEpochMilli()
        val days =
            spendingDays(
                listOf(Expense(category = "OTHER", amountHalala = 2500, createdAt = time)),
                LocalDate.of(2026, 10, 14),
            )
        assertEquals(7, days.size)
        assertEquals(2500L, days.last().halala)
        assertEquals(0L, days[5].halala)
    }

    @Test
    fun favoritesSearchAndSortingKeepRealOutingIds() {
        val outings =
            listOf(
                Outing(
                    id = 1,
                    startedAt = 1000,
                    endedAt = 11000,
                    state = EngineState(meters = 5000.0),
                ),
                Outing(
                    id = 2,
                    startedAt = 12000,
                    endedAt = 14000,
                    state = EngineState(meters = 20000.0),
                ),
            )
        val details = listOf(TripDetails(1, "مشوار الجامعة", "صباح", true))
        assertEquals(listOf(1L), filterOutings(outings, details, "الجامعة", true).map { it.id })
        assertEquals(
            listOf(2L, 1L),
            filterOutings(outings, details, "", false, sort = "DISTANCE").map { it.id },
        )
        assertEquals(
            listOf(1L, 2L),
            filterOutings(outings, details, "", false, sort = "DURATION").map { it.id },
        )
        assertEquals(
            listOf(2L),
            filterOutings(outings, details, "", false, from = 12000).map { it.id },
        )
    }

    @Test
    fun budgetDoesNotDivideByZeroOrOverflowProgress() {
        assertEquals(0f, budgetProgress(100, 0), 0f)
        assertEquals(.5f, budgetProgress(500, 1000), 0f)
        assertEquals(1f, budgetProgress(1500, 1000), 0f)
    }

    @Test
    fun csvQuotesCommasNewlinesAndSpreadsheetFormulas() {
        val csv =
            expensesCsv(
                listOf(
                    Expense(
                        category = "OTHER",
                        amountHalala = 2450,
                        createdAt = 1000,
                        merchant = "=SUM(1,2)\n\"store\"",
                    )
                )
            )
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"24.50\""))
        assertTrue(csv.contains("'=SUM(1,2)\n\"\"store\"\""))
    }

    @Test
    fun gpxPreservesTimestampsAndBreaksAcrossGpsGaps() {
        fun point(time: Long, gap: Boolean) =
            RoutePoint(
                outingId = 1,
                latitude = 21.5,
                longitude = 39.2,
                accuracy = 5.0,
                speed = 10.0,
                altitude = 10.0,
                bearing = 0.0,
                recordedAt = time,
                breakBefore = gap,
            )
        val xml = routeGpx(listOf(point(1000, false), point(2000, false), point(200000, true)))
        val document =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals(3, document.getElementsByTagName("trkpt").length)
        assertEquals(2, document.getElementsByTagName("trkseg").length)
        assertEquals(
            "1970-01-01T00:00:01Z",
            document.getElementsByTagName("time").item(0).textContent,
        )
    }
}
