package sa.rihla.feature.tripdetail

import android.content.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.*
import java.time.*
import kotlinx.coroutines.*
import sa.rihla.core.*
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
import sa.rihla.map.RouteMap
import sa.rihla.tracking.*
import sa.rihla.ui.*

@Composable
fun OutingDetail(vm: JourneyViewModel, data: ScreenData, o: Outing, onExpense: () -> Unit) {
    val points by
        remember(o.id) { vm.repo.dao.points(o.id) }.collectAsStateWithLifecycle(emptyList())
    val events by
        remember(o.id) { vm.repo.dao.events(o.id) }.collectAsStateWithLifecycle(emptyList())
    var deleting by remember { mutableStateOf(false) }
    val stops = remember(o, events) { stopDetails(o, events) }
    var selectedStop by remember(o.id) { mutableStateOf<Long?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            RouteMap(
                points,
                events,
                data.places,
                Modifier.fillMaxWidth().height(320.dp),
                stops = stops,
                onStopPick = { selectedStop = it.id },
            )
            Text(tr("attribution"), style = MaterialTheme.typography.labelSmall)
            if (data.settings.offlineAttribution.isNotEmpty())
                Text(data.settings.offlineAttribution, style = MaterialTheme.typography.labelSmall)
            Text(tr("route_legend"))
            Text(tr("map_controls"), style = MaterialTheme.typography.labelSmall)
        }
        item {
            Metric("started", stamp(o.startedAt))
            Metric("ended", o.endedAt?.let { stamp(it) } ?: tr("active"))
            Metric("tracking_status", tr(o.state.phase))
            Metric("distance", num(o.state.meters / 1000) + " " + tr("km"))
            Metric(
                "total_time",
                duration((o.endedAt ?: data.now) - o.startedAt),
            )
            Metric("driving_time", duration(o.state.drivingMs))
            Metric(
                "stopped_time",
                duration(
                    o.state.stoppedMs +
                        if (o.state.stopStart > 0) data.now - o.state.stopStart else 0
                ),
            )
            Metric(
                "university_time",
                duration(
                    o.state.universityMs +
                        if (o.state.visitConfirmed)
                            (o.state.universityExit.takeIf { it > 0 } ?: data.now) -
                                o.state.universityArrival
                        else 0
                ),
            )
            Metric(
                "total_spending",
                num(
                    data.expenses.filter { it.outingId == o.id }.sumOf { it.amountHalala } / 100.0,
                    2,
                ) + " " + tr("sar"),
            )
            if (o.state.gapMs > 0) Text(tr("gap_notice"))
            EstimatedCostCard(o, data)
        }
        item {
            Heading("classification")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("HOME_TO_UNIVERSITY", "UNIVERSITY_TO_HOME", "OTHER").forEach { key ->
                    FilterChip(
                        selected = o.classification == key,
                        onClick = { vm.classify(o.id, key) },
                        label = { Text(tr(key)) },
                    )
                }
            }
        }
        items(stops, key = { "stop-${it.id}" }) { stop ->
            Card(Modifier.fillMaxWidth().clickable { selectedStop = stop.id }) {
                Column(Modifier.padding(12.dp)) {
                    Metric(
                        "stop",
                        stamp(stop.startedAt) + " · " + duration(stop.durationMs(data.now)),
                    )
                    Text(tr("tap_stop_details"))
                }
            }
        }
        items(events.filter { it.type == "VISIT" }) { e ->
            Metric(
                if (e.type == "VISIT") "visit" else "stop",
                stamp(e.startedAt) + " · " + duration(e.endedAt - e.startedAt),
            )
        }
        item {
            Action("add_expense", click = onExpense)
            if (o.endedAt == null) Action("end_journey") { vm.end() }
            else Action("delete") { deleting = true }
        }
    }
    if (deleting) Confirm("delete_confirmation", { deleting = false }) { vm.delete(o.id) }
    stops
        .firstOrNull { it.id == selectedStop }
        ?.let { stop ->
            AlertDialog(
                onDismissRequest = { selectedStop = null },
                title = { Text(tr("stop_details")) },
                text = {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Metric("arrival_time", stamp(stop.startedAt, seconds = true))
                        Metric(
                            "departure_time",
                            stop.endedAt?.let { stamp(it, seconds = true) } ?: tr("still_stopped"),
                        )
                        Metric("stopped_time", duration(stop.durationMs(data.now)))
                        Metric("latitude", num(stop.latitude, 6))
                        Metric("longitude", num(stop.longitude, 6))
                        Metric("classification", tr(o.classification))
                        val expenses =
                            data.expenses.filter {
                                it.outingId == o.id &&
                                    it.createdAt >= stop.startedAt &&
                                    it.createdAt < (stop.endedAt ?: data.now + 1)
                            }
                        Metric(
                            "stop_spending",
                            num(expenses.sumOf { it.amountHalala } / 100.0, 2) + " " + tr("sar"),
                        )
                        expenses.forEach {
                            Metric(
                                it.category.lowercase(),
                                stamp(it.createdAt) +
                                    " · " +
                                    num(it.amountHalala / 100.0, 2) +
                                    " " +
                                    tr("sar"),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedStop = null }) { Text(tr("done")) }
                },
            )
        }
}
