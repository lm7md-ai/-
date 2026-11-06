package sa.rihla.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import sa.rihla.core.*
import sa.rihla.data.*
import sa.rihla.feature.settings.PlaceDialog
import sa.rihla.map.RouteAnalysis

@Composable
fun TemporaryPlacesScreen(vm: JourneyViewModel, data: ScreenData, onTrip: (Long) -> Unit) {
    var name by remember { mutableStateOf("") }
    var home by remember { mutableStateOf(true) }
    var hours by remember { mutableStateOf("24") }
    var dialog by remember { mutableStateOf<String?>(null) }
    var selectedUquDay by remember { mutableIntStateOf(1) }
    var courseTitle by remember { mutableStateOf("") }
    var buildingGate by remember { mutableStateOf("") }
    var startHourMin by remember { mutableStateOf("08:00") }
    var endHourMin by remember { mutableStateOf("09:50") }
    var summaries by remember {
        mutableStateOf<List<Triple<Outing, TemporaryCommute, sa.rihla.map.RouteSection>>>(
            emptyList()
        )
    }
    var loading by remember { mutableStateOf(false) }
    val temporary = data.places.filter { temporaryPlaceInfo(it.type) != null }
    val placeTime by
        produceState(System.currentTimeMillis(), temporary) {
            while (isActive) {
                value = System.currentTimeMillis()
                val next =
                    temporary
                        .mapNotNull { temporaryPlaceInfo(it.type)?.expires }
                        .filter { it > value }
                        .minOrNull()
                delay(((next ?: (value + 60000)) - value).coerceIn(1, 60000))
            }
        }
    LaunchedEffect(
        data.outings.take(100).map {
            listOf(
                it.id,
                it.endedAt,
                it.state.visits,
                it.state.visitConfirmed,
                it.state.universityArrival,
                it.state.meters.toLong() / 1000,
            )
        },
        temporary,
    ) {
        loading = true
        summaries =
            withContext(Dispatchers.IO) {
                data.outings.take(100).flatMap { trip ->
                    currentCoroutineContext().ensureActive()
                    val points = vm.repo.dao.points(trip.id).first()
                    val events = vm.repo.dao.events(trip.id).first()
                    val active = currentCoroutineContext()
                    val legs =
                        temporaryCommutes(trip, points, events, temporary) { active.ensureActive() }
                    if (legs.isEmpty()) emptyList()
                    else {
                        val analysis =
                            RouteAnalysis(points, stopDetails(trip, events)) {
                                active.ensureActive()
                            }
                        legs.mapNotNull { leg ->
                            analysis.summary(leg.from, leg.to)?.let { Triple(trip, leg, it) }
                        }
                    }
                }
            }
        loading = false
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(androidx.compose.ui.Modifier.fillMaxWidth()) {
                Column(
                    androidx.compose.ui.Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("🎓 جدول محاضرات جامعة أم القرى", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "يحسب مشواري وقت الانطلاق المثالي قبل كل محاضرة بناءً على متوسط زمن مشاويرك الفعلي إلى العابدية + 10 دقائق هامش أمان.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { dayNum ->
                            FilterChip(
                                selected = selectedUquDay == dayNum,
                                onClick = { selectedUquDay = dayNum },
                                label = { Text(uquDayArabicName(dayNum)) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = courseTitle,
                        onValueChange = { courseTitle = it.take(100) },
                        label = { Text("اسم المقرر / المحاضرة") },
                        singleLine = true,
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = buildingGate,
                        onValueChange = { buildingGate = it.take(80) },
                        label = { Text("الكلية أو البوابة (مثال: الحاسب · بوابة ٣)") },
                        singleLine = true,
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startHourMin,
                            onValueChange = { startHourMin = it.take(5) },
                            label = { Text("البداية HH:MM") },
                            singleLine = true,
                            modifier = androidx.compose.ui.Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = endHourMin,
                            onValueChange = { endHourMin = it.take(5) },
                            label = { Text("النهاية HH:MM") },
                            singleLine = true,
                            modifier = androidx.compose.ui.Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = {
                            val st = parseHhMm(startHourMin)
                            val en = parseHhMm(endHourMin)
                            if (st != null && en != null && vm.addScheduleEntry(selectedUquDay, courseTitle, buildingGate, st, en)) {
                                courseTitle = ""
                                buildingGate = ""
                            } else {
                                vm.error.value = "operation_failed"
                            }
                        },
                        enabled = courseTitle.trim().isNotBlank(),
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    ) {
                        Text("إضافة المحاضرة للجدول")
                    }
                }
            }
        }
        data.schedule.forEach { entry ->
            item(key = "uqu_sched_${entry.id}") {
                OutlinedCard(androidx.compose.ui.Modifier.fillMaxWidth()) {
                    Row(
                        androidx.compose.ui.Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(androidx.compose.ui.Modifier.weight(1f)) {
                            Text(
                                "${uquDayArabicName(entry.dayOfWeek)} · ${entry.courseTitle}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${formatMinuteOfDayArabic(entry.startMinuteOfDay)} — ${formatMinuteOfDayArabic(entry.endMinuteOfDay)}" +
                                    if (entry.buildingOrGate.isNotBlank()) " · ${entry.buildingOrGate}" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = entry.enabled,
                            onCheckedChange = { vm.toggleScheduleEntry(entry.id, it) },
                        )
                        TextButton(onClick = { vm.deleteScheduleEntry(entry.id) }) {
                            Text("حذف")
                        }
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Text("المنزل والاستراحة المؤقتة", style = MaterialTheme.typography.titleLarge)
            Text(
                "محطة داخل المشوار؛ لا تنهي الرحلة ولا تستبدل المنزل الدائم. إحصائية الذهاب منها إلى الجامعة تظهر بصورة مستقلة دون تعديل الرحلة.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                name,
                { if (it.length <= 60) name = it },
                label = { Text("اسم المكان") },
                singleLine = true,
            )
            Row {
                FilterChip(home, { home = true }, label = { Text("منزل مؤقت") })
                FilterChip(!home, { home = false }, label = { Text("استراحة مؤقتة") })
            }
            OutlinedTextField(
                hours,
                { hours = it },
                label = { Text("مدة التفعيل بالساعات: 1–720") },
                singleLine = true,
            )
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    dialog = temporaryPlaceType(home, now, now + hours.toLong() * 3600000, name)
                },
                enabled = name.isNotBlank() && (hours.toLongOrNull() ?: 0L) in 1L..720L,
            ) {
                Text("تحديد المكان على الخريطة")
            }
        }
        temporary.forEach { place ->
            item(key = place.type) {
                val info = temporaryPlaceInfo(place.type)!!
                Card {
                    Column(androidx.compose.ui.Modifier.padding(16.dp)) {
                        Text(
                            placeDisplayName(place.type),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (placeTime < info.expires) "مفعّل حتى ${stamp(info.expires)}"
                            else "انتهت صلاحيته؛ تبقى إحصاءاته التاريخية"
                        )
                        TextButton(onClick = { vm.deleteTemporaryPlace(place.type) }) {
                            Text("حذف تعريف المكان")
                        }
                    }
                }
            }
        }
        item {
            Text("من المكان المؤقت إلى الجامعة", style = MaterialTheme.typography.titleLarge)
            Text(
                "آخر 100 مشوار. يلزم توقف مؤكد أو بقاء مسجل لدقيقتين، ثم وصول جامعة مؤكد. البداية آخر نقطة داخل المكان؛ قد يختلف وقت المغادرة الفعلي بين عينتين.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (loading) LinearProgressIndicator()
            else if (summaries.isEmpty())
                Text("لا توجد رحلات مؤهلة بعد. تأكد من ضبط نطاق الجامعة وتسجيل المغادرة والوصول.")
        }
        summaries.forEach { (trip, leg, section) ->
            item(key = "${trip.id}-${leg.arrival}") {
                Card {
                    Column(
                        androidx.compose.ui.Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "${placeDisplayName(leg.place)} ← الجامعة",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("${stamp(leg.departure,true)} — ${stamp(leg.arrival,true)}")
                        Text(
                            "المدة: ${duration((leg.arrival-leg.departure).coerceAtLeast(0))} · المسافة المحتسبة: ${num(section.recordedMeters/1000,2)} كم"
                        )
                        Text(
                            "مسافة GPS الموصولة: ${num(section.gpsMeters/1000,2)} كم · توقف: ${duration(section.stoppedMs)}"
                        )
                        if (section.gaps > 0)
                            Text(
                                "يتضمن انقطاعًا: ${duration(section.gapMs)}؛ المسافة المسجلة لا تغطيه"
                            )
                        TextButton(onClick = { onTrip(trip.id) }) {
                            Text("فتح المشوار الأصلي كاملًا")
                        }
                    }
                }
            }
        }
    }
    dialog?.let { type -> PlaceDialog(vm, data, type) { dialog = null } }
}

private fun parseHhMm(raw: String): Int? {
    val parts = raw.trim().split(':')
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}
