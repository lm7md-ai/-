package sa.rihla.ui

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.*
import sa.rihla.core.*
import sa.rihla.data.*
import sa.rihla.tracking.*

fun trackingReason(reason: String): String =
    when (reason) {
        "MOCK" -> "موقع وهمي"
        "STALE" -> "إشارة قديمة"
        "ACCURACY" -> "دقة لا تكفي للتسجيل"
        "INVALID" -> "قيم موقع غير صالحة"
        "DUPLICATE",
        "ORDER" -> "إشارة مكررة أو خارج الترتيب"
        "JUMP" -> "قفزة بسرعة غير معقولة"
        "SUSPECT" -> "قفزة مشبوهة؛ ننتظر تأكيد الموقع"
        "ENGINE" -> "لم يجتز فحص المسار"
        "QUEUE" -> "ازدحام معالجة الإشارات"
        else -> "لا يوجد رفض حالي"
    }

@Composable
fun TrackingStatusCard(active: Boolean) {
    val live by LiveTrackingHealth.state.collectAsState()
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(active) {
        while (active && isActive) {
            elapsed = SystemClock.elapsedRealtime()
            delay(5000)
        }
    }
    Surface(
        onClick = { sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit("tracking_health") },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.GpsFixed, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("مركز التتبع الذكي", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (!active) "افحص الأذونات والإشارة قبل الانطلاق"
                    else if (live.receivedAt == 0L) "بانتظار إشارة موقع؛ اضغط للتشخيص"
                    else "آخر دقة: ${num(live.accuracy)} متر · ${live.provider}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronLeft, null)
        }
    }
}

@Composable
fun RouteQualityCard(points: List<RoutePoint>, outing: Outing, now: Long) {
    val report =
        remember(points, outing.endedAt, now) {
            routeQuality(points, outing.startedAt, outing.endedAt ?: now)
        }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("جودة تسجيل المسار", style = MaterialTheme.typography.titleMedium)
            if (report.points == 0) Text("لا توجد نقاط مسجلة بعد؛ لا يمكن تقييم الجودة.")
            else {
                Text("${report.points} نقطة · وسيط دقة الموقع ${num(report.medianAccuracy!!)} متر")
                Text("${report.goodFixes} نقطة بدقة 15 مترًا أو أفضل")
                Text("انقطاعات بين النقاط: ${report.gaps} · ${duration(report.gapMs)}")
                if (report.startDelayMs > 15000)
                    Text("تأخر أول موقع: ${duration(report.startDelayMs)}")
                if (report.tailDelayMs > 90000)
                    Text("الوقت بعد آخر موقع: ${duration(report.tailDelayMs)}")
            }
            Text(
                "الدقة تقدير مزود الموقع وليست قياسًا للخطأ الحقيقي. لا تُرسم وصلة ولا تُحسب مسافة عبر الانقطاع المحدد. الإشارات المرفوضة لا تظهر في هذا التقرير.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TrackingDiagnosticsScreen(vm: JourneyViewModel, data: ScreenData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by LiveTrackingHealth.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5000)
            elapsed = SystemClock.elapsedRealtime()
        }
    }
    val fine = refresh >= 0 && TrackingService.fineAllowed(context)
    val background = TrackingService.backgroundAllowed(context)
    val enabled = runCatching {
        if (Build.VERSION.SDK_INT >= 28)
            context.getSystemService(LocationManager::class.java).isLocationEnabled
        else Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
    }
        .getOrDefault(false)
    val battery =
        context
            .getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    val restricted =
        Build.VERSION.SDK_INT >= 28 &&
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
    val notifications =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    val gms =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS
    fun open(action: String, app: Boolean = false) {
        runCatching {
            context.startActivity(
                Intent(action).apply {
                    if (app) this.data = android.net.Uri.parse("package:${context.packageName}")
                }
            )
        }
            .onFailure { result = "تعذر فتح صفحة النظام؛ افتح إعدادات الهاتف ثم مشواري." }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("جاهزية التتبع", "الأذونات، الإشارة، واستمرار التسجيل")
        val checks =
            listOf(
                Triple("الموقع الدقيق", fine, "مطلوب للمسار الدقيق"),
                Triple("خدمة الموقع في الهاتف", enabled, "فعّل الموقع من إعدادات النظام"),
                Triple("موقع الخلفية", background, "مطلوب للبدء التلقائي من الخلفية"),
                Triple(
                    "التعرف على النشاط",
                    Detection.available(context),
                    "يساعد على اكتشاف القيادة تلقائيًا",
                ),
                Triple("إشعارات التطبيق", notifications, "لإظهار حالة التسجيل والتحكم به"),
                Triple("قيود الخلفية", !restricted, "راجع قيود التطبيق في النظام"),
                Triple(
                    "استثناء تحسين البطارية",
                    battery,
                    "يساعد على الاستمرار؛ لا يضمن تجاوز قيود RedMagic",
                ),
                Triple(
                    "مكان البيت محفوظ",
                    data.places.any { it.type == "HOME" },
                    "لمحفز المغادرة والعودة التلقائي",
                ),
            )
        checks.forEach { (label, ready, explanation) ->
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        if (ready) Icons.Default.CheckCircle else Icons.Default.Info,
                        null,
                        tint =
                            if (ready) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (ready) "متاح" else explanation,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        Text(
            "التسجيل التلقائي: ${if (data.settings.auto) "مفعل" else "غير مفعل"} · مزود Google: ${if (gms) "متاح" else "غير متاح؛ GPS المباشر للتسجيل، ومحفزات النشاط والبيت قد لا تعمل"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) },
                modifier = Modifier.weight(1f),
            ) {
                Text("أذونات التطبيق")
            }
            OutlinedButton(
                onClick = { open(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
                modifier = Modifier.weight(1f),
            ) {
                Text("إعدادات الموقع")
            }
        }
        TextButton(onClick = { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) {
            Text("إعدادات تحسين البطارية")
        }
        TextButton(
            onClick = { sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit("settings") }
        ) {
            Text("التتبع وأماكني من الإعدادات")
        }
        HorizontalDivider()
        SectionTitle("الإشارة والتسجيل الآن")
        Text(
            "الخدمة: " +
                when (live.service) {
                    "running" -> "تسجيل جارٍ"
                    "checking_driving" -> "التحقق من القيادة"
                    "permission" -> "إذن مفقود"
                    "error" -> "توقفت بسبب خطأ"
                    "blocked" -> "منع النظام بدء الخدمة"
                    "starting" -> "جارٍ البدء"
                    else -> "غير مشغلة"
                }
        )
        if (live.receivedAt > 0) {
            val stale = live.updatedElapsed > 0 && elapsed - live.updatedElapsed > 60000
            Text(
                "آخر إشارة: ${stamp(live.receivedAt, true)} · ${live.provider} · ±${num(live.accuracy)} متر"
            )
            if (stale && data.active != null)
                Text(
                    "لم تصل إشارة حديثة منذ أكثر من دقيقة؛ افتح المكان أو راجع الموقع والأذونات.",
                    color = MaterialTheme.colorScheme.error,
                )
        } else Text("بانتظار موقع؛ افتح مكانًا مكشوفًا وجرب اختبار الإشارة.")
        Text(
            "نقاط حفظت في هذه الجلسة: ${live.acceptedCount} · إشارات مستبعدة: ${live.rejectedCount}"
        )
        Text(
            "آخر سبب استبعاد: ${trackingReason(live.reason)}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (live.requestedInterval > 0)
            Text(
                "فاصل الطلب الحالي: ${live.requestedInterval / 1000} ثانية؛ قد يختلف وصول الإشارات الفعلي."
            )
        if (live.satellites > 0) {
            Text(
                "GNSS: ${live.usedSatellites} مستخدم من ${live.satellites} · متوسط C/N₀: ${num(live.averageCn0)} dB-Hz"
            )
            if (live.dualFrequency > 0) Text("إشارات L5/E5 مستخدمة: ${live.dualFrequency}")
        } else
            Text(
                "بيانات الأقمار غير متاحة حاليًا من النظام؛ هذا لا يعني أن مزود الموقع لا يعمل.",
                style = MaterialTheme.typography.bodySmall,
            )
        Button(
            onClick = {
                scope.launch {
                    testing = true
                    try {
                        val fix = LocationPicker.current(context)
                        result =
                            if (fix == null)
                                "لم نحصل على موقع صالح؛ راجع الموقع ثم جرب في مكان مكشوف."
                            else
                                "نجح اختبار الإشارة بدقة ±${num(fix.accuracy)} متر. لم يُنشأ مشوار."
                    } finally {
                        testing = false
                    }
                }
            },
            enabled = fine && !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (testing) "جارٍ اختبار الإشارة…" else "اختبار إشارة الموقع")
        }
        Button(
            onClick = {
                Detection.register(context, data.settings.auto) {
                    result = "تعذر تسجيل أحد محفزات التلقائي؛ راجع الأذونات وخدمات Google."
                }
                if (data.active != null && fine)
                    result =
                        if (TrackingService.launch(context))
                            "أُرسل طلب استئناف التسجيل؛ راقب وصول الإشارة."
                        else "منع النظام الاستئناف؛ راجع أذونات التطبيق."
                else result = "أُرسل طلب إعادة تسجيل محفزات التلقائي وفق إعداداتك؛ لم يُنشأ مشوار."
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("إعادة تهيئة التتبع والاستئناف")
        }
        if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.primary)
        TextButton(
            onClick = {
                val text =
                    "مشواري 6.0 / Android ${Build.VERSION.SDK_INT}\n${Build.MANUFACTURER} ${Build.MODEL}\nFine=$fine Background=$background Location=$enabled Activity=${Detection.available(context)} Notifications=$notifications BatteryExempt=$battery Restricted=$restricted Google=$gms\nService=${live.service} Provider=${live.provider} Accuracy=${live.accuracy} Interval=${live.requestedInterval}\nAccepted=${live.acceptedCount} Rejected=${live.rejectedCount} Reason=${live.reason}\nGNSS=${live.usedSatellites}/${live.satellites} Cn0=${live.averageCn0} L5=${live.dualFrequency}"
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, text),
                        "مشاركة تقرير التشخيص",
                    )
                )
            }
        ) {
            Text("مشاركة تقرير تشخيص دون إحداثيات")
        }
        Text(
            "الأوامر القسرية لإيقاف التطبيق، سحب الأذونات، وبعض قيود الشركة المصنعة قد تمنع التلقائي. دمج حساسات الموقع يتم عبر مزود Android؛ لا يفتح التطبيق الميكروفون ولا يسجل حساسات إضافية في الخلفية. الدقة الأعلى قد تزيد استهلاك البطارية.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
