package sa.rihla.feature.statistics

import android.content.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import com.google.android.gms.location.*
import java.time.*
import kotlinx.coroutines.*
import sa.rihla.core.*
import sa.rihla.core.Period
import sa.rihla.data.*
import sa.rihla.feature.calendar.*
import sa.rihla.feature.dashboard.*
import sa.rihla.feature.expenses.*
import sa.rihla.feature.history.*
import sa.rihla.feature.onboarding.*
import sa.rihla.feature.settings.*
import sa.rihla.feature.statistics.*
import sa.rihla.feature.tripdetail.*
import sa.rihla.feature.vehicle.*
import sa.rihla.tracking.*
import sa.rihla.ui.*

@Composable
fun EconomyCard(e: Economy?) {
    if (e == null) Text(tr("insufficient_fuel"))
    else {
        Metric("km_l", num(e.kmPerLiter, 2))
        Metric("l_100", num(e.litersPer100Km, 2))
        Metric("sar_km", num(e.sarPerKm, 2))
    }
    Text(tr("fuel_method"), style = MaterialTheme.typography.bodySmall)
}

@Composable
fun MetricsList(m: Metrics) {
    Metric("outings_count", num(m.outings.toDouble(), 0))
    Metric("university_trips", num(m.universityTrips.toDouble(), 0))
    Metric("visits", num(m.visits.toDouble(), 0))
    Metric("distance", num(m.meters / 1000) + " " + tr("km"))
    Metric("driving_time", duration(m.drivingMs))
    Metric("stopped_time", duration(m.stoppedMs))
    Metric("university_time", duration(m.universityMs))
    Metric(
        "average_visit",
        if (m.visits > 0) duration(m.universityMs / m.visits) else tr("empty"),
    )
    listOf(
            "average_travel" to m.averageTravelMs,
            "fastest" to m.fastestMs,
            "longest" to m.longestMs,
        )
        .forEach { (key, v) ->
            Metric(key, v?.let { duration(it) } ?: tr("empty"))
        }
    Metric("fuel", num(m.fuel / 100.0, 2) + " " + tr("sar"))
    Metric("food", num(m.food / 100.0, 2) + " " + tr("sar"))
    Metric("other", num(m.other / 100.0, 2) + " " + tr("sar"))
    Metric("total_spending", num(m.total / 100.0, 2) + " " + tr("sar"))
    Metric(
        "average_spending",
        if (m.outings > 0) num(m.total / 100.0 / m.outings, 2) + " " + tr("sar") else tr("empty"),
    )
    Metric(
        "cost_km",
        if (m.meters > 0) num(m.total / 100.0 / (m.meters / 1000), 2) + " " + tr("sar_km")
        else tr("empty"),
    )
    Heading("fuel_economy")
    Text(tr("fuel_period"))
    EconomyCard(m.economy)
}

@Composable
fun StatisticsScreen(data: ScreenData, vm: JourneyViewModel? = null, onTrip: (Long) -> Unit = {}) {
    var period by remember { mutableStateOf(Period.MONTH) }
    val m = metrics(data, periodBounds(period))
    val previous = metrics(data, periodBounds(period, previous = true))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Period.entries.zip(listOf("week", "month", "year", "all")).forEach { (p, key) ->
                    FilterChip(
                        selected = p == period,
                        onClick = { period = p },
                        label = { Text(tr(key)) },
                    )
                }
            }
        }
        item {
            MetricGrid(
                listOf(
                    "distance" to (num(m.meters / 1000) + " " + tr("km")),
                    "total_spending" to (num(m.total / 100.0, 2) + " " + tr("sar")),
                    "outings_count" to num(m.outings.toDouble(), 0),
                    "driving_time" to duration(m.drivingMs),
                    "stopped_time" to duration(m.stoppedMs),
                    "university_time" to duration(m.universityMs),
                    "average_travel" to (m.averageTravelMs?.let { duration(it) } ?: tr("empty")),
                    "fastest" to (m.fastestMs?.let { duration(it) } ?: tr("empty")),
                )
            )
            Heading("seven_day_spending")
            SpendingChart(data.expenses, day(data.now))
            Panel("total_spending", androidx.compose.material.icons.Icons.Default.Payments) {
                Metric("fuel", num(m.fuel / 100.0, 2) + " " + tr("sar"))
                Metric("food", num(m.food / 100.0, 2) + " " + tr("sar"))
                Metric("other", num(m.other / 100.0, 2) + " " + tr("sar"))
                Metric("longest", m.longestMs?.let { duration(it) } ?: tr("empty"))
                Metric("visits", num(m.visits.toDouble(), 0))
                Metric("university_trips", num(m.universityTrips.toDouble(), 0))
            }
            Panel("fuel_economy") { EconomyCard(m.economy) }
            sa.rihla.ui.AdvancedAnalytics(data, periodBounds(period))
            if (vm != null) sa.rihla.ui.RouteComparisonPanel(vm, data, periodBounds(period), onTrip)
            val bounds = periodBounds(period)
            val estimated =
                estimateTotals(
                    data.outings.filter {
                        it.startedAt >= bounds.first && it.startedAt < bounds.second
                    },
                    data.settings,
                    m.economy,
                )
            Metric(
                "estimated_fuel_cost",
                numberRange(estimated.first / 100.0, estimated.second / 100.0) + " " + tr("sar"),
            )
            Text(tr("estimate_not_expense"))
            Text(tr("stats_policy"))
            if (period != Period.ALL) {
                Heading("comparison")
                Metric("distance_change", num((m.meters - previous.meters) / 1000) + " " + tr("km"))
                Metric(
                    "spending_change",
                    num((m.total - previous.total) / 100.0, 2) + " " + tr("sar"),
                )
                Metric(
                    "trips_change",
                    num((m.outings - previous.outings).toDouble(), 0),
                )
                Metric(
                    "travel_change",
                    if (m.averageTravelMs != null && previous.averageTravelMs != null)
                        if (m.averageTravelMs == previous.averageTravelMs) tr("comparison_equal")
                        else
                            tr(
                                if (m.averageTravelMs < previous.averageTravelMs)
                                    "comparison_faster"
                                else "comparison_slower"
                            ) +
                                " " +
                                duration(
                                    kotlin.math.abs(m.averageTravelMs - previous.averageTravelMs)
                                )
                    else tr("no_comparison"),
                )
            }
        }
    }
}
