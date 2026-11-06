package sa.rihla.feature.expenses

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import java.io.File
import java.time.*
import kotlinx.coroutines.*
import sa.rihla.core.*
import sa.rihla.data.*
import sa.rihla.ui.*

@Composable
fun ExpensesScreen(data: ScreenData, vm: JourneyViewModel, onAdd: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<Expense?>(null) }
    var viewingReceiptPath by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("ALL") }
    val warrantyAlerts =
        remember(data.expenses, data.now) {
            ReceiptStore.expiringWarranties(data.expenses, data.now)
        }
    val visible =
        remember(data.expenses, query, category) {
            data.expenses.filter {
                (category == "ALL" ||
                    it.category == category ||
                    (category == "SMS" && it.source == "SMS_NOTIFICATION") ||
                    (category == "RECEIPT" && !it.receiptImageUri.isNullOrBlank())) &&
                    (query.isBlank() ||
                        it.merchant.contains(query.trim(), true) ||
                        it.placeLabel.contains(query.trim(), true) ||
                        normalizeSms(query).trim() in (it.amountHalala / 100.0).toString())
            }
        }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Hero(
                tr("wallet_caption"),
                num(visible.sumOf { it.amountHalala } / 100.0, 2) + " " + tr("sar"),
                tr("visible_spending"),
                "add_expense",
                icon = Icons.Default.AccountBalanceWallet,
                onAction = onAdd,
            )
            if (warrantyAlerts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🧾 تنبيهات الضمان (${warrantyAlerts.size})", style = MaterialTheme.typography.titleSmall)
                        warrantyAlerts.take(4).forEach { alert ->
                            val title = alert.expense.merchant.ifBlank { "فاتورة محفوظة" }
                            Text(
                                if (alert.expired) "• انتهى ضمان $title"
                                else "• ينتهي ضمان $title خلال ${alert.daysRemaining} يوم",
                                style = MaterialTheme.typography.bodySmall,
                            )
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
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                            "ALL" to tr("all"),
                            "FUEL" to tr("fuel"),
                            "FOOD" to tr("food"),
                            "OTHER" to tr("other"),
                            "SMS" to tr("sms_imported"),
                            "RECEIPT" to "فواتير 🧾",
                        )
                        .forEach { (value, label) ->
                            FilterChip(
                                category == value,
                                { category = value },
                                label = { Text(label) },
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
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                        "FUEL" -> Icons.Default.LocalGasStation
                                        "FOOD" -> Icons.Default.Restaurant
                                        else -> Icons.Default.ReceiptLong
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
                                    } + if (!e.receiptImageUri.isNullOrBlank()) " 🧾" else "",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stamp(e.createdAt) +
                                        if (e.placeLabel.isNotBlank()) " · 📍 ${e.placeLabel}" else "",
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
                                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    "تفاصيل المصروف",
                                )
                            }
                        }
                        if (expanded) {
                            if (e.source == "RECURRING") Text("مصروف متكرر · " + e.merchant)
                            if (e.warrantyExpiryAt != null) {
                                Text(
                                    "تاريخ انتهاء الضمان: ${stamp(e.warrantyExpiryAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (!e.receiptImageUri.isNullOrBlank()) {
                                OutlinedButton(
                                    onClick = { viewingReceiptPath = e.receiptImageUri }
                                ) {
                                    Icon(Icons.Default.Image, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("عرض صورة الفاتورة المحفوظة")
                                }
                            }
                            if (e.source == "SMS_NOTIFICATION") {
                                Text(
                                    tr("sms_imported"),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                if (e.merchant.isNotBlank()) Text(e.merchant)
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    listOf(
                                            "FUEL" to "fuel",
                                            "FOOD" to "food",
                                            "OTHER" to "other",
                                        )
                                        .forEach { (cat, label) ->
                                            FilterChip(
                                                e.category == cat,
                                                { vm.categorizeImportedExpense(e.id, cat) },
                                                label = { Text(tr(label)) },
                                            )
                                        }
                                }
                            }
                            data.fuel
                                .find { it.expenseId == e.id }
                                ?.let { f ->
                                    Text(num(f.litersMl / 1000.0, 3) + " · " + tr("liters"))
                                    Text(num(f.priceHalala / 100.0, 2) + " " + tr("price"))
                                    if (f.full) Text(tr("full_tank"))
                                }
                            TextButton(onClick = { deleting = e }) { Text(tr("delete")) }
                        }
                    }
                }
            }
        }
    }

    if (deleting != null) {
        val target = deleting!!
        Confirm("delete_confirmation", { deleting = null }) {
            if (target.source == "SMS_NOTIFICATION") vm.deleteImportedExpense(target.id)
            else vm.deleteAnyExpense(target.id)
        }
    }

    viewingReceiptPath?.let { path ->
        val bmp =
            remember(path) {
                runCatching {
                    val f = File(path)
                    if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                }.getOrNull()
            }
        AlertDialog(
            onDismissRequest = { viewingReceiptPath = null },
            title = { Text("🧾 صورة الفاتورة") },
            text = {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "صورة الفاتورة",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    )
                } else {
                    Text("الملف غير موجود أو تعذرت قراءته.")
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingReceiptPath = null }) { Text("إغلاق") }
            },
        )
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
    val scope = rememberCoroutineScope()
    var category by rememberSaveable { mutableStateOf("FUEL") }
    var amount by rememberSaveable { mutableStateOf("") }
    var merchant by rememberSaveable { mutableStateOf("") }
    var liters by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable {
        mutableStateOf((data.settings.fuelPriceHalala / 100.0).toString())
    }
    var grade by rememberSaveable { mutableStateOf(data.settings.fuelGrade) }
    var full by rememberSaveable { mutableStateOf(false) }
    var odometer by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var outing by rememberSaveable { mutableStateOf(initialOuting) }
    var receiptPath by rememberSaveable { mutableStateOf<String?>(null) }
    var warrantyMonths by rememberSaveable { mutableIntStateOf(0) }
    var invalid by remember { mutableStateOf(false) }

    val galleryPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                scope.launch(Dispatchers.IO) {
                    val saved = ReceiptStore.saveFromUri(c, uri)
                    withContext(Dispatchers.Main) { receiptPath = saved }
                }
            }
        }
    val cameraCapture =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
            if (bmp != null) {
                scope.launch(Dispatchers.IO) {
                    val saved = ReceiptStore.saveBitmap(c, bmp)
                    withContext(Dispatchers.Main) { receiptPath = saved }
                }
            }
        }

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
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it.take(100) },
                    singleLine = true,
                    label = { Text("المتجر أو وصف المشتريات — اختياري") },
                    modifier = Modifier.fillMaxWidth(),
                )
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
                // v7.0: Receipt Photo Vault & Warranty
                Text("🧾 الفاتورة والضمان (محليًا دون سحابة)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { cameraCapture.launch(null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("تصوير")
                    }
                    OutlinedButton(
                        onClick = { galleryPicker.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Image, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("من المعرض")
                    }
                }
                if (receiptPath != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✓ أُرفقت فاتورة مضغوطة", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { receiptPath = null }) { Text("إزالة") }
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                            0 to "بدون ضمان",
                            3 to "ضمان ٣ أشهر",
                            12 to "ضمان سنة",
                            24 to "ضمان سنتان",
                        )
                        .forEach { (months, label) ->
                            FilterChip(
                                selected = warrantyMonths == months,
                                onClick = { warrantyMonths = months },
                                label = { Text(label) },
                            )
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
                    val expiry =
                        if (warrantyMonths > 0)
                            Instant.ofEpochMilli(time)
                                .atZone(SaudiZone)
                                .plusMonths(warrantyMonths.toLong())
                                .toInstant()
                                .toEpochMilli()
                        else null
                    if (
                        vm.expense(
                            category = category,
                            amount = amount,
                            liters = liters,
                            price = price,
                            full = full,
                            time = time,
                            outing = outing,
                            odometer = odometer,
                            merchant = merchant,
                            receiptImageUri = receiptPath,
                            warrantyExpiryAt = expiry,
                        )
                    )
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
