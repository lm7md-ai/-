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
    var editing by remember { mutableStateOf(false) }
    val details = data.details.find { it.outingId == o.id } ?: TripDetails(o.id)
    var replay by remember(o.id) { mutableStateOf(false) }
    var cursor by remember(o.id) { mutableIntStateOf(0) }
    val previewPoints =
        remember(points, replay, cursor) {
            if (replay) points.take(cursor.coerceIn(0, points.lastIndex.coerceAtLeast(0)) + 1)
            else points
        }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    details.title.takeIf { it.isNotBlank() } ?: tr(o.classification),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.favorite(o.id) }) {
                    Icon(
                        if (details.favorite) androidx.compose.material.icons.Icons.Default.Star
                        else androidx.compose.material.icons.Icons.Default.StarBorder,
                        tr("favorite"),
                    )
                }
                IconButton(onClick = { editing = true }) {
                    Icon(androidx.compose.material.icons.Icons.Default.Edit, tr("edit_journey"))
                }
            }
            if (details.note.isNotBlank()) Text(details.note)
            RouteMap(
                previewPoints,
                if (replay)
                    events.filter { it.startedAt <= (previewPoints.lastOrNull()?.recordedAt ?: 0L) }
                else events,
                data.places,
                Modifier.fillMaxWidth().height(320.dp),
                stops =
                    if (replay)
                        stops.filter {
                            it.startedAt <= (previewPoints.lastOrNull()?.recordedAt ?: 0L)
                        }
                    else stops,
                onStopPick = { selectedStop = it.id },
            )
            Text(tr("attribution"), style = MaterialTheme.typography.labelSmall)
            if (data.settings.offlineAttribution.isNotEmpty())
                Text(data.settings.offlineAttribution, style = MaterialTheme.typography.labelSmall)
            Text(tr("route_legend"))
            Text(tr("map_controls"), style = MaterialTheme.typography.labelSmall)
            if (o.endedAt != null && points.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        replay,
                        {
                            replay = it
                            cursor = if (it) 0 else points.lastIndex
                        },
                    )
                    Text(tr("route_replay"))
                }
                if (replay) {
                    Slider(
                        cursor.toFloat(),
                        { cursor = it.toInt() },
                        valueRange = 0f..points.lastIndex.toFloat(),
                    )
                    previewPoints.lastOrNull()?.let { point ->
                        Text(
                            stamp(point.recordedAt, seconds = true) +
                                " · " +
                                num(point.speed * 3.6) +
                                " " +
                                tr("km_hour")
                        )
                    }
                }
            }
            ExportDocument(
                vm,
                "export_gpx",
                "rihla-route-${o.id}.gpx",
                "application/gpx+xml",
                enabled = points.isNotEmpty(),
            ) {
                routeGpx(points)
            }
            if (points.isNotEmpty()) {
                MetricGrid(
                    listOf(
                        "recorded_points" to num(points.size.toDouble(), 0),
                        "gps_accuracy" to
                            (num(points.map { it.accuracy }.average()) + " " + tr("meters_unit")),
                    )
                )
            }
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
    if (editing) {
        var title by remember(details) { mutableStateOf(details.title) }
        var note by remember(details) { mutableStateOf(details.note) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(tr("edit_journey")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        title,
                        { if (it.length <= 80) title = it },
                        label = { Text(tr("journey_title")) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        note,
                        { if (it.length <= 2000) note = it },
                        label = { Text(tr("journey_note")) },
                        minLines = 3,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.tripDetails(o.id, title, note)
                        editing = false
                    }
                ) {
                    Text(tr("save"))
                }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(tr("cancel")) } },
        )
    }
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
