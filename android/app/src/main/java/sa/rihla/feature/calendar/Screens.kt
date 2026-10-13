package sa.rihla.feature.calendar

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
import com.google.android.gms.location.*
import java.time.*
import java.time.format.DateTimeFormatter
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
import sa.rihla.tracking.*
import sa.rihla.ui.*

@Composable
fun CalendarScreen(data: ScreenData, onOpen: (Long) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.now(SaudiZone)) }
    var selected by remember { mutableStateOf(LocalDate.now(SaudiZone)) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                val driven =
                                    data.outings.any {
                                        date >= day(it.startedAt) &&
                                            date <= day(it.endedAt ?: data.now)
                                    }
                                TextButton(
                                    onClick = { selected = date },
                                    colors =
                                        ButtonDefaults.textButtonColors(
                                            contentColor =
                                                if (date == selected)
                                                    MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface
                                        ),
                                ) {
                                    Text(num(d.toDouble(), 0) + if (driven) " •" else "")
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
            Text(selected.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Arabic)))
            Metric("outings_count", num(m.outings.toDouble(), 0))
            Metric("visits", num(m.visits.toDouble(), 0))
            Metric("distance", num(m.meters / 1000) + " " + tr("km"))
            Metric("university_time", duration(m.universityMs))
            Metric("total_spending", num(m.total / 100.0, 2) + " " + tr("sar"))
            Text(tr("stats_policy"), style = MaterialTheme.typography.labelSmall)
        }
        items(data.outings.filter { it.startedAt < end && (it.endedAt ?: data.now) >= start }) { o
            ->
            OutingCard(o, data.now) { onOpen(o.id) }
        }
    }
}
