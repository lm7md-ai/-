package sa.rihla.feature.dashboard

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import sa.rihla.core.*
import sa.rihla.core.Period
import sa.rihla.tracking.TrackingService
import sa.rihla.ui.*

@Composable
fun Dashboard(
    vm: JourneyViewModel,
    data: ScreenData,
    onOpen: (Long) -> Unit,
    onLive: () -> Unit,
    onExpense: () -> Unit,
) {
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (TrackingService.fineAllowed(context)) vm.start()
            else vm.error.value = "accuracy_notice"
        }
    val month = metrics(data, periodBounds(Period.MONTH))
    val active = data.active
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Hero(
                tr(if (active == null) "ready_for_road" else "live_journey"),
                if (active == null) tr("hero_headline")
                else num(active.state.meters / 1000) + " " + tr("km"),
                if (active == null) tr("hero_subtitle")
                else duration(data.now - active.startedAt) + " · " + tr(active.state.phase),
                if (active == null) "start_journey" else "show_map",
            ) {
                if (active != null) onLive()
                else if (TrackingService.fineAllowed(context)) vm.start()
                else
                    permission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onExpense, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("add_expense"))
                }
                if (active != null)
                    OutlinedButton(onClick = { vm.end() }, modifier = Modifier.weight(1f)) {
                        Text(tr("end_journey"))
                    }
                else
                    StatusPill(
                        tr(if (data.settings.auto) "auto_tracking" else "manual_mode"),
                        data.settings.auto,
                    )
            }
        }
        item {
            Heading("this_month")
            MetricGrid(
                listOf(
                    "month_distance" to (num(month.meters / 1000) + " " + tr("km")),
                    "month_spending" to (num(month.total / 100.0, 2) + " " + tr("sar")),
                    "outings_count" to num(month.outings.toDouble(), 0),
                    "driving_time" to duration(month.drivingMs),
                )
            )
        }
        item { BudgetCard(data, month.total) }
        item { SpendingChart(data.expenses, day(data.now)) }
        if (active != null)
            item {
                Panel("live_journey", Icons.Default.MyLocation) {
                    OutlinedButton(
                        onClick = {
                            if (TrackingService.fineAllowed(context)) vm.start()
                            else
                                permission.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    )
                                )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tr("resume"))
                    }
                    if (active.state.stopStart > 0)
                        Metric("stopped_time", duration(data.now - active.state.stopStart))
                    EstimatedCostCard(active, data)
                }
            }
        item { Heading("recent") }
        if (data.outings.isEmpty()) item { EmptyPanel("no_journeys_yet", "first_journey_hint") }
        items(data.outings.take(5), key = { it.id }) { outing ->
            OutingCard(outing, data.now, data.details.find { it.outingId == outing.id }) {
                onOpen(outing.id)
            }
        }
    }
}
