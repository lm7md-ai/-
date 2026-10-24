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
import androidx.compose.material.icons.filled.*
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
    Text("الميكروفون: " + if (allowed(Manifest.permission.RECORD_AUDIO)) "مسموح" else "غير مسموح")
    Button(
        onClick = { single.launch(Manifest.permission.RECORD_AUDIO) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("منح إذن الميكروفون للأوامر الصوتية")
    }
    Text(
        "يُستخدم عند الضغط للتحدث فقط. يمكنك تغيير الإذن من إعدادات التطبيق في الهاتف.",
        style = MaterialTheme.typography.bodySmall,
    )
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
    var section by
        androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    androidx.activity.compose.BackHandler(enabled = section != null) { section = null }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (section == null) {
            Text("كل ما تحتاجه", style = MaterialTheme.typography.headlineMedium)
            Text(
                "أدواتك وإعداداتك في مكان واحد",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                query,
                { query = it },
                label = { Text("ابحث في الإعدادات والأدوات") },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth(),
            )
            val groups =
                listOf(
                    "الذكاء الاصطناعي" to
                        listOf(
                            Triple(
                                "models",
                                "نماذج الذكاء الاصطناعي",
                                "النموذج المدمج وتنزيل Qwen وفصل الموسيقى",
                            ),
                            Triple(
                                "assistant",
                                "المساعد الذكي",
                                "اكتب أو تحدث لاقتراح أوامر التطبيق",
                            ),
                            Triple(
                                "assistant_profile",
                                "تخصيص مساعدي وتدريب نموذجي",
                                "اسمي وتعليماتي وأمثلة أوامري",
                            ),
                            Triple(
                                "voice",
                                "الأوامر الصوتية",
                                "الميكروفون واللغة العربية وقراءة الردود",
                            ),
                        ),
                    "أدوات التطبيق" to
                        listOf(
                            Triple(
                                "father_transfers",
                                "حوالات الوالد",
                                "الأرشيف والقديم والجديد وآخر حوالة",
                            ),
                            Triple(
                                "temporary_places",
                                "الأماكن المؤقتة والجامعة",
                                "منزل أو استراحة مؤقتة وإحصائية الجامعة",
                            ),
                            Triple(
                                "repeated_routes",
                                "المسارات المتكررة",
                                "قارن الوقت والمسافة والتوقفات وساعة المغادرة",
                            ),
                            Triple(
                                "tracking_health",
                                "مركز التتبع الذكي",
                                "فحص الإشارة والأذونات والأقمار واستئناف التسجيل",
                            ),
                            Triple(
                                "audio",
                                "مكتبة الصوتيات والمشغل",
                                "المحاضرات والقوائم والمفضلة",
                            ),
                            Triple(
                                "studio",
                                "استوديو المحاضرات",
                                "تحسين الصوت وتنظيم الملفات وفصل الموسيقى",
                            ),
                            Triple(
                                "map",
                                "خريطة السعودية وأدوات المسار",
                                "خرائط دون إنترنت وبحث وطبقات",
                            ),
                            Triple("calendar", "التقويم", "المشاوير حسب الأيام"),
                            Triple("expenses", "المصروفات", "مصاريفك وعمليات الشراء"),
                            Triple("statistics", "الإحصاءات", "تحليل الرحلات والتكاليف"),
                            Triple("vehicle", "سيارتي", "بيانات السيارة والاستهلاك"),
                        ),
                    "إعدادات التطبيق" to
                        listOf(
                            Triple(
                                "tracking",
                                "التتبع وأماكني",
                                "التتبع التلقائي والبيت والجامعة والبلوتوث",
                            ),
                            Triple(
                                "finance",
                                "الميزانية والوقود",
                                "حساب التكلفة والأسعار والاستهلاك",
                            ),
                            Triple("sms", "رسائل الشراء", "الاستقبال والأذونات وإضافة رسالة فائتة"),
                            Triple(
                                "permissions",
                                "الأذونات والبطارية",
                                "الموقع والإشعارات وتشخيص جاهزية الهاتف",
                            ),
                            Triple("appearance", "المظهر", "الوضع الفاتح والداكن والنظام"),
                            Triple("maps_settings", "إعدادات الخرائط", "مصادر الخرائط والاستيراد"),
                            Triple(
                                "backup",
                                "نسخ بياناتك محليًا",
                                "التصدير والاستعادة وأدوات البيانات",
                            ),
                            Triple(
                                "privacy",
                                "الخصوصية والبيانات",
                                "بياناتك المحلية وخيارات الحذف",
                            ),
                        ),
                )
            for ((title, entries) in groups) {
                val visible = entries.filter {
                    query.isBlank() || (it.second + " " + it.third).contains(query.trim(), true)
                }
                if (visible.isNotEmpty())
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                    )
                for ((key, label, description) in visible) sa.rihla.assistant.FeatureRow(
                    label,
                    description,
                    when (key) {
                        "repeated_routes" -> androidx.compose.material.icons.Icons.Default.Route
                        "tracking_health" -> androidx.compose.material.icons.Icons.Default.GpsFixed
                        "models" -> androidx.compose.material.icons.Icons.Default.Memory
                        "assistant",
                        "assistant_profile" ->
                            androidx.compose.material.icons.Icons.Default.AutoAwesome
                        "voice" -> androidx.compose.material.icons.Icons.Default.Mic
                        "audio",
                        "studio" -> androidx.compose.material.icons.Icons.Default.Headphones
                        "map",
                        "maps_settings" -> androidx.compose.material.icons.Icons.Default.Map
                        "calendar" -> androidx.compose.material.icons.Icons.Default.CalendarMonth
                        "expenses" -> androidx.compose.material.icons.Icons.Default.Payments
                        "statistics" -> androidx.compose.material.icons.Icons.Default.BarChart
                        "vehicle" -> androidx.compose.material.icons.Icons.Default.DirectionsCar
                        "tracking" -> androidx.compose.material.icons.Icons.Default.Route
                        "finance" -> androidx.compose.material.icons.Icons.Default.LocalGasStation
                        "sms" -> androidx.compose.material.icons.Icons.Default.Sms
                        "permissions" -> androidx.compose.material.icons.Icons.Default.Shield
                        "appearance" -> androidx.compose.material.icons.Icons.Default.Palette
                        "backup" -> androidx.compose.material.icons.Icons.Default.Backup
                        "privacy" -> androidx.compose.material.icons.Icons.Default.Lock
                        else -> androidx.compose.material.icons.Icons.Default.Tune
                    },
                ) {
                    if (
                        key in
                            setOf(
                                "tracking",
                                "finance",
                                "sms",
                                "permissions",
                                "appearance",
                                "maps_settings",
                                "backup",
                                "privacy",
                            )
                    )
                        section = key
                    else navigate(key)
                }
            }
        } else {
            TextButton(onClick = { section = null }) { Text("العودة إلى الإعدادات والأدوات") }
            when (section) {
                "tracking" -> {
                    TrackingOptions(vm, data)
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
                                            data.places.any { it.type == "HOME" } ||
                                            data.settings.bluetoothAddress.isNotBlank())),
                        )
                        Text(tr("auto_tracking"))
                    }
                    Text(tr("tracking_limits"))
                    val trackingContext = LocalContext.current
                    var homeStatus by remember { mutableStateOf("") }
                    var serviceStatus by remember { mutableStateOf("") }
                    LaunchedEffect(Unit) {
                        while (true) {
                            homeStatus =
                                trackingContext
                                    .getSharedPreferences(
                                        "tracking_health",
                                        android.content.Context.MODE_PRIVATE,
                                    )
                                    .getString("home", "")
                                    .orEmpty()
                            serviceStatus =
                                trackingContext
                                    .getSharedPreferences(
                                        "tracking_health",
                                        android.content.Context.MODE_PRIVATE,
                                    )
                                    .getString("service", "")
                                    .orEmpty()
                            delay(5000)
                        }
                    }
                    Text(
                        "محفّز مغادرة البيت: " +
                            when (homeStatus) {
                                "registered" -> "مسجل لدى النظام"
                                "error" -> "تعذر التسجيل؛ راجع إذن الموقع الدائم وخدمات Google"
                                else -> "غير مسجل؛ حدد البيت وفعّل الموقع الدائم والتتبع التلقائي"
                            }
                    )
                    Text(
                        "تبدأ محاولة التتبع عند مغادرة نطاق البيت، ثم يؤكد GPS القيادة. تنتهي الرحلة بعد توقف ثابت داخل نطاق البيت لمدة 5 دقائق. قد يؤخر النظام محفّز النطاق، ويحتاج GPS إلى إشارة صالحة.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "آخر حالة تشغيل التتبع: " +
                            when (serviceStatus) {
                                "checking_driving" -> "بدأ فحص القيادة"
                                "running" -> "شُغّلت خدمة تسجيل المسار"
                                "permission" -> "منع النظام الخدمة بسبب إذن الموقع"
                                "blocked" -> "منع النظام بدء الخدمة في الخلفية"
                                "error" -> "تعذرت متابعة التسجيل؛ افتح التطبيق لإعادة المحاولة"
                                else -> "لم تُشغّل الخدمة بعد"
                            }
                    )
                    Action("configure_home") { place = "HOME" }
                    Action("configure_university") { place = "UNIVERSITY" }
                    Text(tr("no_official_boundary"))
                }
                "finance" -> {
                    BudgetSettings(vm, data)
                    FuelCostSettings(vm, data)
                }
                "sms" -> SmsImportSettings(vm, data)
                "permissions" -> {
                    DeviceReadiness(data)
                    PermissionsPanel(vm)
                }
                "appearance" -> {
                    Heading("theme")
                    Column {
                        listOf("SYSTEM", "LIGHT", "DARK").forEach { value ->
                            FilterChip(
                                data.settings.theme == value,
                                { vm.theme(value) },
                                label = { Text(tr(value)) },
                            )
                        }
                    }
                }
                "maps_settings" -> OfflineImport(vm, data)
                "backup" -> DataTools(vm, data)
                "privacy" -> {
                    Heading("privacy")
                    Text(tr("privacy"))
                    Action("delete_all") { deleting = true }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (place != null) PlaceDialog(vm, data, place!!) { place = null }
    if (deleting) Confirm("delete_all_confirmation", { deleting = false }) { vm.clear() }
}

@Composable
fun SmsImportSettings(vm: JourneyViewModel, data: ScreenData) {
    val context = LocalContext.current
    val health =
        remember(context) {
            context.getSharedPreferences("sms_health", android.content.Context.MODE_PRIVATE)
        }
    var refresh by remember { mutableIntStateOf(0) }
    var enabling by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var pasteResult by remember { mutableStateOf<String?>(null) }
    val smsPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refresh++
            if (granted) vm.smsImport(true)
        }
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
    val direct = refresh >= 0 && sa.rihla.sms.PurchaseSmsReceiver.allowed(context)
    val messagesApp = android.provider.Telephony.Sms.getDefaultSmsPackage(context)
    Heading("sms_import")
    Text(tr("sms_import_explanation"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            data.settings.smsImport,
            { enabled ->
                if (!enabled || allowed || direct) vm.smsImport(enabled)
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
    Text(
        if (direct) "استقبال الرسائل الجديدة مباشرة: مسموح"
        else "استقبال الرسائل الجديدة مباشرة: غير مسموح"
    )
    Button(onClick = { smsPermission.launch(Manifest.permission.RECEIVE_SMS) }) {
        Text("تفعيل استقبال رسائل الشراء مباشرة")
    }
    Text(
        "بإذنك، يُستخرج المبلغ والتاجر من رسائل الشراء الجديدة على الجهاز، دون رفع الرسائل أو حفظ نصها. لا يقرأ هذا الإذن الرسائل السابقة. إذا منع الهاتف الإذن، استخدم إشعارات الرسائل أو الصق الرسالة هنا.",
        style = MaterialTheme.typography.bodySmall,
    )
    DisposableEffect(health) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                main.post { refresh++ }
            }
        health.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            health.unregisterOnSharedPreferenceChangeListener(listener)
            main.removeCallbacksAndMessages(null)
        }
    }
    val lastResult =
        when (health.getString("result", null)) {
            "imported" -> "تم تسجيل عملية شراء"
            "unrecognized" -> "وصل نص، لكنه لم يتضمن عملية شراء واضحة؛ قد يكون إشعارًا مخفيًا"
            "duplicate_or_disabled" -> "عملية مكررة أو الاستيراد متوقف أو الرسالة أقدم من التفعيل"
            "error" -> "تعذر حفظ العملية؛ الصق الرسالة لإعادة المحاولة"
            else -> "لم يستقبل التطبيق نص رسالة حتى الآن"
        }
    Text("حالة الاستقبال: $lastResult")
    Text(
        "اتصال قارئ الإشعارات: " +
            when (health.getString("listener", null)) {
                "connected" -> "متصل"
                "disconnected" -> "إعادة اتصال مطلوبة"
                else -> "لم يتأكد الاتصال"
            }
    )
    Button(
        onClick = {
            pasting = true
            pasteResult = null
        }
    ) {
        Text("إضافة رسالة شراء فائتة")
    }
    if (pasting)
        Dialog(
            onDismissRequest = {
                pasting = false
                pasted = ""
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxWidth().padding(24.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("الصق رسالة الشراء")
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it.take(10000) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                    Text("يُستخدم تاريخ رسالة الراجحي إن وُجد، وتُمنع إضافة نفس الرسالة مرتين.")
                    pasteResult?.let { Text(it) }
                    Button(
                        onClick = {
                            vm.importPastedSms(pasted) { ok ->
                                pasteResult =
                                    if (ok) "تم تسجيل العملية"
                                    else "الرسالة مكررة أو لا تتضمن مبلغ شراء واضحًا"
                            }
                        },
                        enabled = pasted.isNotBlank(),
                    ) {
                        Text("استخراج وتسجيل")
                    }
                    TextButton(
                        onClick = {
                            pasting = false
                            pasted = ""
                        }
                    ) {
                        Text("إغلاق")
                    }
                }
            }
        }
    if (messagesApp == null) Text(tr("sms_app_missing"))
    if (data.settings.smsImport && !allowed && !direct) Text(tr("sms_access_required"))
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
            (existing?.radius ?: if (type == "HOME" || type.startsWith("TEMP_")) 200.0 else 800.0)
                .toInt()
                .toString()
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
                Text(
                    if (type.startsWith("TEMP_")) "حدد موقع المكان المؤقت"
                    else tr(if (type == "HOME") "configure_home" else "configure_university"),
                    style = MaterialTheme.typography.titleLarge,
                )
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
                                            db.rawQuery(
                                                    "SELECT tile_data FROM tiles WHERE length(tile_data)<=2000000 LIMIT 1",
                                                    null,
                                                )
                                                .use {
                                                    require(it.moveToFirst())
                                                    val bytes = it.getBlob(0)
                                                    require(sa.rihla.map.TileImages.valid(bytes))
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
