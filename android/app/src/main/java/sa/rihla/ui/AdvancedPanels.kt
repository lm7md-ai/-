package sa.rihla.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import sa.rihla.core.*
import sa.rihla.data.*

private val categories = listOf("FUEL" to "fuel", "FOOD" to "food", "OTHER" to "other")

@Composable
fun RouteComparisonPanel(
    vm: JourneyViewModel,
    data: ScreenData,
    bounds: Pair<Long, Long>,
    onTrip: (Long) -> Unit,
) {
    var routes by remember { mutableStateOf<List<RouteComparison>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val selected =
        remember(data.outings, bounds) {
            data.outings
                .filter {
                    it.endedAt != null &&
                        it.startedAt >= bounds.first &&
                        it.startedAt < bounds.second
                }
                .take(100)
        }
    LaunchedEffect(selected) {
        loading = true
        routes =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val samples = selected.mapNotNull { outing ->
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    routeSample(
                        outing,
                        vm.repo.dao.points(outing.id).first(),
                        vm.repo.dao.events(outing.id).first().count { it.type == "STOP" },
                    )
                }
                compareRouteSamples(samples)
            }
        loading = false
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("مقارنة المسارات المسجلة", style = MaterialTheme.typography.titleLarge)
            Text(
                "نقارن آخر 100 مشوار منتهي بلا فجوات GPS حسب الاتجاه وشكل المسار. التجميع تقريبي وقد يجمع شوارع متقاربة. الوقت يشمل التوقفات؛ أفضل ساعة ملاحظة تحتاج 3 رحلات على الأقل وليست توقعًا للازدحام.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!loading && routes.isEmpty()) Text("لا توجد مسارات مكتملة كافية للمقارنة.")
            val repeated = routes.filter { it.trips >= 2 }
            if (!loading && routes.isNotEmpty() && repeated.isEmpty())
                Text("لم يتكرر مسار متشابه بعد. ستظهر المقارنة بعد تسجيل مشوارين على المسار نفسه.")
            repeated.forEachIndexed { index, value ->
                Text(
                    "مسار ${index+1} · ${value.trips} مشوار · " +
                        num(value.averageMeters / 1000) +
                        " كم · " +
                        duration(value.averageMs)
                )
                Text(
                    "الأسرع: ${duration(value.fastestMs)} · الأبطأ: ${duration(value.slowestMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "متوسط الوقوف: ${duration(value.averageStoppedMs)} · ${num(value.averageStops,1)} توقف",
                    style = MaterialTheme.typography.bodySmall,
                )
                value.bestHour?.let { hour ->
                    Text(
                        "أفضل ساعة مغادرة ملاحظة: ${hour.hour}:00 · ${hour.trips} رحلات · ${duration(hour.averageDrivingMs)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                    ?: Text(
                        "عينات ساعات المغادرة غير كافية للمقارنة بعد.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                TextButton(onClick = { onTrip(value.representative) }) {
                    Text("عرض مشوار من هذا المسار")
                }
            }
        }
    }
}

@Composable
fun AdvancedAnalytics(data: ScreenData, bounds: Pair<Long, Long>) {
    val trips =
        remember(data.outings, bounds) {
            data.outings.filter { it.startedAt >= bounds.first && it.startedAt < bounds.second }
        }
    Panel("comparison") {
        Text("تحليل مشاويرك", style = MaterialTheme.typography.titleLarge)
        val ended = trips.filter { it.endedAt != null }
        val elapsed = ended.sumOf { it.endedAt!! - it.startedAt }
        val gaps = ended.sumOf { it.state.gapMs }
        Text("الوقت غير الموثّق بالموقع: " + duration(gaps))
        if (elapsed > 0)
            Text(
                "نسبة الوقت الموثّق: " +
                    num((elapsed - gaps).coerceAtLeast(0) * 100.0 / elapsed, 1) +
                    "%"
            )
        val destinations =
            remember(trips, data.details, data.places) {
                destinationSummaries(trips, data.details, data.places)
            }
        Text("أكثر الوجهات زيارة", style = MaterialTheme.typography.titleMedium)
        if (destinations.isEmpty()) Text("تظهر الوجهات بعد إنهاء مشاوير لها نقاط GPS.")
        destinations.take(5).forEach { d ->
            Text(
                (if (d.name in listOf("HOME", "UNIVERSITY")) tr(d.name.lowercase())
                else if (temporaryPlaceInfo(d.name) != null) placeDisplayName(d.name)
                else d.name.removePrefix("PIN:")) + " · " + num(d.count.toDouble(), 0) + " مشوار"
            )
            Text(
                "متوسط القيادة: " +
                    duration(d.drivingMs / d.count) +
                    " · " +
                    num(d.meters / d.count / 1000) +
                    " كم",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("أوقات الانطلاق المسجلة", style = MaterialTheme.typography.titleMedium)
        departureHours(trips).forEach { h ->
            Text("${h.hour}:00 · ${h.trips} مشوار · متوسط " + duration(h.averageDrivingMs))
        }
        Text(
            "المقارنات من سجلك الشخصي وقد تشمل طرقًا مختلفة؛ لا تمثل ازدحامًا مباشرًا. سمِّ المشاوير لتمييز الوجهات.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun FuelLog(data: ScreenData) {
    Panel("fuel_economy") {
        Text("سجل تعبئات السيارة", style = MaterialTheme.typography.titleLarge)
        val economy = metrics(data, periodBounds(Period.ALL)).economy
        if (economy != null) {
            Text(num(economy.litersPer100Km, 2) + " لتر / 100 كم")
            Text(
                num(economy.sarPerKm, 3) + " ر.س / كم · " + num(economy.kmPerLiter, 2) + " كم / لتر"
            )
        } else Text("أضف تعبئتين كاملتين وقراءات العداد لحساب الاستهلاك الفعلي.")
        Text(
            "قراءات العداد تتقدم على مسافة GPS إذا توفرت عند طرفي الفترة. لا تستنتج تعبئة من رسالة البنك.",
            style = MaterialTheme.typography.bodySmall,
        )
        val prices = data.fuel.filter { it.litersMl > 0 }
        Text("إجمالي الوقود المسجّل: " + num(prices.sumOf { it.litersMl } / 1000.0, 1) + " لتر")
        val expenseById = data.expenses.associateBy { it.id }
        if (prices.isNotEmpty())
            Text(
                "متوسط سعر اللتر: " +
                    num(
                        prices.sumOf { expenseById[it.expenseId]?.amountHalala ?: 0 } * 10.0 /
                            prices.sumOf { it.litersMl },
                        2,
                    ) +
                    " ر.س"
            )
        data.fuel.asReversed().take(20).forEach { f ->
            HorizontalDivider()
            Text(
                stamp(f.createdAt) +
                    " · " +
                    num(f.litersMl / 1000.0, 2) +
                    " لتر" +
                    if (f.full) " · خزان كامل" else ""
            )
            Text(
                f.odometerMeters?.let { "العداد: " + num(it / 1000, 1) + " كم" }
                    ?: "المسافة من التتبع" + if (!f.reliable) " · فترة GPS غير مكتملة" else ""
            )
        }
    }
}

@Composable
fun WalletPro(vm: JourneyViewModel, data: ScreenData) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Panel("total_spending") {
        Text("ميزانيات الفئات", style = MaterialTheme.typography.titleLarge)
        val bounds = periodBounds(Period.MONTH)
        val limits =
            listOf(data.settings.fuelBudget, data.settings.foodBudget, data.settings.otherBudget)
        categories.forEachIndexed { index, (category, key) ->
            val spent =
                data.expenses
                    .filter {
                        it.category == category &&
                            it.createdAt >= bounds.first &&
                            it.createdAt < bounds.second
                    }
                    .sumOf { it.amountHalala }
            Text(
                tr(key) +
                    " · " +
                    num(spent / 100.0, 2) +
                    " ر.س" +
                    if (limits[index] > 0) " من " + num(limits[index] / 100.0, 2) else ""
            )
            if (limits[index] > 0) {
                LinearProgressIndicator(
                    progress = { budgetProgress(spent, limits[index]) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (spent > limits[index])
                    Text(
                        "تجاوزت الميزانية بـ " + num((spent - limits[index]) / 100.0, 2),
                        color = MaterialTheme.colorScheme.error,
                    )
            }
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                if (expanded) "إخفاء إدارة المحفظة"
                else "إدارة الميزانيات والقواعد والمصاريف المتكررة"
            )
        }
        if (expanded) {
            categories.forEachIndexed { i, (category, key) ->
                var amount by
                    rememberSaveable(category, limits[i]) {
                        mutableStateOf(if (limits[i] == 0L) "" else (limits[i] / 100.0).toString())
                    }
                OutlinedTextField(
                    amount,
                    { amount = it },
                    label = { Text("ميزانية " + tr(key) + " الشهرية") },
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        if (!vm.categoryBudget(category, amount)) vm.error.value = "invalid_expense"
                    }
                ) {
                    Text("حفظ الميزانية — فارغ لإيقافها")
                }
            }
            var pattern by rememberSaveable { mutableStateOf("") }
            var category by rememberSaveable { mutableStateOf("OTHER") }
            Text("قواعد تصنيف التاجر", style = MaterialTheme.typography.titleMedium)
            Text(
                "تُطبق على الرسائل الجديدة فقط. القاعدة الأكثر تحديدًا لها الأولوية. تصنيف الوقود لا ينشئ تعبئة أو لترات.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                pattern,
                { pattern = it },
                label = { Text("جزء من اسم التاجر") },
                singleLine = true,
            )
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                categories.forEach { (value, key) ->
                    FilterChip(category == value, { category = value }, label = { Text(tr(key)) })
                }
            }
            TextButton(
                onClick = {
                    if (vm.rule(pattern, category)) pattern = ""
                    else vm.error.value = "invalid_expense"
                }
            ) {
                Text("حفظ القاعدة")
            }
            data.rules.forEach { rule ->
                Row {
                    Text(rule.pattern + " ← " + tr(rule.category.lowercase()), Modifier.weight(1f))
                    TextButton(onClick = { vm.deleteRule(rule.id) }) { Text("حذف") }
                }
            }
            var title by rememberSaveable { mutableStateOf("") }
            var amount by rememberSaveable { mutableStateOf("") }
            var due by rememberSaveable { mutableStateOf("1") }
            Text("مصاريف شهرية متكررة", style = MaterialTheme.typography.titleMedium)
            Text(
                "تذكير داخل المحفظة؛ لا يُسجّل صرفًا حتى تؤكد السداد. الفئة المختارة أعلاه تُستخدم للجدول.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                title,
                { title = it },
                label = { Text("اسم المصروف") },
                singleLine = true,
            )
            OutlinedTextField(
                amount,
                { amount = it },
                label = { Text("المبلغ بالريال") },
                singleLine = true,
            )
            OutlinedTextField(
                due,
                { due = it },
                label = { Text("يوم الشهر 1–31") },
                singleLine = true,
            )
            TextButton(
                onClick = {
                    if (vm.recurring(title, amount, category, due)) {
                        title = ""
                        amount = ""
                    } else vm.error.value = "invalid_expense"
                }
            ) {
                Text("إضافة المصروف المتكرر")
            }
        }
        var confirm by remember { mutableStateOf<RecurringExpense?>(null) }
        val today = day(data.now)
        val month = YearMonth.from(today)
        data.recurring.forEach { value ->
            val date = recurringDate(value, month)
            val paid = data.expenses.any { it.importKey == recurringKey(value.id, month) }
            HorizontalDivider()
            Text(value.title + " · " + num(value.amountHalala / 100.0, 2) + " ر.س · " + date)
            if (paid) Text("سداد الشهر مسجّل")
            else
                TextButton(enabled = !today.isBefore(date), onClick = { confirm = value }) {
                    Text("تسجيل سداد هذا الشهر")
                }
            if (expanded)
                TextButton(onClick = { vm.deleteRecurring(value.id) }) {
                    Text("إلغاء الجدول — يحتفظ بالمصاريف السابقة")
                }
        }
        confirm?.let { value ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                properties =
                    androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                title = { Text("تأكيد السداد") },
                text = {
                    Text(
                        "هل دفعت " +
                            num(value.amountHalala / 100.0, 2) +
                            " ر.س لـ " +
                            value.title +
                            "؟"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.payRecurring(value.id)
                            confirm = null
                        }
                    ) {
                        Text("سجّل السداد")
                    }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("إلغاء") } },
            )
        }
    }
}
