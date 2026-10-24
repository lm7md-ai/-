package sa.rihla.map

import android.content.Intent
import androidx.activity.compose.BackHandler
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
    var selectedStop by remember(selectedTrip) { mutableStateOf<Long?>(null) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var toolTab by rememberSaveable { mutableIntStateOf(0) }
    var follow by rememberSaveable { mutableStateOf(false) }
    var stopList by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }
    LaunchedEffect(selectedTrip) {
        follow = false
        stopList = false
    }
    val analysis = rememberRouteAnalysis(points, stops)
    val legs =
        remember(analysis, trip) {
            if (analysis != null && trip != null) StopRouteSections(analysis, trip) else null
        }
    LaunchedEffect(selectedTrip, measure) {
        pointSelection = null
        sectionState.reset()
    }
    LaunchedEffect(selectedTrip, points.firstOrNull()?.outingId) {
        if (points.isNotEmpty() && points.first().outingId == selectedTrip) camera.fit()
    }
    LaunchedEffect(follow, points.lastOrNull()?.recordedAt) {
        if (follow) points.lastOrNull()?.let { camera.center(it.latitude, it.longitude) }
    }
    LaunchedEffect(query) {
        delay(300)
        failed = false
        val result =
            if (query.trim().length >= 2)
                withContext(Dispatchers.IO) { runCatching { SaudiMap.search(context, query) } }
            else Result.success(emptyList())
        failed = result.isFailure
        results = result.getOrDefault(emptyList())
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
                        onStopPick = if (measure) null else { value -> selectedStop = value.id },
                        camera = camera,
                        layers = layers,
                        measurement = marks,
                        display = style.value.copy(inspect = style.value.inspect && !measure),
                        onPointPick =
                            if (measure) null
                            else
                                { value ->
                                    sectionState.stopRange = null
                                    pointSelection = value
                                    toolTab = 0
                                },
                        onRouteStats = { routeStats = it },
                        highlightedTime = pointSelection?.point?.recordedAt,
                        highlightedRange = sectionState.range(pointSelection, legs),
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
                        FilledTonalIconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                if (expanded) Icons.Default.FullscreenExit
                                else Icons.Default.Fullscreen,
                                stringResource(
                                    if (expanded) R.string.map_exit_expanded
                                    else R.string.map_expanded
                                ),
                            )
                        }
                        FilledTonalIconButton(onClick = { camera.zoom(1) }) {
                            Icon(Icons.Default.Add, "تكبير +")
                        }
                        FilledTonalIconButton(onClick = { camera.zoom(-1) }) {
                            Icon(Icons.Default.Remove, "تصغير −")
                        }
                        FilledTonalIconButton(
                            onClick = {
                                follow = false
                                camera.fit()
                            }
                        ) {
                            Icon(Icons.Default.CenterFocusStrong, "احتواء المسار")
                        }
                    }
                }
                if (!expanded && !measure)
                    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        trip?.let {
                                            data.details
                                                .find { d -> d.outingId == it.id }
                                                ?.title
                                                ?.takeIf { t -> t.isNotBlank() }
                                                ?: stamp(it.startedAt)
                                        } ?: stringResource(R.string.atlas_map_country),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow =
                                            androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (trip == null) stringResource(R.string.atlas_map_hint)
                                        else
                                            num(trip.state.meters / 1000) +
                                                " " +
                                                tr("km") +
                                                " · " +
                                                duration(
                                                    (trip.endedAt ?: data.now) - trip.startedAt
                                                ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { camera.country() }) {
                                    Icon(
                                        Icons.Default.Public,
                                        stringResource(R.string.atlas_map_country),
                                    )
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
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AssistChip(
                                    onClick = {
                                        measure = !measure
                                        follow = false
                                    },
                                    label = { Text(stringResource(R.string.map_quick_measure)) },
                                )
                                if (data.active != null && selectedTrip != data.active?.id)
                                    AssistChip(
                                        onClick = {
                                            selectedTrip = data.active?.id
                                            camera.fit()
                                        },
                                        label = { Text(stringResource(R.string.map_open_active)) },
                                    )
                                FilterChip(
                                    selected = follow,
                                    enabled = points.isNotEmpty(),
                                    onClick = { follow = !follow },
                                    label = { Text(stringResource(R.string.map_quick_follow)) },
                                )
                                AssistChip(
                                    enabled = stops.isNotEmpty(),
                                    onClick = { stopList = true },
                                    label = {
                                        Text(stringResource(R.string.map_quick_stops, stops.size))
                                    },
                                )
                                AssistChip(
                                    enabled = !locating,
                                    onClick = {
                                        scope.launch {
                                            locating = true
                                            try {
                                                val fix = LocationPicker.current(context)
                                                if (fix == null) vm.error.value = "location_failed"
                                                else {
                                                    follow = false
                                                    chosen = fix.lat to fix.lon
                                                    camera.navigate(fix.lat, fix.lon)
                                                }
                                            } finally {
                                                locating = false
                                            }
                                        }
                                    },
                                    label = {
                                        Text(
                                            stringResource(
                                                if (locating) R.string.map_stop_locating
                                                else R.string.map_quick_location
                                            )
                                        )
                                    },
                                )
                            }
                            if (trip != null)
                                Text(
                                    stringResource(
                                        if (trip.endedAt == null) R.string.map_quick_active
                                        else R.string.map_quick_history
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            points.lastOrNull()?.let { point ->
                                Text(
                                    stringResource(
                                        R.string.map_latest_fix,
                                        stamp(point.recordedAt, true),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (trip != null)
                                Text(
                                    stringResource(R.string.map_leg_tap_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                        }
                    }
            }
        }
        if (measure)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Surface(
                    Modifier.fillMaxWidth().padding(12.dp),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(
                                R.string.map_measure_total,
                                num(
                                    marks.zipWithNext().sumOf { (a, b) ->
                                        distance(a.first, a.second, b.first, b.second)
                                    } / 1000,
                                    3,
                                ),
                            )
                        )
                        Text(
                            stringResource(R.string.map_measure_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            TextButton(
                                enabled = marks.isNotEmpty(),
                                onClick = { marks = marks.dropLast(1) },
                            ) {
                                Text(stringResource(R.string.map_measure_undo))
                            }
                            TextButton(
                                enabled = marks.isNotEmpty(),
                                onClick = { marks = emptyList() },
                            ) {
                                Text(stringResource(R.string.map_measure_clear))
                            }
                            TextButton(onClick = { measure = false }) {
                                Text(stringResource(R.string.map_measure_done))
                            }
                        }
                    }
                }
            }
    }
    if (stopList)
        AlertDialog(
            onDismissRequest = { stopList = false },
            title = { Text(stringResource(R.string.map_quick_stops, stops.size)) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(stops.size) { index ->
                        val value = stops[index]
                        TextButton(
                            onClick = {
                                stopList = false
                                follow = false
                                camera.navigate(value.latitude, value.longitude)
                                selectedStop = value.id
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.map_leg_stop, index + 1))
                                Text(
                                    stamp(value.startedAt, true),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    duration(value.durationMs(data.now)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { stopList = false }) {
                    Text(stringResource(R.string.map_leg_close))
                }
            },
        )
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
                                {
                                    measure = !measure
                                    tools = false
                                    pointSelection = null
                                    follow = false
                                },
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
    stops
        .firstOrNull { it.id == selectedStop }
        ?.let { value ->
            if (trip != null)
                StopRouteDialog(
                    value,
                    stops,
                    analysis,
                    trip,
                    data.now,
                    onHighlight = { range ->
                        sectionState.reset()
                        sectionState.stopRange = range
                        pointSelection = null
                        tools = false
                        camera.section(range)
                        selectedStop = null
                    },
                    onDismiss = { selectedStop = null },
                )
        }
}
