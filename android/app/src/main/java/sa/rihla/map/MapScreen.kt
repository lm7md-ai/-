package sa.rihla.map

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sa.rihla.core.*
import sa.rihla.data.*
import sa.rihla.tracking.LocationPicker
import sa.rihla.ui.*

@Composable
fun MapScreen(vm: JourneyViewModel, data: ScreenData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTrip by rememberSaveable {
        mutableStateOf(data.active?.id ?: data.outings.firstOrNull()?.id)
    }
    val trip = data.outings.find { it.id == selectedTrip }
    val points by
        remember(selectedTrip) {
                selectedTrip?.let { vm.repo.dao.points(it) }
                    ?: kotlinx.coroutines.flow.flowOf(emptyList())
            }
            .collectAsState(emptyList())
    val events by
        remember(selectedTrip) {
                selectedTrip?.let { vm.repo.dao.events(it) }
                    ?: kotlinx.coroutines.flow.flowOf(emptyList())
            }
            .collectAsState(emptyList())
    val stops = remember(trip, events) { trip?.let { stopDetails(it, events) }.orEmpty() }
    val camera = remember { MapCamera() }
    var layers by remember { mutableStateOf(MapLayers()) }
    var measure by rememberSaveable { mutableStateOf(false) }
    var marks by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var chosen by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MapPlace>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var stop by remember { mutableStateOf<StopDetails?>(null) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(follow, points.lastOrNull()?.recordedAt) {
        if (follow) points.lastOrNull()?.let { camera.center(it.latitude, it.longitude) }
    }
    LaunchedEffect(query) {
        delay(300)
        failed = false
        results =
            if (query.trim().length >= 2)
                withContext(Dispatchers.IO) {
                    runCatching { SaudiMap.search(context, query) }
                        .getOrElse {
                            failed = true
                            emptyList()
                        }
                }
            else emptyList()
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("خريطة السعودية دون إنترنت", style = MaterialTheme.typography.titleLarge)
            Text(
                "طرق ومدن محلية من OpenStreetMap؛ التفاصيل تزيد مع التكبير. لا تتضمن توجيهًا صوتيًا أو ازدحامًا مباشرًا.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                query,
                { query = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ابحث عن مدينة أو حي بالعربية") },
            )
            if (failed) Text("تعذر فتح بيانات الخريطة. حاول مجددًا.")
            results.take(8).forEach { place ->
                TextButton(
                    onClick = {
                        chosen = place.latitude to place.longitude
                        camera.navigate(place.latitude, place.longitude)
                        query = ""
                    }
                ) {
                    Text(place.name)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { camera.country() }) { Text("السعودية كاملة") }
                TextButton(onClick = { camera.zoom(1) }) { Text("تكبير +") }
                TextButton(onClick = { camera.zoom(-1) }) { Text("تصغير −") }
                TextButton(onClick = { camera.fit() }) { Text("احتواء المسار") }
            }
            RouteMap(
                points,
                events,
                data.places,
                Modifier.fillMaxWidth().height(430.dp),
                chosen,
                stops = stops,
                onStopPick = if (measure) null else { value -> stop = value },
                camera = camera,
                layers = layers,
                measurement = marks,
                pickStopsWithSelection = true,
                centerSelection = false,
                onPick = { lat, lon ->
                    chosen = lat to lon
                    if (measure) marks = marks + (lat to lon)
                },
            )
            Text(
                "المسار: أزرق أقل من 30 · أخضر 30–80 · برتقالي أعلى من 80 كم/س. الألوان تعرض السرعة المسجلة ولا تحدد المخالفة.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(follow, { follow = !follow }, label = { Text("متابعة آخر نقطة") })
                FilterChip(
                    layers.route,
                    { layers = layers.copy(route = !layers.route) },
                    label = { Text("المسار") },
                )
                FilterChip(
                    layers.stops,
                    { layers = layers.copy(stops = !layers.stops) },
                    label = { Text("التوقفات") },
                )
                FilterChip(
                    layers.places,
                    { layers = layers.copy(places = !layers.places) },
                    label = { Text("الأماكن") },
                )
                FilterChip(
                    layers.speed,
                    { layers = layers.copy(speed = !layers.speed) },
                    label = { Text("تلوين السرعة") },
                )
                FilterChip(
                    layers.accuracy,
                    { layers = layers.copy(accuracy = !layers.accuracy) },
                    label = { Text("دائرة الدقة") },
                )
            }
            Row {
                FilterChip(measure, { measure = !measure }, label = { Text("قياس المسافات") })
                if (marks.isNotEmpty())
                    TextButton(onClick = { marks = marks.dropLast(1) }) { Text("تراجع") }
                if (marks.isNotEmpty())
                    TextButton(onClick = { marks = emptyList() }) { Text("مسح القياس") }
            }
            if (measure)
                Text(
                    "انقر نقاطًا على الخريطة؛ القياس خط مستقيم بين النقاط وليس مسافة طريق: " +
                        num(
                            marks.zipWithNext().sumOf { (a, b) ->
                                distance(a.first, a.second, b.first, b.second)
                            } / 1000,
                            3,
                        ) +
                        " كم"
                )
            TextButton(onClick = { tools = !tools }) {
                Text(if (tools) "إخفاء أدوات الخريطة" else "اختيار مشوار والأماكن والمشاركة")
            }
            if (tools) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selectedTrip == null,
                        { selectedTrip = null },
                        label = { Text("بدون مسار") },
                    )
                    data.outings.take(30).forEach { outing ->
                        FilterChip(
                            selectedTrip == outing.id,
                            { selectedTrip = outing.id },
                            label = {
                                Text(
                                    data.details
                                        .find { it.outingId == outing.id }
                                        ?.title
                                        ?.takeIf { it.isNotBlank() } ?: stamp(outing.startedAt)
                                )
                            },
                        )
                    }
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            val fix = LocationPicker.current(context)
                            if (fix == null) vm.error.value = "location_failed"
                            else {
                                chosen = fix.lat to fix.lon
                                camera.navigate(fix.lat, fix.lon)
                            }
                        }
                    }
                ) {
                    Text("موقعي الحالي")
                }
                chosen?.let { (lat, lon) ->
                    Text(
                        "${"%.5f".format(java.util.Locale.US,lat)}, ${"%.5f".format(java.util.Locale.US,lon)}"
                    )
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("اسم المكان المحفوظ") },
                        singleLine = true,
                    )
                    TextButton(
                        enabled = name.trim().length in 1..60,
                        onClick = {
                            vm.place(SavedPlace("PIN:" + name.trim(), lat, lon, 100.0))
                            name = ""
                        },
                    ) {
                        Text("حفظ المكان")
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(
                                            Intent.EXTRA_TEXT,
                                            "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=16/$lat/$lon",
                                        ),
                                    "مشاركة النقطة المختارة",
                                )
                            )
                        }
                    ) {
                        Text("مشاركة هذه النقطة فقط")
                    }
                }
                data.places.forEach { place ->
                    Row {
                        TextButton(
                            onClick = {
                                chosen = place.latitude to place.longitude
                                camera.navigate(place.latitude, place.longitude)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (place.type.startsWith("PIN:")) place.type.removePrefix("PIN:")
                                else tr(place.type.lowercase())
                            )
                        }
                        if (place.type.startsWith("PIN:"))
                            TextButton(onClick = { vm.deletePin(place.type) }) { Text("حذف") }
                    }
                }
                Text(
                    "يمكن استيراد حزمة MBTiles إضافية من المزيد. بيانات السعودية مضمنة ولا تحتاج تنزيلًا عند أول تشغيل. حقوق البيانات: © OpenStreetMap contributors · Geofabrik · ODbL 1.0.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    stop?.let { value ->
        AlertDialog(
            onDismissRequest = { stop = null },
            properties =
                androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            title = { Text("تفاصيل التوقف") },
            text = {
                Column {
                    Text("البداية: " + stamp(value.startedAt))
                    Text("المدة: " + duration(value.durationMs(data.now)))
                    Text(value.endedAt?.let { "النهاية: " + stamp(it) } ?: "التوقف جارٍ")
                    Text("الموقع: ${value.latitude}, ${value.longitude}")
                }
            },
            confirmButton = { TextButton(onClick = { stop = null }) { Text("إغلاق") } },
        )
    }
}
