package sa.rihla.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.google.android.gms.location.*
import java.io.File
import kotlinx.coroutines.*
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
import sa.rihla.map.RouteMap
import sa.rihla.tracking.*
import sa.rihla.ui.*

@Composable
fun PermissionsPanel(vm: JourneyViewModel) {
    val c = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh++
            if (!TrackingService.fineAllowed(c)) vm.error.value = "accuracy_notice"
        }
    val single =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    refresh++
                    Detection.register(c, vm.state.value.settings.auto) {
                        vm.error.value = "detection_failed"
                    }
                }
            }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    fun allowed(name: String) = c.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
    Heading("permissions")
    Text(tr("location_explanation"))
    Metric(
        "fine_location",
        tr(if (refresh >= 0 && TrackingService.fineAllowed(c)) "allowed" else "denied"),
    )
    Action("grant_location") {
        permission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }
    Text(tr("background_explanation"))
    Metric(
        "background_location",
        tr(if (TrackingService.backgroundAllowed(c)) "allowed" else "denied"),
    )
    Action("background_location", enabled = TrackingService.fineAllowed(c)) {
        if (Build.VERSION.SDK_INT == 29)
            single.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else
            c.startActivity(
                Intent(
                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${c.packageName}".toUri(),
                )
            )
    }
    Metric("activity_permission", tr(if (Detection.available(c)) "allowed" else "denied"))
    if (Build.VERSION.SDK_INT >= 29)
        Action("grant_activity") { single.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
    if (Build.VERSION.SDK_INT >= 33) {
        Metric(
            "notifications",
            tr(if (allowed(Manifest.permission.POST_NOTIFICATIONS)) "allowed" else "denied"),
        )
        Action("grant_notifications") { single.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
    Action("system_settings") {
        c.startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${c.packageName}".toUri(),
            )
        )
    }
}

@Composable
fun SettingsScreen(vm: JourneyViewModel, data: ScreenData, navigate: (String) -> Unit = {}) {
    var place by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Hero(tr("brand_caption"), tr("your_space"), tr("local_first_caption"), "vehicle") {
            navigate("vehicle")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { navigate("calendar") }, modifier = Modifier.weight(1f)) {
                Text(tr("calendar"))
            }
            OutlinedButton(onClick = { navigate("vehicle") }, modifier = Modifier.weight(1f)) {
                Text(tr("vehicle"))
            }
        }
        BudgetSettings(vm, data)
        Button(onClick = { navigate("map") }, modifier = Modifier.fillMaxWidth()) {
            Text("خريطة السعودية وأدوات المسار")
        }
        DataTools(vm, data)
        DeviceReadiness(data)
        TrackingOptions(vm, data)
        Heading("theme")
        Row {
            listOf("SYSTEM", "LIGHT", "DARK").forEach { v ->
                FilterChip(data.settings.theme == v, { vm.theme(v) }, label = { Text(tr(v)) })
            }
        }
        Heading("auto_tracking")
        Text(tr("manual_available"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                data.settings.auto,
                { vm.automatic(it) },
                enabled =
                    data.settings.auto ||
                        (TrackingService.backgroundAllowed(LocalContext.current) &&
                            (Detection.available(LocalContext.current) ||
                                data.settings.bluetoothAddress.isNotBlank())),
            )
            Text(tr("auto_tracking"))
        }
        Text(tr("tracking_limits"))
        Action("configure_home") { place = "HOME" }
        Action("configure_university") { place = "UNIVERSITY" }
        Text(tr("no_official_boundary"))
        FuelCostSettings(vm, data)
        SmsImportSettings(vm, data)
        PermissionsPanel(vm)
        OfflineImport(vm, data)
        Heading("privacy")
        Text(tr("privacy"))
        Action("delete_all") { deleting = true }
    }
    if (place != null) PlaceDialog(vm, data, place!!) { place = null }
    if (deleting) Confirm("delete_all_confirmation", { deleting = false }) { vm.clear() }
}

@Composable
fun SmsImportSettings(vm: JourneyViewModel, data: ScreenData) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var enabling by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refresh++
            if (enabling) {
                vm.smsImport(sa.rihla.sms.PurchaseNotificationService.allowed(context))
                enabling = false
            }
        }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val allowed = refresh >= 0 && sa.rihla.sms.PurchaseNotificationService.allowed(context)
    val messagesApp = android.provider.Telephony.Sms.getDefaultSmsPackage(context)
    Heading("sms_import")
    Text(tr("sms_import_explanation"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            data.settings.smsImport,
            { enabled ->
                if (!enabled || allowed) vm.smsImport(enabled)
                else {
                    enabling = true
                    launcher.launch(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            },
            enabled = messagesApp != null || data.settings.smsImport,
        )
        Text(tr("sms_import"))
    }
    Metric("sms_access", tr(if (allowed) "allowed" else "denied"))
    if (messagesApp == null) Text(tr("sms_app_missing"))
    if (data.settings.smsImport && !allowed) Text(tr("sms_access_required"))
    Action("sms_access_settings") {
        launcher.launch(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    Text(tr("sms_import_limits"), style = MaterialTheme.typography.bodySmall)
}

@Composable
fun PlaceDialog(vm: JourneyViewModel, data: ScreenData, type: String, onDismiss: () -> Unit) {
    val c = LocalContext.current
    val existing = data.places.find { it.type == type }
    var selected by remember { mutableStateOf(existing?.let { it.latitude to it.longitude }) }
    var radius by remember {
        mutableStateOf(
            (existing?.radius ?: if (type == "HOME") 200.0 else 800.0).toInt().toString()
        )
    }
    var polygonMode by remember { mutableStateOf(existing?.polygon?.isNotEmpty() == true) }
    var corners by remember {
        mutableStateOf<List<Pair<Double, Double>>>(
            sa.rihla.core.parsePolygon(existing?.polygon ?: "")
        )
    }
    val pickerScope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Heading(if (type == "HOME") "configure_home" else "configure_university")
                Text(tr("map_selection"))
                RouteMap(
                    emptyList(),
                    emptyList(),
                    data.places,
                    Modifier.fillMaxWidth().height(350.dp),
                    selected,
                    corners,
                ) { a, b ->
                    if (polygonMode) corners = corners + (a to b) else selected = a to b
                }
                Text(tr("attribution"))
                if (data.settings.offlineAttribution.isNotEmpty())
                    Text(data.settings.offlineAttribution)
                Action("current_location") {
                    if (!TrackingService.fineAllowed(c)) vm.error.value = "location_failed"
                    else {
                        pickerScope.launch {
                            val fix = LocationPicker.current(c)
                            if (fix != null) selected = fix.lat to fix.lon
                            else vm.error.value = "location_failed"
                        }
                    }
                }
                if (type == "UNIVERSITY") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(polygonMode, { polygonMode = it })
                        Text(tr("polygon"))
                    }
                    if (polygonMode) {
                        Text(tr("polygon_hint"))
                        Action("reset_polygon") { corners = emptyList() }
                    }
                }
                if (!polygonMode) FormField("radius", radius, { radius = it })
                Action(
                    "save",
                    enabled =
                        if (polygonMode) corners.size >= 3
                        else selected != null && (radius.toDoubleOrNull() ?: 0.0) in 50.0..10000.0,
                ) {
                    val center =
                        if (polygonMode)
                            corners.map { it.first }.average() to
                                corners.map { it.second }.average()
                        else selected!!
                    vm.place(
                        SavedPlace(
                            type,
                            center.first,
                            center.second,
                            radius.toDoubleOrNull() ?: 800.0,
                            if (polygonMode)
                                corners.joinToString(";") { "${it.first},${it.second}" }
                            else "",
                        )
                    )
                    onDismiss()
                }
                Action("cancel", click = onDismiss)
            }
        }
    }
}

@Composable
fun OfflineImport(vm: JourneyViewModel, data: ScreenData) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(data.settings.offlineAttribution) }
    var consent by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    importing = true
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val target = File(c.filesDir, "offline.mbtiles")
                                val temp = File(c.filesDir, "offline.pending")
                                try {
                                    c.contentResolver.openInputStream(uri)!!.use { input ->
                                        temp.outputStream().use { output ->
                                            val bytes = ByteArray(32768)
                                            var total = 0L
                                            while (true) {
                                                val n = input.read(bytes)
                                                if (n < 0) break
                                                total += n
                                                require(total <= 2L * 1024 * 1024 * 1024)
                                                output.write(bytes, 0, n)
                                            }
                                        }
                                    }
                                    SQLiteDatabase.openDatabase(
                                            temp.path,
                                            null,
                                            SQLiteDatabase.OPEN_READONLY,
                                        )
                                        .use { db ->
                                            db.rawQuery("PRAGMA quick_check", null).use {
                                                require(it.moveToFirst() && it.getString(0) == "ok")
                                            }
                                            db.rawQuery(
                                                    "SELECT value FROM metadata WHERE name='format'",
                                                    null,
                                                )
                                                .use {
                                                    require(
                                                        it.moveToFirst() &&
                                                            it.getString(0) in
                                                                listOf("png", "jpg", "jpeg")
                                                    )
                                                }
                                            db.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null)
                                                .use {
                                                    require(it.moveToFirst())
                                                    val bytes = it.getBlob(0)
                                                    require(
                                                        android.graphics.BitmapFactory
                                                            .decodeByteArray(
                                                                bytes,
                                                                0,
                                                                bytes.size,
                                                            ) != null
                                                    )
                                                }
                                        }
                                    require(temp.renameTo(target))
                                    vm.app.settings.attribution(source)
                                } finally {
                                    temp.delete()
                                }
                            }
                        }
                    importing = false
                    vm.error.value = if (result.isSuccess) "offline_ready" else "offline_failed"
                }
        }
    Heading("offline_map")
    Text(tr("bundled_map_ready"))
    Text(sa.rihla.map.OfflineMapStore.Attribution, style = MaterialTheme.typography.labelSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(data.settings.onlineMaps, { vm.onlineMaps(it) })
        Text(tr("online_map_optional"))
    }
    Text(tr("offline_default_explanation"))
    Text(tr("offline_explanation"))
    FormField("offline_attribution", source, { source = it }, false)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(consent, { consent = it })
        Text(tr("confirm_import"))
    }
    Action("import_map", enabled = source.isNotBlank() && consent && !importing) {
        picker.launch(
            arrayOf(
                "application/octet-stream",
                "application/x-sqlite3",
                "application/vnd.sqlite3",
                "*/*",
            )
        )
    }
    Text(tr("map_source_notice"))
}
