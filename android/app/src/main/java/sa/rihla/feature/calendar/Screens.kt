package sa.rihla.feature.calendar

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import sa.rihla.RihlaApp
import sa.rihla.core.*
import sa.rihla.ui.*

@Composable
fun CalendarScreen(data: ScreenData, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as RihlaApp
    var month by remember { mutableStateOf(YearMonth.now(SaudiZone)) }
    var selected by remember { mutableStateOf(LocalDate.now(SaudiZone)) }
    var storyNodes by remember { mutableStateOf<List<DailyStoryNode>>(emptyList()) }

    LaunchedEffect(selected, data.outings.size, data.expenses.size) {
        storyNodes =
            withContext(Dispatchers.IO) {
                DailyStoryBuilder.buildForDate(
                    date = selected,
                    outings = data.outings,
                    events = app.repository.dao.allEvents(),
                    expenses = data.expenses,
                    rules = data.rules,
                    parkingPins = app.repository.dao.parkingRecords(),
                    tracks = app.audio.dao.tracks().first(),
                    bookmarks = app.audio.dao.bookmarks().first(),
                    transfers = app.transfers.rows(),
                    aliases = app.transfers.aliases,
                    defaultProfile = ConsumptionProfile(5500, 7800, "REFERENCE"),
                    defaultFuelPriceHalala = data.settings.fuelPriceHalala,
                )
            }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { month = month.minusMonths(1) }) {
                    Text(tr("month_previous"))
                }
                Text(month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", Arabic)))
                TextButton(onClick = { month = month.plusMonths(1) }) { Text(tr("month_next")) }
            }
        }
        item {
            val offset = month.atDay(1).dayOfWeek.value % 7
            val cells = offset + month.lengthOfMonth()
            Column {
                Row(Modifier.fillMaxWidth()) {
                    (0..6).forEach { n ->
                        Text(
                            month
                                .atDay(1)
                                .with(
                                    java.time.temporal.TemporalAdjusters.previousOrSame(
                                        DayOfWeek.SUNDAY
                                    )
                                )
                                .plusDays(n.toLong())
                                .format(DateTimeFormatter.ofPattern("EEEEE", Arabic)),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                for (row in 0 until (cells + 6) / 7) Row(Modifier.fillMaxWidth()) {
                    for (col in 0..6) {
                        val d = row * 7 + col - offset + 1
                        Box(
                            Modifier.weight(1f).height(52.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (d in 1..month.lengthOfMonth()) {
                                val date = month.atDay(d)
                                val hasActivity =
                                    data.outings.any {
                                        date >= day(it.startedAt) &&
                                            date <= day(it.endedAt ?: data.now)
                                    } ||
                                        data.expenses.any { day(it.createdAt) == date }
                                TextButton(
                                    onClick = { selected = date },
                                    colors =
                                        ButtonDefaults.textButtonColors(
                                            containerColor =
                                                if (date == selected)
                                                    MaterialTheme.colorScheme.primary
                                                else androidx.compose.ui.graphics.Color.Transparent,
                                            contentColor =
                                                if (date == selected)
                                                    MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurface,
                                        ),
                                ) {
                                    Text(num(d.toDouble(), 0) + if (hasActivity) " •" else "")
                                }
                            }
                        }
                    }
                }
            }
        }
        val start = selected.atStartOfDay(SaudiZone).toInstant().toEpochMilli()
        val end = selected.plusDays(1).atStartOfDay(SaudiZone).toInstant().toEpochMilli()
        val m = metrics(data, start to end)
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selected.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Arabic)),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (storyNodes.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            val summaryText =
                                buildString {
                                    append("📖 قصة يومي — ${selected}:\n")
                                    storyNodes.forEach { n ->
                                        append("• [${n.formattedTime}] ${n.title}")
                                        if (n.badgeText.isNotBlank()) append(" (${n.badgeText})")
                                        append(" — ${n.subtitle}\n")
                                    }
                                }
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, summaryText),
                                    "مشاركة قصة اليوم",
                                )
                            )
                        }
                    ) {
                        Text("مشاركة قصة اليوم")
                    }
                }
            }
            MetricGrid(
                listOf(
                    "outings_count" to num(m.outings.toDouble(), 0),
                    "distance" to (num(m.meters / 1000) + " " + tr("km")),
                    "university_time" to duration(m.universityMs),
                    "total_spending" to (num(m.total / 100.0, 2) + " " + tr("sar")),
                )
            )
            Text("📖 قصة اليوم (الخط الزمني الموحد)", style = MaterialTheme.typography.titleMedium)
            if (storyNodes.isEmpty()) {
                Text(
                    "لا توجد أحداث مسجلة في هذا اليوم بعد.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(
            storyNodes,
            key = { "${it.timestampMs}_${it.type}_${it.title.hashCode()}" },
        ) { node ->
            OutlinedCard(
                onClick = {
                    node.outingId?.let(onOpen)
                        ?: if (node.trackId != null) {
                            sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit("audio")
                        } else Unit
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${node.formattedTime} · ${node.title}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            node.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (node.badgeText.isNotBlank()) {
                        Text(
                            node.badgeText,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        items(data.outings.filter { it.startedAt < end && (it.endedAt ?: data.now) >= start }) { o ->
            OutingCard(o, data.now) { onOpen(o.id) }
        }
    }
}
