package sa.rihla.feature.expenses

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.*
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
fun ExpensesScreen(data: ScreenData, vm: JourneyViewModel, onAdd: () -> Unit) {
    var deleting by remember { mutableStateOf<Long?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Action("add_expense", click = onAdd)
            Metric(
                "total_spending",
                num(data.expenses.sumOf { it.amountHalala } / 100.0, 2) + " " + tr("sar"),
            )
        }
        items(data.expenses, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        tr(
                            when (e.category) {
                                "FUEL" -> "fuel"
                                "FOOD" -> "food"
                                else -> "other"
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(num(e.amountHalala / 100.0, 2) + " " + tr("sar"))
                    Text(stamp(e.createdAt))
                    if (e.source == "SMS_NOTIFICATION") {
                        Text(tr("sms_imported"), style = MaterialTheme.typography.labelMedium)
                        if (e.merchant.isNotBlank()) Text(e.merchant)
                        run {
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                listOf("FUEL" to "fuel", "FOOD" to "food", "OTHER" to "other")
                                    .forEach { (category, label) ->
                                        FilterChip(
                                            e.category == category,
                                            { vm.categorizeImportedExpense(e.id, category) },
                                            label = { Text(tr(label)) },
                                        )
                                    }
                            }
                            TextButton(onClick = { deleting = e.id }) { Text(tr("delete")) }
                        }
                    }
                    data.fuel
                        .find { it.expenseId == e.id }
                        ?.let { f ->
                            Text(num(f.litersMl / 1000.0, 3) + " · " + tr("liters"))
                            Text(num(f.priceHalala / 100.0, 2) + " " + tr("price"))
                            if (f.full) Text(tr("full_tank"))
                        }
                }
            }
        }
    }
    if (deleting != null)
        Confirm("delete_confirmation", { deleting = null }) {
            vm.deleteImportedExpense(deleting!!)
        }
}

@Composable
fun ExpenseDialog(
    vm: JourneyViewModel,
    data: ScreenData,
    initialOuting: Long?,
    onDismiss: () -> Unit,
) {
    val c = LocalContext.current
    var category by remember { mutableStateOf("FUEL") }
    var amount by remember { mutableStateOf("") }
    var liters by remember { mutableStateOf("") }
    var price by remember { mutableStateOf((data.settings.fuelPriceHalala / 100.0).toString()) }
    var grade by remember { mutableStateOf(data.settings.fuelGrade) }
    var full by remember { mutableStateOf(false) }
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var outing by remember { mutableStateOf(initialOuting) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("add_expense")) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row {
                    listOf("FUEL" to "fuel", "FOOD" to "food", "OTHER" to "other").forEach {
                        (v, key) ->
                        FilterChip(category == v, { category = v }, label = { Text(tr(key)) })
                    }
                }
                FormField("amount", amount, { amount = it })
                if (category == "FUEL") {
                    Row {
                        listOf("91", "95").forEach { g ->
                            FilterChip(
                                grade == g,
                                {
                                    grade = g
                                    price = (saudiPrice(g) / 100.0).toString()
                                },
                                label = { Text(tr("grade_" + g)) },
                            )
                        }
                    }
                    Text(tr("expense_hint"))
                    FormField("liters", liters, { liters = it })
                    FormField("price", price, { price = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(full, { full = it })
                        Text(tr("full_tank"))
                    }
                }
                TextButton(
                    onClick = {
                        val d = Instant.ofEpochMilli(time).atZone(SaudiZone)
                        DatePickerDialog(
                                c,
                                { _, y, m, day ->
                                    TimePickerDialog(
                                            c,
                                            { _, h, min ->
                                                time =
                                                    LocalDateTime.of(y, m + 1, day, h, min)
                                                        .atZone(SaudiZone)
                                                        .toInstant()
                                                        .toEpochMilli()
                                            },
                                            d.hour,
                                            d.minute,
                                            true,
                                        )
                                        .show()
                                },
                                d.year,
                                d.monthValue - 1,
                                d.dayOfMonth,
                            )
                            .show()
                    }
                ) {
                    Text(tr("expense_time") + " · " + stamp(time))
                }
                Text(tr("outing_link"))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(outing == null, { outing = null }, label = { Text(tr("unlinked")) })
                    data.outings.forEach { o ->
                        FilterChip(
                            outing == o.id,
                            { outing = o.id },
                            label = { Text(stamp(o.startedAt)) },
                        )
                    }
                }
                if (invalid) Text(tr("invalid_expense"), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (vm.expense(category, amount, liters, price, full, time, outing)) onDismiss()
                    else invalid = true
                }
            ) {
                Text(tr("save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("cancel")) } },
    )
}
