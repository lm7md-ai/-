package sa.rihla.ui

import android.content.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.*
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
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

val Arabic = Locale.forLanguageTag("ar-SA")

@Composable
fun tr(key: String): String {
    return androidx.compose.ui.res.stringResource(textResource(key))
}

fun num(v: Double, digits: Int = 1) =
    NumberFormat.getNumberInstance(Arabic).apply { maximumFractionDigits = digits }.format(v)

fun day(t: Long) = Instant.ofEpochMilli(t).atZone(SaudiZone).toLocalDate()

fun stamp(t: Long, seconds: Boolean = false) =
    DateTimeFormatter.ofPattern(
            if (seconds) "d MMM yyyy · HH:mm:ss" else "d MMM yyyy · HH:mm",
            Arabic,
        )
        .format(Instant.ofEpochMilli(t).atZone(SaudiZone))

@Composable
fun duration(ms: Long): String {
    val p = durationParts(ms)
    val hours = tr("hours")
    val minutes = tr("minutes")
    val seconds = tr("seconds")
    return buildList {
            if (p.hours > 0) add(num(p.hours.toDouble(), 0) + " " + hours)
            if (p.hours > 0 || p.minutes > 0) add(num(p.minutes.toDouble(), 0) + " " + minutes)
            add(num(p.seconds.toDouble(), 0) + " " + seconds)
        }
        .joinToString(" · ")
}

@Composable
fun Heading(key: String) {
    Text(
        tr(key),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun Metric(key: String, value: String) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                tr(key),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
fun Action(key: String, enabled: Boolean = true, click: () -> Unit) {
    Button(
        onClick = click,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 52.dp),
    ) {
        Text(tr(key))
    }
}

@Composable
fun Confirm(key: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("confirm")) },
        text = { Text(tr(key)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            ) {
                Text(tr("confirm"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("cancel")) } },
    )
}

@Composable
fun FormField(key: String, value: String, onChange: (String) -> Unit, decimal: Boolean = true) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(tr(key)) },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text
            ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun RihlaRoot(vm: JourneyViewModel = viewModel(), audioRequest: Int = 0) {
    val data by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clearing by vm.clearing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val noticeMessage = notice?.let { tr(it) }
    LaunchedEffect(notice, noticeMessage) {
        notice?.let { key ->
            snackbar.showSnackbar(noticeMessage ?: return@LaunchedEffect)
            if (vm.notice.value == key) vm.notice.value = null
        }
    }
    val audioPlayback by sa.rihla.audio.AudioLive.state.collectAsStateWithLifecycle()
    if (!data.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    LaunchedEffect(data.active?.id) {
        if (data.active != null && TrackingService.fineAllowed(context))
            TrackingService.launch(context)
    }
    LaunchedEffect(data.settings.auto, data.places.find { it.type == "HOME" }) {
        Detection.register(context, data.settings.auto) { vm.error.value = "detection_failed" }
    }
    val dark =
        when (data.settings.theme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> isSystemInDarkTheme()
        }
    LaunchedEffect(dark, context) {
        (context as? android.app.Activity)?.let { activity ->
            androidx.core.view.WindowCompat.getInsetsController(
                    activity.window,
                    activity.window.decorView,
                )
                .apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
        }
    }
    val colors = journeyColors(dark)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = colors, typography = NewTypography, shapes = NewShapes) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val pageStates = rememberSaveableStateHolder()
                var page by rememberSaveable { mutableStateOf("dashboard") }
                var selected by rememberSaveable { mutableStateOf<Long?>(null) }
                var previousPages by rememberSaveable { mutableStateOf(listOf<String>()) }
                fun openPage(next: String) {
                    if (next != page) {
                        previousPages = (previousPages + page).takeLast(15)
                        page = next
                    }
                    selected = null
                }
                fun goBack() {
                    if (selected != null) selected = null
                    else {
                        page = previousPages.lastOrNull() ?: "dashboard"
                        previousPages = previousPages.dropLast(1)
                    }
                }
                LaunchedEffect(Unit) {
                    sa.rihla.audio.studio.AssistantNavigation.requests.collect { openPage(it) }
                }
                LaunchedEffect(audioRequest) {
                    if (audioRequest > 0)
                        openPage(if (audioPlayback.id.isBlank()) "audio" else "player")
                }
                LaunchedEffect(selected, data.outings) {
                    if (selected != null && data.outings.none { it.id == selected }) selected = null
                }
                BackHandler(enabled = selected != null || page != "dashboard") {
                    goBack()
                }
                var expense by rememberSaveable { mutableStateOf(false) }
                var expenseOuting by rememberSaveable { mutableStateOf<Long?>(null) }
                if (!data.settings.onboarded)
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbar) },
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    ) { padding ->
                        Box(Modifier.padding(padding)) { Onboarding(vm, data) }
                    }
                else
                    BoxWithConstraints {
                        val wide = maxWidth >= 840.dp
                        Scaffold(
                            snackbarHost = { SnackbarHost(snackbar) },
                            topBar = {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.background)
                                        .statusBarsPadding()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (
                                        selected != null ||
                                            page !in
                                                setOf(
                                                    "dashboard",
                                                    "history",
                                                    "audio",
                                                    "map",
                                                    "settings",
                                                )
                                    ) {
                                        IconButton(onClick = ::goBack) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                stringResource(R.string.atlas_back),
                                            )
                                        }
                                    }
                                    AtlasMark(Modifier.padding(end = 10.dp).size(25.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            when {
                                                selected != null -> tr("detail")
                                                page == "assistant" -> "المساعد الذكي"
                                                page == "models" -> "نماذج الذكاء الاصطناعي"
                                                page == "assistant_profile" -> "تخصيص مساعدي"
                                                page == "voice" -> "الأوامر الصوتية"
                                                page == "studio" -> "استوديو المحاضرات"
                                                page == "player" -> "المشغل"
                                                page == "father_transfers" -> "حوالات الوالد"
                                                page == "temporary_places" ->
                                                    "الأماكن المؤقتة والجامعة"
                                                page == "repeated_routes" -> "المسارات المتكررة"
                                                page == "tracking_health" -> "مركز التتبع الذكي"
                                                page == "settings" -> tr("more")
                                                else -> tr(page)
                                            },
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                    }
                                    if (page == "player")
                                        IconButton(onClick = { openPage("audio") }) {
                                            Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                stringResource(R.string.atlas_back_library),
                                            )
                                        }
                                    else
                                        IconButton(
                                            onClick = {
                                                openPage(
                                                    if (page == "map") "settings" else "assistant"
                                                )
                                            }
                                        ) {
                                            Icon(
                                                if (page == "map") Icons.Default.Tune
                                                else Icons.Default.AutoAwesome,
                                                if (page == "map") tr("more")
                                                else stringResource(R.string.atlas_assistant),
                                            )
                                        }
                                }
                            },
                            bottomBar = {
                                if (page != "player")
                                    Column {
                                        if (audioPlayback.id.isNotBlank() && page != "player")
                                            sa.rihla.audio.AudioMiniPlayer(
                                                onOpen = { openPage("player") }
                                            )
                                        if (!wide)
                                            AtlasNavigation(
                                                page,
                                                selected != null,
                                                false,
                                                ::openPage,
                                            )
                                    }
                            },
                        ) { padding ->
                            Row(Modifier.padding(padding)) {
                                if (wide) AtlasNavigation(page, selected != null, true, ::openPage)
                                Box(
                                    Modifier.weight(1f).fillMaxHeight(),
                                    contentAlignment = Alignment.TopCenter,
                                ) {
                                    Column(
                                        Modifier.widthIn(
                                                max = if (page == "map") 1200.dp else 840.dp
                                            )
                                            .fillMaxWidth()
                                            .padding(
                                                horizontal = if (page == "map") 0.dp else 20.dp
                                            )
                                    ) {
                                        val o = data.outings.find { it.id == selected }
                                        if (o != null)
                                            OutingDetail(vm, data, o) {
                                                expenseOuting = o.id
                                                expense = true
                                            }
                                        else
                                            pageStates.SaveableStateProvider(page) {
                                                when (page) {
                                                    "dashboard" ->
                                                        Dashboard(
                                                            vm,
                                                            data,
                                                            onOpen = { selected = it },
                                                            onLive = { selected = data.active?.id },
                                                            onExpense = {
                                                                expenseOuting = data.active?.id
                                                                expense = true
                                                            },
                                                        )
                                                    "history" -> History(data) { selected = it }
                                                    "calendar" ->
                                                        CalendarScreen(data) { selected = it }
                                                    "map" -> sa.rihla.map.MapScreen(vm, data)
                                                    "father_transfers" -> FatherTransfersScreen()
                                                    "temporary_places" ->
                                                        TemporaryPlacesScreen(vm, data) {
                                                            selected = it
                                                        }
                                                    "repeated_routes" ->
                                                        androidx.compose.foundation.lazy
                                                            .LazyColumn {
                                                                item {
                                                                    RouteComparisonPanel(
                                                                        vm,
                                                                        data,
                                                                        0L to Long.MAX_VALUE,
                                                                    ) {
                                                                        selected = it
                                                                    }
                                                                }
                                                            }
                                                    "tracking_health" ->
                                                        TrackingDiagnosticsScreen(vm, data)
                                                    "audio" ->
                                                        sa.rihla.audio.AudioScreen(
                                                            onOpenPlayer = { openPage("player") }
                                                        )
                                                    "player" -> sa.rihla.audio.AudioPlayerScreen()
                                                    "assistant" ->
                                                        sa.rihla.assistant.AssistantScreen()
                                                    "voice" ->
                                                        sa.rihla.assistant.AssistantScreen(
                                                            voiceOnly = true
                                                        )
                                                    "assistant_profile" ->
                                                        sa.rihla.assistant.PersonalAssistantScreen()
                                                    "models" ->
                                                        sa.rihla.assistant.ModelCenterScreen()
                                                    "studio" ->
                                                        sa.rihla.audio.studio.StudioScreen(
                                                            viewModel(),
                                                            { openPage("audio") },
                                                            showHeading = false,
                                                        )
                                                    "statistics" ->
                                                        StatisticsScreen(data, vm) { selected = it }
                                                    "vehicle" -> VehicleScreen(data)
                                                    "expenses" ->
                                                        ExpensesScreen(data, vm) {
                                                            expenseOuting = data.active?.id
                                                            expense = true
                                                        }
                                                    "settings" ->
                                                        SettingsScreen(vm, data) { openPage(it) }
                                                }
                                            }
                                    }
                                }
                            }
                        }
                    }
                if (expense) ExpenseDialog(vm, data, expenseOuting) { expense = false }
                if (clearing)
                    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                        Card {
                            Column(
                                Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator()
                                Text(tr("deleting_data"))
                            }
                        }
                    }
                if (error != null)
                    AlertDialog(
                        onDismissRequest = { vm.error.value = null },
                        text = { Text(tr(error!!)) },
                        confirmButton = {
                            TextButton(onClick = { vm.error.value = null }) { Text(tr("done")) }
                        },
                    )
            }
        }
    }
}

@Composable
fun OutingCard(o: Outing, now: Long, details: TripDetails? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.TripOrigin,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Box(
                    Modifier.width(2.dp)
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Icon(
                    Icons.Default.Place,
                    null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        details?.title?.takeIf { it.isNotBlank() } ?: tr(o.classification),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (details?.favorite == true)
                        Icon(
                            Icons.Default.Star,
                            tr("favorite"),
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                }
                Text(
                    stamp(o.startedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    num(o.state.meters / 1000) + " " + tr("km"),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    duration((o.endedAt ?: now) - o.startedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (o.endedAt == null) StatusPill(tr("active"), true)
                if (!details?.note.isNullOrBlank())
                    Text(
                        details.note,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
            Icon(
                Icons.Default.ChevronLeft,
                null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
