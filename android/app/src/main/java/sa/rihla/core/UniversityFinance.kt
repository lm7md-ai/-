package sa.rihla.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong
import sa.rihla.data.*

data class UniversityDayCostSummary(
    val universityDaysCount: Int,
    val averageUniversityDayHalala: Long,
    val averageFuelPerUniDayHalala: Long,
    val averagePersonalPerUniDayHalala: Long,
    val currentMonthUniTotalHalala: Long,
    val currentMonthUniDays: Int,
    val averageOffDayHalala: Long,
)

data class RemainingTransferBalanceSummary(
    val configured: Boolean,
    val cutoffMs: Long,
    val totalTransfersHalala: Long,
    val totalExpensesHalala: Long,
    val remainingHalala: Long,
    val dailyBurnRateHalala: Long,
    val estimatedDaysRemaining: Long?,
    val lastTransferAmountHalala: Long?,
    val lastTransferAt: Long?,
)

object UniversityFinanceCalculator {
    fun computeUniversityDayCosts(
        outings: List<Outing>,
        events: List<JourneyEvent>,
        expenses: List<Expense>,
        defaultProfile: ConsumptionProfile,
        defaultFuelPriceHalala: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): UniversityDayCostSummary {
        val visitOutingIds =
            events.filter { it.type == "VISIT" && it.confirmed }.map { it.outingId }.toSet()
        val uniOutings =
            outings.filter {
                it.state.visitConfirmed ||
                    it.state.visits > 0 ||
                    it.classification == "HOME_TO_UNIVERSITY" ||
                    it.classification == "UNIVERSITY_TO_HOME" ||
                    it.id in visitOutingIds
            }
        val uniDates: Set<LocalDate> =
            uniOutings
                .map { Instant.ofEpochMilli(it.startedAt).atZone(SaudiZone).toLocalDate() }
                .toSet()

        fun outingFuelHalala(o: Outing): Long {
            val profile =
                if (o.consumptionMinMl > 0 && o.consumptionMaxMl >= o.consumptionMinMl)
                    ConsumptionProfile(o.consumptionMinMl, o.consumptionMaxMl, o.estimateSource)
                else defaultProfile
            val price = if (o.fuelPriceHalala > 0) o.fuelPriceHalala else defaultFuelPriceHalala
            val est = estimateFuel(o.state.meters, profile, price) ?: return 0L
            return ((est.minHalala + est.maxHalala) / 2L)
        }

        val currentMonth = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(SaudiZone))
        var totalUniFuel = 0L
        var totalUniPersonal = 0L
        var monthUniTotal = 0L
        var monthUniDays = 0

        val outingsByDate = outings.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(SaudiZone).toLocalDate() }
        val nonFuelExpensesByDate =
            expenses
                .filter { it.category != "FUEL" }
                .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(SaudiZone).toLocalDate() }

        for (date in uniDates) {
            val dayOutings = outingsByDate[date].orEmpty()
            val fuelCost = dayOutings.sumOf { outingFuelHalala(it) }
            val personalCost = nonFuelExpensesByDate[date].orEmpty().sumOf { it.amountHalala }
            totalUniFuel += fuelCost
            totalUniPersonal += personalCost
            if (YearMonth.from(date) == currentMonth) {
                monthUniDays++
                monthUniTotal += fuelCost + personalCost
            }
        }

        // Compare against off-days (days with activity or spending that were NOT university days)
        val allActiveDates = (outingsByDate.keys + nonFuelExpensesByDate.keys).distinct()
        val offDates = allActiveDates.filter { it !in uniDates }
        val offTotal =
            offDates.sumOf { date ->
                val fCost = outingsByDate[date].orEmpty().sumOf { outingFuelHalala(it) }
                val pCost = nonFuelExpensesByDate[date].orEmpty().sumOf { it.amountHalala }
                fCost + pCost
            }
        val avgOff = if (offDates.isEmpty()) 0L else offTotal / offDates.size
        val count = uniDates.size
        val avgFuel = if (count == 0) 0L else totalUniFuel / count
        val avgPersonal = if (count == 0) 0L else totalUniPersonal / count

        return UniversityDayCostSummary(
            universityDaysCount = count,
            averageUniversityDayHalala = avgFuel + avgPersonal,
            averageFuelPerUniDayHalala = avgFuel,
            averagePersonalPerUniDayHalala = avgPersonal,
            currentMonthUniTotalHalala = monthUniTotal,
            currentMonthUniDays = monthUniDays,
            averageOffDayHalala = avgOff,
        )
    }

    fun computeRemainingTransferBalance(
        transferRows: List<TransferRow>,
        aliases: Set<String>,
        cutoffMs: Long,
        expenses: List<Expense>,
        nowMs: Long = System.currentTimeMillis(),
    ): RemainingTransferBalanceSummary {
        val totals = transferTotals(transferRows, aliases, cutoffMs)
        val effectiveCutoff =
            if (cutoffMs > 0L) cutoffMs
            else transferRows.minOfOrNull { it.time } ?: 0L
        val configured = cutoffMs > 0L || totals.count > 0
        if (!configured) {
            return RemainingTransferBalanceSummary(
                configured = false,
                cutoffMs = 0L,
                totalTransfersHalala = 0L,
                totalExpensesHalala = 0L,
                remainingHalala = 0L,
                dailyBurnRateHalala = 0L,
                estimatedDaysRemaining = null,
                lastTransferAmountHalala = null,
                lastTransferAt = null,
            )
        }
        val transfersInPeriod = if (cutoffMs > 0L) totals.fresh else totals.all
        val spentSinceCutoff =
            expenses.filter { it.createdAt >= effectiveCutoff }.sumOf { it.amountHalala }
        val remaining = transfersInPeriod - spentSinceCutoff

        // Daily burn rate over last 14 days (or since cutoff if shorter)
        val fourteenDaysAgo = nowMs - 14L * 86_400_000L
        val burnWindowStart = maxOf(effectiveCutoff, fourteenDaysAgo)
        val elapsedDays =
            ((nowMs - burnWindowStart).coerceAtLeast(86_400_000L).toDouble() / 86_400_000.0)
        val recentSpend =
            expenses.filter { it.createdAt in burnWindowStart..nowMs }.sumOf { it.amountHalala }
        val dailyBurn = (recentSpend / elapsedDays).roundToLong().coerceAtLeast(0L)
        val daysLeft =
            if (remaining <= 0L) 0L
            else if (dailyBurn > 0L) (remaining / dailyBurn)
            else null

        return RemainingTransferBalanceSummary(
            configured = true,
            cutoffMs = effectiveCutoff,
            totalTransfersHalala = transfersInPeriod,
            totalExpensesHalala = spentSinceCutoff,
            remainingHalala = remaining,
            dailyBurnRateHalala = dailyBurn,
            estimatedDaysRemaining = daysLeft,
            lastTransferAmountHalala = totals.last?.amount,
            lastTransferAt = totals.last?.time,
        )
    }
}
