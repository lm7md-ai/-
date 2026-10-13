package sa.rihla.feature.dashboard

import android.Manifest
import android.content.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
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
fun Dashboard(
    vm: JourneyViewModel,
    data: ScreenData,
    onOpen: (Long) -> Unit,
    onLive: () -> Unit,
    onExpense: () -> Unit,
) {
    val c = LocalContext.current
    val location =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (TrackingService.fineAllowed(c)) vm.start() else vm.error.value = "accuracy_notice"
        }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Heading("data_local")
            Text(tr("car_name"), color = MaterialTheme.colorScheme.secondary)
        }
        item { Metric("tracking_status", tr(data.active?.state?.phase ?: "idle")) }
        val active = data.active
        if (active != null) {
            item {
                Metric("total_time", duration(data.now - active.startedAt))
                Metric("distance", num(active.state.meters / 1000) + " " + tr("km"))
                EstimatedCostCard(active, data)
            }
            if (active.state.stopStart > 0)
                item {
                    Metric(
                        "stopped_time",
                        duration(data.now - active.state.stopStart),
                    )
                }
            if (active.state.universityArrival > 0)
                item {
                    Metric(
                        "university_time",
                        duration(data.now - active.state.universityArrival),
                    )
                }
            item {
                Action("show_map", click = onLive)
                Action("resume") {
                    if (TrackingService.fineAllowed(c)) vm.start()
                    else
                        location.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                }
                Text(tr("recovery"))
                Action("end_journey") { vm.end() }
            }
        } else
            item {
                Action("start_journey") {
                    if (TrackingService.fineAllowed(c)) vm.start()
                    else
                        location.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                }
            }
        item { Action("add_expense", click = onExpense) }
        val monthly = metrics(data, periodBounds(Period.MONTH))
        val weekly = metrics(data, periodBounds(Period.WEEK))
        item {
            Metric("week_university", num(weekly.universityTrips.toDouble(), 0))
            Metric("month_university", num(monthly.universityTrips.toDouble(), 0))
            Metric("month_distance", num(monthly.meters / 1000) + " " + tr("km"))
            Metric("month_spending", num(monthly.total / 100.0, 2) + " " + tr("sar"))
            val estimated =
                estimateTotals(
                    data.outings.filter {
                        it.startedAt >= periodBounds(Period.MONTH).first &&
                            it.startedAt < periodBounds(Period.MONTH).second
                    },
                    data.settings,
                    monthly.economy,
                )
            Metric(
                "estimated_month_cost",
                numberRange(estimated.first / 100.0, estimated.second / 100.0) + " " + tr("sar"),
            )
            Metric("university_time", duration(monthly.universityMs))
            Metric(
                "average_travel",
                monthly.averageTravelMs?.let { duration(it) } ?: tr("empty"),
            )
        }
        item {
            Heading("fuel_economy")
            EconomyCard(monthly.economy)
            Heading("recent")
        }
        items(data.outings.take(5), key = { it.id }) { OutingCard(it, data.now) { onOpen(it.id) } }
    }
}
