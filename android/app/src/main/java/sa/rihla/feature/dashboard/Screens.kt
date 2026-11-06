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
    val app = context.applicationContext as sa.rihla.RihlaApp
    val transferRev by app.transfers.changes.collectAsState()
    val transferBalance =
        remember(transferRev, data.expenses, data.now) {
            UniversityFinanceCalculator.computeRemainingTransferBalance(
                transferRows = app.transfers.rows(),
                aliases = app.transfers.aliases,
                cutoffMs = app.transfers.cutoff,
                expenses = data.expenses,
                nowMs = data.now,
            )
        }
    val nextLecture =
        remember(data.schedule, data.outings, data.now) {
            UniversityDepartureAdvisor.nextLectureAdvice(
                schedule = data.schedule,
                outings = data.outings,
                nowMs = data.now,
            )
        }
    val warrantyAlerts =
        remember(data.expenses, data.now) {
            ReceiptStore.expiringWarranties(data.expenses, data.now)
        }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SectionTitle("يومك على الطريق", stamp(data.now).substringBefore(" · "))
        }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardShortcut("قيادة اللانسر", Icons.Default.Speed, Modifier.weight(1f)) {
                    sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit("cockpit")
                }
                DashboardShortcut("الخريطة", Icons.Default.Map, Modifier.weight(1f)) {
                    sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit("map")
                }
                DashboardShortcut("مكتبتي", Icons.Default.Headphones, Modifier.weight(1f)) {
                    sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit("audio")
                }
                DashboardShortcut(
                    "محفظتي",
                    Icons.Default.AccountBalanceWallet,
                    Modifier.weight(1f),
                ) {
                    sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit("expenses")
                }
            }
        }
        item {
            // v7.0: UQU Next Lecture & Smart Departure Card
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("🎓 جدول أم القرى والانطلاق الذكي", style = MaterialTheme.typography.titleMedium)
                        TextButton(
                            onClick = {
                                sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit(
                                    "temporary_places"
                                )
                            }
                        ) {
                            Text("إدارة الجدول")
                        }
                    }
                    if (nextLecture == null) {
                        Text(
                            "أضف محاضراتك الأسبوعية (الأحد–الخميس) ليحسب مشواري وقت الانطلاق الأمثل من متوسط زمن طريقك.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "${nextLecture.dayLabelArabic}: ${nextLecture.entry.courseTitle} · ${nextLecture.lectureStartFormatted}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (nextLecture.entry.buildingOrGate.isNotBlank()) {
                            Text("الموقع: ${nextLecture.entry.buildingOrGate}", style = MaterialTheme.typography.bodySmall)
                        }
                        val depNote =
                            if (nextLecture.isToday && (nextLecture.minutesUntilDeparture ?: 1) <= 0)
                                "انطلق الآن! (متوسط المشوار ${nextLecture.estimatedTravelMinutes} د + هامش أمان ${nextLecture.bufferMinutes} د)"
                            else
                                "الانطلاق المقترح: ${nextLecture.recommendedDepartureFormatted} (متوسط الطريق ${nextLecture.estimatedTravelMinutes} د)"
                        Text(depNote, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (transferBalance.configured) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("💸 الرصيد الفعلي المتبقي", style = MaterialTheme.typography.titleMedium)
                            TextButton(
                                onClick = {
                                    sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit(
                                        "father_transfers"
                                    )
                                }
                            ) {
                                Text("التفاصيل")
                            }
                        }
                        Text(
                            "${num(transferBalance.remainingHalala / 100.0, 2)} ر.س",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        val daysStr =
                            transferBalance.estimatedDaysRemaining?.let { " · يكفي نحو $it يومًا" }.orEmpty()
                        Text(
                            "معدل الصرف اليومي: ${num(transferBalance.dailyBurnRateHalala / 100.0, 1)} ر.س$daysStr",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (warrantyAlerts.isNotEmpty()) {
            item {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🧾 تنبيهات ضمان الفواتير (${warrantyAlerts.size})", style = MaterialTheme.typography.titleSmall)
                        warrantyAlerts.take(3).forEach { alert ->
                            val label = alert.expense.merchant.ifBlank { "مشتريات" }
                            Text(
                                if (alert.expired) "• انتهى ضمان $label مؤخرًا"
                                else "• ينتهي ضمان $label خلال ${alert.daysRemaining} يوم",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        item { TrackingStatusCard(active != null) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("TODAY" to "اليوم", "WEEK" to "الأسبوع", "MONTH" to "الشهر").forEach {
                    (value, label) ->
                    FilterChip(
                        selectedPeriod == value,
                        { selectedPeriod = value },
                        label = { Text(label) },
                    )
                }
            }
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

@Composable
private fun DashboardShortcut(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.padding(vertical = 16.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
