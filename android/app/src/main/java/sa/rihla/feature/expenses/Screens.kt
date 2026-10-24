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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.*
import com.google.android.gms.location.*
import java.time.*
import kotlinx.coroutines.*
import sa.rihla.R
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
    var page by rememberSaveable { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("ALL") }
    val visible =
        remember(data.expenses, query, category) {
            data.expenses.filter {
                (category == "ALL" ||
                    it.category == category ||
                    (category == "SMS" && it.source == "SMS_NOTIFICATION")) &&
                    (query.isBlank() ||
                        it.merchant.contains(query.trim(), true) ||
                        normalizeSms(query).trim() in (it.amountHalala / 100.0).toString())
            }
        }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SectionTitle(
                stringResource(R.string.atlas_wallet),
                stringResource(R.string.atlas_wallet_hint),
            )
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(tr("visible_spending"), style = MaterialTheme.typography.labelLarge)
                    Text(
                        num(visible.sumOf { it.amountHalala } / 100.0, 2) + " " + tr("sar"),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = onAdd) { Text(tr("add_expense")) }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit(
                                    "father_transfers"
                                )
                            }
                        ) {
                            Text(stringResource(R.string.atlas_wallet_transfers))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PageTabs(listOf("سجل المصروفات", "التحليل والميزانية"), page) { page = it }
            if (page == 0) {
                OutlinedTextField(
                    query,
                    { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("search_expenses")) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    leadingIcon = {
                        Icon(androidx.compose.material.icons.Icons.Default.Search, null)
                    },
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                            "ALL" to "all",
                            "FUEL" to "fuel",
                            "FOOD" to "food",
                            "OTHER" to "other",
                            "SMS" to "sms_imported",
                        )
                        .forEach { (value, label) ->
                            FilterChip(
                                category == value,
                                { category = value },
                                label = { Text(tr(label)) },
                            )
                        }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                SpendingChart(data.expenses, day(data.now))
                Spacer(Modifier.height(12.dp))
                sa.rihla.ui.WalletPro(vm, data)
                Metric(
                    "total_spending",
                    num(data.expenses.sumOf { it.amountHalala } / 100.0, 2) + " " + tr("sar"),
                )
                ExportDocument(vm, "export_csv", "rihla-expenses.csv", "text/csv") {
                    expensesCsv(visible)
                }
            }
        }
        if (page == 0) {

            if (visible.isEmpty()) item { EmptyPanel("no_results", "search_expense_hint") }
            items(visible, key = { it.id }) { e ->
                var expanded by rememberSaveable(e.id) { mutableStateOf(false) }
                Card(
                    Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Icon(
                                    when (e.category) {
                                        "FUEL" ->
                                            androidx.compose.material.icons.Icons.Default
                                                .LocalGasStation
                                        "FOOD" ->
                                            androidx.compose.material.icons.Icons.Default.Restaurant
                                        else ->
                                            androidx.compose.material.icons.Icons.Default
                                                .ReceiptLong
                                    },
                                    null,
                                    Modifier.padding(10.dp).size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    e.merchant.ifBlank {
                                        tr(
                                            when (e.category) {
                                                "FUEL" -> "fuel"
                                                "FOOD" -> "food"
                                                else -> "other"
                                            }
                                        )
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stamp(e.createdAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                num(e.amountHalala / 100.0, 2) + " " + tr("sar"),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            IconButton(onClick = { expanded = !expanded }) {
                                Icon(
                                    if (expanded)
                                        androidx.compose.material.icons.Icons.Default.ExpandLess
                                    else androidx.compose.material.icons.Icons.Default.ExpandMore,
                                    "تفاصيل المصروف",
                                )
                            }
                        }
                        if (expanded) {
                            if (e.source == "RECURRING") Text("مصروف متكرر · " + e.merchant)
                            if (e.source == "SMS_NOTIFICATION") {
                                Text(
                                    tr("sms_imported"),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                if (e.merchant.isNotBlank()) Text(e.merchant)
                                run {
                                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                                        listOf(
                                                "FUEL" to "fuel",
                                                "FOOD" to "food",
                                                "OTHER" to "other",
                                            )
                                            .forEach { (category, label) ->
                                                FilterChip(
                                                    e.category == category,
                                                    {
                                                        vm.categorizeImportedExpense(e.id, category)
                                                    },
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
    var category by rememberSaveable { mutableStateOf("FUEL") }
    var amount by rememberSaveable { mutableStateOf("") }
    var liters by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable {
        mutableStateOf((data.settings.fuelPriceHalala / 100.0).toString())
    }
    var grade by rememberSaveable { mutableStateOf(data.settings.fuelGrade) }
    var full by rememberSaveable { mutableStateOf(false) }
    var odometer by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var outing by rememberSaveable { mutableStateOf(initialOuting) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 24.dp),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
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
                    OutlinedTextField(
                        odometer,
                        { odometer = it },
                        singleLine = true,
                        label = { Text("قراءة عداد السيارة بالكيلومتر — اختياري") },
                        keyboardOptions =
                            androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                            ),
                    )
                    Text(
                        "للاستهلاك الفعلي: سجّل العداد وحدّد خزان كامل في تعبئتين، وسجّل أي تعبئة بينهما.",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                    if (vm.expense(category, amount, liters, price, full, time, outing, odometer))
                        onDismiss()
                    else invalid = true
                }
            ) {
                Text(tr("save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("cancel")) } },
    )
}
