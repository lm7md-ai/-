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
    val context = LocalContext.current
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
                if (!data.settings.onboarded) Onboarding(vm, data)
                else
                    Scaffold(
                        topBar = {
                            Row(
                                Modifier.fillMaxWidth()
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
                                                "assistant",
                                                "settings",
                                            )
                                ) {
                                    IconButton(onClick = ::goBack) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع")
                                    }
                                }
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
                                            "طي المشغل إلى المكتبة",
                                        )
                                    }
                                else
                                    IconButton(onClick = { openPage("map") }) {
                                        Icon(Icons.Default.Map, "الخريطة")
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
                                    NavigationBar(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 0.dp,
                                    ) {
                                        listOf(
                                                "dashboard" to Icons.Default.Home,
                                                "history" to Icons.Default.Route,
                                                "audio" to Icons.Default.Headphones,
                                                "assistant" to Icons.Default.AutoAwesome,
                                                "settings" to Icons.Default.GridView,
                                            )
                                            .forEach { (key, icon) ->
                                                NavigationBarItem(
                                                    selected =
                                                        (page == key ||
                                                            (key == "assistant" &&
                                                                page in
                                                                    setOf(
                                                                        "models",
                                                                        "assistant_profile",
                                                                        "voice",
                                                                    )) ||
                                                            (key == "audio" &&
                                                                page in
                                                                    setOf("studio", "player"))) &&
                                                            selected == null,
                                                    onClick = {
                                                        openPage(key)
                                                    },
                                                    icon = {
                                                        Icon(
                                                            icon,
                                                            if (key == "assistant") "المساعد الذكي"
                                                            else tr(key),
                                                        )
                                                    },
                                                    label = {
                                                        Text(
                                                            if (key == "assistant") "المساعد"
                                                            else
                                                                tr(
                                                                    if (key == "settings") "more"
                                                                    else key
                                                                ),
                                                            maxLines = 1,
                                                        )
                                                    },
                                                )
                                            }
                                    }
                                }
                        },
                    ) { padding ->
                        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
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
                                        "calendar" -> CalendarScreen(data) { selected = it }
                                        "map" -> sa.rihla.map.MapScreen(vm, data)
                                        "audio" ->
                                            sa.rihla.audio.AudioScreen(
                                                onOpenPlayer = { openPage("player") }
                                            )
                                        "player" -> sa.rihla.audio.AudioPlayerScreen()
                                        "assistant" -> sa.rihla.assistant.AssistantScreen()
                                        "voice" ->
                                            sa.rihla.assistant.AssistantScreen(voiceOnly = true)
                                        "assistant_profile" ->
                                            sa.rihla.assistant.PersonalAssistantScreen()
                                        "models" -> sa.rihla.assistant.ModelCenterScreen()
                                        "studio" ->
                                            sa.rihla.audio.studio.StudioScreen(
                                                viewModel(),
                                                { openPage("audio") },
                                                showHeading = false,
                                            )
                                        "statistics" -> StatisticsScreen(data, vm) { selected = it }
                                        "vehicle" -> VehicleScreen(data)
                                        "expenses" ->
                                            ExpensesScreen(data, vm) {
                                                expenseOuting = data.active?.id
                                                expense = true
                                            }
                                        "settings" -> SettingsScreen(vm, data) { openPage(it) }
                                    }
                                }
                        }
                    }
                if (expense) ExpenseDialog(vm, data, expenseOuting) { expense = false }
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
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Route,
                    null,
                    modifier = Modifier.padding(end = 10.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    details?.title?.takeIf { it.isNotBlank() } ?: tr(o.classification),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (details?.favorite == true)
                    Icon(
                        Icons.Default.Star,
                        tr("favorite"),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
            }
            Text(
                stamp(o.startedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                num(o.state.meters / 1000) +
                    " " +
                    tr("km") +
                    " · " +
                    duration((o.endedAt ?: now) - o.startedAt)
            )
            if (o.endedAt == null) Text(tr("active"), color = MaterialTheme.colorScheme.primary)
            if (!details?.note.isNullOrBlank())
                Text(
                    details.note,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}
