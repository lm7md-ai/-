package sa.rihla.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sa.rihla.R
import sa.rihla.core.*
import sa.rihla.data.Outing
import sa.rihla.tracking.LocationPicker
import sa.rihla.ui.*

@Composable
private fun endpoint(stop: Int?, live: Boolean, start: Boolean): String =
    stop?.let { stringResource(R.string.map_leg_stop, it + 1) }
        ?: stringResource(
            when {
                start -> R.string.map_leg_start
                live -> R.string.map_leg_latest
                else -> R.string.map_leg_end
            }
        )

@Composable
fun StopRouteMetrics(leg: StopRouteSection) {
    Text(
        stringResource(
            R.string.map_leg_endpoints,
            endpoint(leg.fromStop, false, true),
            endpoint(leg.toStop, leg.liveEnd, false),
        ),
        style = MaterialTheme.typography.titleMedium,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AtlasStat(
            duration(leg.elapsedMs),
            stringResource(R.string.map_leg_duration),
            Modifier.weight(1f),
        )
        leg.samples?.let {
            AtlasStat(
                stringResource(R.string.map_leg_distance_value, num(it.recordedMeters / 1000, 3)),
                stringResource(R.string.map_leg_distance),
                Modifier.weight(1f),
            )
        }
    }
    Text(stringResource(R.string.map_leg_departure, stamp(leg.startedAt, true)))
    Text(stringResource(R.string.map_leg_arrival, stamp(leg.endedAt, true)))
    leg.samples?.let { samples ->
        Text(stringResource(R.string.map_leg_moving, duration(samples.movingMs)))
        Text(stringResource(R.string.map_leg_stopped, duration(samples.stoppedMs)))
        if (samples.gaps > 0)
            Text(
                stringResource(R.string.map_leg_gaps, duration(samples.gapMs)),
                color = MaterialTheme.colorScheme.error,
            )
    } ?: Text(stringResource(R.string.map_leg_no_samples))
    if (leg.unsampledMs > 0)
        Text(
            stringResource(R.string.map_leg_unsampled, duration(leg.unsampledMs)),
            style = MaterialTheme.typography.bodySmall,
        )
    Text(stringResource(R.string.map_leg_boundary_note), style = MaterialTheme.typography.bodySmall)
    if (leg.liveEnd)
        Text(
            stringResource(R.string.map_leg_live_note, stamp(leg.endedAt, true)),
            style = MaterialTheme.typography.bodySmall,
        )
}

/** Shared by the full map and trip detail. All calculations leave the trip ledger intact. */
@Composable
fun StopRouteDialog(
    stop: StopDetails,
    stops: List<StopDetails>,
    analysis: RouteAnalysis?,
    trip: Outing,
    now: Long,
    onHighlight: (IntRange) -> Unit,
    onDismiss: () -> Unit,
    extraDetails: @Composable () -> Unit = {},
    locate: (suspend () -> Fix?)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val legs = remember(analysis, trip) { analysis?.let { StopRouteSections(it, trip) } }
    var mode by remember(stop.id) { mutableIntStateOf(-1) }
    var locating by remember(stop.id) { mutableStateOf(false) }
    var fix by remember(stop.id) { mutableStateOf<Fix?>(null) }
    var failed by remember(stop.id) { mutableStateOf(false) }
    val leg =
        when (mode) {
            0 -> legs?.before(stop)
            1 -> legs?.after(stop)
            2 -> legs?.toLatest(stop)
            else -> null
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    R.string.map_leg_stop,
                    stops.sortedBy { it.startedAt }.indexOfFirst { it.id == stop.id } + 1,
                )
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Metric("arrival_time", stamp(stop.startedAt, true))
                Metric(
                    "departure_time",
                    stop.endedAt?.let { stamp(it, true) } ?: tr("still_stopped"),
                )
                Metric("stopped_time", duration(stop.durationMs(now)))
                Metric("latitude", num(stop.latitude, 6))
                Metric("longitude", num(stop.longitude, 6))
                extraDetails()
                HorizontalDivider()
                TextButton(onClick = { mode = 0 }, enabled = legs != null) {
                    Text(stringResource(R.string.map_stop_before))
                }
                TextButton(onClick = { mode = 1 }, enabled = legs != null && stop.endedAt != null) {
                    Text(stringResource(R.string.map_stop_after))
                }
                if (trip.endedAt == null)
                    TextButton(
                        onClick = { mode = 2 },
                        enabled = legs?.toLatest(stop) != null,
                    ) {
                        Text(stringResource(R.string.map_stop_to_live))
                    }
                if (stop.endedAt == null) Text(stringResource(R.string.map_stop_not_departed))
                if (analysis == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                leg?.let { value ->
                    StopRouteMetrics(value)
                    value.range?.let { range ->
                        FilledTonalButton(onClick = { onHighlight(range) }) {
                            Text(stringResource(R.string.map_leg_fit))
                        }
                    }
                }
                HorizontalDivider()
                OutlinedButton(
                    enabled = !locating,
                    onClick = {
                        scope.launch {
                            locating = true
                            failed = false
                            fix = null
                            try {
                                fix =
                                    if (locate != null) locate()
                                    else LocationPicker.current(context)
                                failed = fix == null
                            } finally {
                                locating = false
                            }
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (locating) R.string.map_stop_locating
                            else R.string.map_stop_current_distance
                        )
                    )
                }
                if (failed)
                    Text(
                        stringResource(R.string.map_stop_fix_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                fix?.let { current ->
                    Text(
                        stringResource(
                            R.string.map_stop_direct,
                            num(
                                distance(stop.latitude, stop.longitude, current.lat, current.lon) /
                                    1000,
                                3,
                            ),
                        )
                    )
                    Text(
                        stringResource(
                            R.string.map_stop_fix_time,
                            stamp(current.time, true),
                            num(current.accuracy),
                        )
                    )
                    Text(
                        stringResource(R.string.map_stop_direct_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.map_leg_close)) }
        },
    )
}
