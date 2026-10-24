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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sa.rihla.R
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
    var selectedPeriod by remember { mutableStateOf("MONTH") }
    val bounds =
        when (selectedPeriod) {
            "TODAY" ->
                day(data.now).atStartOfDay(SaudiZone).toInstant().toEpochMilli() to
                    day(data.now).plusDays(1).atStartOfDay(SaudiZone).toInstant().toEpochMilli()
            "WEEK" -> periodBounds(Period.WEEK)
            else -> periodBounds(Period.MONTH)
        }
    val month = metrics(data, bounds)
    val active = data.active
    fun navigate(page: String) {
        sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit(page)
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stamp(data.now).substringBefore(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.atlas_today),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                }
            }
        }
        item {
            DeparturePanel(
                tr(if (active == null) "ready_for_road" else "live_journey"),
                if (active == null) tr("hero_headline")
                else num(active.state.meters / 1000) + " " + tr("km"),
                if (active == null) tr(if (data.settings.auto) "auto_tracking" else "manual_mode")
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
        if (active != null)
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { vm.end() }, modifier = Modifier.weight(1f)) {
                        Text(tr("end_journey"))
                    }
                    FilledTonalButton(onClick = onExpense, modifier = Modifier.weight(1f)) {
                        Text(tr("add_expense"))
                    }
                }
            }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.atlas_overview),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (active == null)
                    TextButton(onClick = onExpense) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("add_expense"))
                    }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                        "TODAY" to R.string.atlas_day,
                        "WEEK" to R.string.atlas_week,
                        "MONTH" to R.string.atlas_month,
                    )
                    .forEach { (value, label) ->
                        FilterChip(
                            selectedPeriod == value,
                            { selectedPeriod = value },
                            label = { Text(stringResource(label)) },
                        )
                    }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                AtlasStat(
                    num(month.meters / 1000) + " " + tr("km"),
                    stringResource(R.string.atlas_distance_period),
                    Modifier.weight(1f),
                )
                AtlasStat(
                    num(month.total / 100.0, 2) + " " + tr("sar"),
                    stringResource(R.string.atlas_spending_period),
                    Modifier.weight(1f),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                AtlasStat(
                    num(month.outings.toDouble(), 0),
                    tr("outings_count"),
                    Modifier.weight(1f),
                )
                AtlasStat(duration(month.drivingMs), tr("driving_time"), Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("recent"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { navigate("history") }) {
                    Text(stringResource(R.string.atlas_all_journeys))
                }
            }
        }
        if (data.outings.isEmpty()) item { EmptyPanel("no_journeys_yet", "first_journey_hint") }
        items(data.outings.take(3), key = { it.id }) { outing ->
            OutingCard(outing, data.now, data.details.find { it.outingId == outing.id }) {
                onOpen(outing.id)
            }
        }
        item {
            SectionTitle(stringResource(R.string.atlas_quick_tools))
            AtlasLink(
                stringResource(R.string.atlas_wallet),
                stringResource(R.string.atlas_wallet_hint),
                Icons.Default.AccountBalanceWallet,
            ) {
                navigate("expenses")
            }
            Spacer(Modifier.height(8.dp))
            AtlasLink(
                stringResource(R.string.atlas_assistant),
                stringResource(R.string.atlas_models),
                Icons.Default.AutoAwesome,
            ) {
                navigate("assistant")
            }
        }
        item { TrackingStatusCard(active != null) }
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
                        Text(tr("check_location_now"))
                    }
                    Metric("distance", num(active.state.meters / 1000) + " " + tr("km"))
                    Metric("driving_time", duration(active.state.drivingMs))
                    if (active.state.stopStart > 0)
                        Metric("stopped_time", duration(data.now - active.state.stopStart))
                    EstimatedCostCard(active, data)
                }
            }
    }
}
