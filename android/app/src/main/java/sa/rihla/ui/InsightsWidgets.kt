package sa.rihla.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import sa.rihla.core.*
import sa.rihla.data.Expense

@Composable
fun SpendingChart(expenses: List<Expense>, today: LocalDate) {
    val days = remember(expenses, today) { spendingDays(expenses, today) }
    var selected by remember(today) { mutableIntStateOf(6) }
    val maximum = days.maxOf { it.halala }.coerceAtLeast(1)
    Panel("seven_day_spending", Icons.Default.BarChart) {
        Text(
            days[selected].date.format(DateTimeFormatter.ofPattern("EEEE d MMM", Arabic)) +
                " · " +
                num(days[selected].halala / 100.0, 2) +
                " " +
                tr("sar"),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            Modifier.fillMaxWidth().height(145.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEachIndexed { index, value ->
                val description = value.date.toString() + ": " + value.halala / 100.0
                Column(
                    Modifier.weight(1f)
                        .clickable { selected = index }
                        .semantics { contentDescription = description },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(110.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier.fillMaxWidth()
                                .height(110.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp),
                                )
                        )
                        if (value.halala > 0)
                            Box(
                                Modifier.fillMaxWidth()
                                    .height((110.0 * value.halala / maximum).toFloat().dp)
                                    .background(
                                        if (index == selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.primary.copy(alpha = .4f),
                                        RoundedCornerShape(8.dp),
                                    )
                            )
                    }
                    Text(
                        num(value.date.dayOfMonth.toDouble(), 0),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Text(
            tr("tap_chart_day"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun BudgetCard(data: ScreenData, spent: Long) {
    val budget = data.settings.monthlyBudgetHalala
    if (budget <= 0) return
    Panel("monthly_budget", Icons.Default.AccountBalanceWallet) {
        Text(
            num(spent / 100.0, 2) + " / " + num(budget / 100.0, 2) + " " + tr("sar"),
            style = MaterialTheme.typography.titleLarge,
        )
        LinearProgressIndicator(
            progress = { budgetProgress(spent, budget) },
            modifier = Modifier.fillMaxWidth(),
            color =
                if (spent > budget) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
        )
        Text(
            tr(if (spent > budget) "budget_exceeded" else "budget_remaining") +
                " " +
                num(kotlin.math.abs(budget - spent) / 100.0, 2) +
                " " +
                tr("sar")
        )
    }
}

@Composable
fun BudgetSettings(vm: JourneyViewModel, data: ScreenData) {
    var value by
        remember(data.settings.monthlyBudgetHalala) {
            mutableStateOf((data.settings.monthlyBudgetHalala / 100.0).toString())
        }
    var invalid by remember { mutableStateOf(false) }
    Panel("monthly_budget", Icons.Default.AccountBalanceWallet) {
        Text(tr("budget_hint"))
        FormField("amount", value, { value = it })
        if (invalid) Text(tr("invalid_expense"), color = MaterialTheme.colorScheme.error)
        Action("save") { invalid = !vm.budget(value) }
    }
}
