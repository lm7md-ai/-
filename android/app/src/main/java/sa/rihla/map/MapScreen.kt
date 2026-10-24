package sa.rihla.map

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sa.rihla.R
import sa.rihla.core.*
import sa.rihla.data.*
import sa.rihla.tracking.LocationPicker
import sa.rihla.ui.*

@OptIn(ExperimentalMaterial3Api::class)
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
    val style = rememberRouteStyle()
    var pointSelection by remember { mutableStateOf<RoutePointSelection?>(null) }
    val sectionState = remember(selectedTrip) { SectionSelectionState() }
    var routeStats by remember { mutableStateOf<RouteDisplayStats?>(null) }
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
    var toolTab by rememberSaveable { mutableIntStateOf(0) }
    var follow by rememberSaveable { mutableStateOf(false) }
    val analysis = rememberRouteAnalysis(points, stops)
    LaunchedEffect(selectedTrip, measure) {
        pointSelection = null
        sectionState.reset()
    }
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
    LaunchedEffect(points) {
        pointSelection?.let { selected ->
            if (points.getOrNull(selected.index)?.recordedAt != selected.point.recordedAt) {
                pointSelection = null
                sectionState.reset()
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                RouteMap(
                    points,
                    events,
                    data.places,
                    Modifier.fillMaxSize(),
                    chosen,
                    stops = stops,
                    onStopPick = if (measure) null else { value -> stop = value },
                    camera = camera,
                    layers = layers,
                    measurement = marks,
                    display = style.value.copy(inspect = style.value.inspect && !measure),
                    onPointPick =
                        if (measure) null
                        else
                            { value ->
                                pointSelection = value
                                toolTab = 0
                            },
                    onRouteStats = { routeStats = it },
                    highlightedTime = pointSelection?.point?.recordedAt,
                    highlightedRange = sectionState.range(pointSelection),
                    pickStopsWithSelection = true,
                    centerSelection = false,
                    onPick = { lat, lon ->
                        chosen = lat to lon
                        if (measure) marks = marks + (lat to lon)
                    },
                )

                Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        placeholder = { Text(stringResource(R.string.atlas_map_search)) },
                    )
                    if (failed)
                        Text(
                            "تعذر فتح بيانات الخريطة. حاول مجددًا.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    if (results.isNotEmpty())
                        Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 4.dp) {
                            Column {
                                results.take(5).forEach { place ->
                                    TextButton(
                                        onClick = {
                                            chosen = place.latitude to place.longitude
                                            camera.navigate(place.latitude, place.longitude)
                                            query = ""
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(place.name)
                                    }
                                }
                            }
                        }
                }
                Column(
                    Modifier.align(Alignment.CenterEnd).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilledTonalIconButton(onClick = { camera.zoom(1) }) {
                        Icon(Icons.Default.Add, "تكبير +")
                    }
                    FilledTonalIconButton(onClick = { camera.zoom(-1) }) {
                        Icon(Icons.Default.Remove, "تصغير −")
                    }
                    FilledTonalIconButton(onClick = { camera.fit() }) {
                        Icon(Icons.Default.CenterFocusStrong, "احتواء المسار")
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                trip?.let {
                                    data.details
                                        .find { d -> d.outingId == it.id }
                                        ?.title
                                        ?.takeIf { t -> t.isNotBlank() } ?: stamp(it.startedAt)
                                } ?: stringResource(R.string.atlas_map_country),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                if (trip == null) stringResource(R.string.atlas_map_hint)
                                else
                                    num(trip.state.meters / 1000) +
                                        " " +
                                        tr("km") +
                                        " · " +
                                        duration((trip.endedAt ?: data.now) - trip.startedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { camera.country() }) {
                            Icon(Icons.Default.Public, stringResource(R.string.atlas_map_country))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                tools = true
                                toolTab = 0
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Layers, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.atlas_map_layers))
                        }
                        OutlinedButton(
                            onClick = {
                                tools = true
                                toolTab = 1
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.atlas_map_trip))
                        }
                    }
                }
            }
        }
    }
    if (tools || pointSelection != null)
        ModalBottomSheet(
            onDismissRequest = {
                tools = false
                pointSelection = null
            }
        ) {
            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.atlas_route_tools),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        IconButton(
                            onClick = {
                                tools = false
                                pointSelection = null
                            }
                        ) {
                            Icon(Icons.Default.Close, stringResource(R.string.atlas_close_options))
                        }
                    }
                    pointSelection?.let { value ->
                        RouteSectionInspector(analysis, value, trip, sectionState, camera) {
                            pointSelection = null
                        }
                    }
                    PageTabs(
                        listOf(
                            stringResource(R.string.atlas_map_layers),
                            stringResource(R.string.atlas_map_trip),
                        ),
                        toolTab,
                    ) {
                        toolTab = it
                    }
                    if (pointSelection != null || toolTab == 0) {
                        RouteStyleControls(style.value, routeStats) {
                            style.set(it)
                            pointSelection = null
                            sectionState.reset()
                        }
                        RouteSectionHighlights(analysis, sectionState, camera)

                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            FilterChip(
                                follow,
                                { follow = !follow },
                                label = { Text("متابعة آخر نقطة") },
                            )
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
                                style.value.color == RouteColorMode.SPEED,
                                {
                                    style.set(
                                        style.value.copy(
                                            color =
                                                if (style.value.color == RouteColorMode.SPEED)
                                                    RouteColorMode.PLAIN
                                                else RouteColorMode.SPEED
                                        )
                                    )
                                },
                                label = { Text("تلوين السرعة") },
                            )
                            FilterChip(
                                layers.accuracy,
                                { layers = layers.copy(accuracy = !layers.accuracy) },
                                label = { Text("دائرة الدقة") },
                            )
                        }
                        Row {
                            FilterChip(
                                measure,
                                { measure = !measure },
                                label = { Text("قياس المسافات") },
                            )
                            if (marks.isNotEmpty())
                                TextButton(onClick = { marks = marks.dropLast(1) }) {
                                    Text("تراجع")
                                }
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
                    }
                    if (toolTab == 1) {
                        TextButton(
                            onClick = {
                                sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit(
                                    "temporary_places"
                                )
                            }
                        ) {
                            Text("منزل أو استراحة مؤقتة وإحصائية الجامعة")
                        }
                        TextButton(
                            onClick = {
                                sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit(
                                    "repeated_routes"
                                )
                            }
                        ) {
                            Text("مقارنة المسارات المتكررة")
                        }
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
                                                ?.takeIf { it.isNotBlank() }
                                                ?: stamp(outing.startedAt)
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
                                    Text(sa.rihla.core.placeDisplayName(place.type))
                                }
                                if (place.type.startsWith("PIN:"))
                                    TextButton(onClick = { vm.deletePin(place.type) }) {
                                        Text("حذف")
                                    }
                            }
                        }
                        Text(
                            "يمكن استيراد حزمة MBTiles إضافية من المزيد. بيانات السعودية مضمنة ولا تحتاج تنزيلًا عند أول تشغيل. حقوق البيانات: © OpenStreetMap contributors · Geofabrik · ODbL 1.0.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
