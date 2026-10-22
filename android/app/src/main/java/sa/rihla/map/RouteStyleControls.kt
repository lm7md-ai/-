package sa.rihla.map

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import sa.rihla.ui.*

@Stable
class RouteStyleState(context: Context) {
    private val prefs = context.getSharedPreferences("route_display", Context.MODE_PRIVATE)
    var value by
        mutableStateOf(
            RouteDisplayOptions(
                confidence = prefs.getBoolean("confidence", true),
                cleanStops = prefs.getBoolean("clean", true),
                matchRoads = prefs.getBoolean("matching", false),
                simplify = prefs.getBoolean("simplify", true),
                color =
                    RouteColorMode.entries.firstOrNull { it.name == prefs.getString("color", "") }
                        ?: RouteColorMode.PLAIN,
            )
        )
        private set

    fun set(options: RouteDisplayOptions) {
        value = options
        prefs
            .edit()
            .putBoolean("confidence", options.confidence)
            .putBoolean("clean", options.cleanStops)
            .putBoolean("matching", options.matchRoads)
            .putBoolean("simplify", options.simplify)
            .putString("color", options.color.name)
            .apply()
    }
}

@Composable
fun rememberRouteStyle(): RouteStyleState {
    val context = LocalContext.current.applicationContext
    return remember(context) { RouteStyleState(context) }
}

@Composable
fun RouteStyleControls(
    options: RouteDisplayOptions,
    stats: RouteDisplayStats?,
    onChange: (RouteDisplayOptions) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Text(
        when (options.color) {
            RouteColorMode.PLAIN -> "خط أخضر للمسار المسجل"
            RouteColorMode.SPEED -> "السرعة: أزرق <30 · أخضر 30–80 · برتقالي ≥80 كم/س"
            RouteColorMode.ACCURACY -> "الدقة المقدّرة: أخضر ≤10م · ذهبي 10–25م · أحمر >25م"
            RouteColorMode.TIME ->
                "الزمن: أزرق ← تركوازي ← بنفسجي ← برتقالي؛ من بداية التسجيل إلى نهايته"
            RouteColorMode.STOPS -> "التوقفات المؤكدة برتقالي · بقية المسار أخضر"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "العلامات: أخضر للبداية · أزرق للنهاية · ذهبي للتوقف",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (options.confidence)
        Text(
            "متصل: دقة معلنة ≤25م · متقطع: دقة أضعف · فراغ: انقطاع تسجيل",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    if (options.matchRoads)
        Text(
            "مطابقة تقديرية للعرض فقط؛ لا تغيّر النقاط الأصلية أو المسافة. " +
                (stats?.matchingStatus ?: "جارٍ تجهيز الطرق المحلية…"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { open = !open }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (open) "إغلاق أدوات خط المسار" else "أدوات خط المسار")
        }
        FilterChip(
            options.inspect,
            { onChange(options.copy(inspect = !options.inspect)) },
            label = { Text("فحص النقاط") },
        )
    }
    Text(
        "اضغط على خط المسار لعرض تفاصيل المقطع بين نقطتين؛ كبّر الخريطة إذا لزم. التوقفات لها أولوية الضغط.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (options.inspect)
        Text(
            "الضغط على الخط يعرض تفاصيل المقطع. فعّل فحص النقاط للسحب المتتابع على المسار؛ اسحب بعيدًا عن الخط لتحريك الخريطة.",
            style = MaterialTheme.typography.bodySmall,
        )
    if (open)
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("طبقة ألوان المسار", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RouteColorMode.entries.forEach { mode ->
                        FilterChip(
                            options.color == mode,
                            { onChange(options.copy(color = mode)) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                RouteToggle("إظهار ثقة الخط", options.confidence) {
                    onChange(options.copy(confidence = it))
                }
                RouteToggle("تنظيف اهتزاز الوقوف", options.cleanStops) {
                    onChange(options.copy(cleanStops = it))
                }
                RouteToggle("تبسيط الخط حسب التكبير", options.simplify) {
                    onChange(options.copy(simplify = it))
                }
                RouteToggle("مطابقة تقديرية مع الطرق", options.matchRoads) {
                    onChange(options.copy(matchRoads = it))
                }
                Text(
                    "المطابقة محلية قرب المنطقة المعروضة. تُترك النقاط الملتبسة أو البعيدة عن الطرق كما سُجلت؛ ليست توجيهًا أو تحديدًا للمسار أو الجسر الصحيح.",
                    style = MaterialTheme.typography.bodySmall,
                )
                stats?.let {
                    Text(
                        "نقاط GPS: ${it.raw} · رؤوس الرسم: ${it.drawn}\nتنظيف عرض: ${it.cleaned} · مطابقة تقديرية: ${it.matched}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            onChange(
                                RouteDisplayOptions(
                                    confidence = false,
                                    cleanStops = false,
                                    matchRoads = false,
                                    simplify = false,
                                    color = RouteColorMode.PLAIN,
                                )
                            )
                        }
                    ) {
                        Text("عرض GPS الأصلي")
                    }
                    TextButton(onClick = { onChange(RouteDisplayOptions()) }) {
                        Text("العرض المحسّن")
                    }
                }
                Text(
                    "كل التغييرات هنا للعرض؛ تبقى البيانات الأصلية والتكلفة والتصدير محفوظة.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
}

@Composable
private fun RouteToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

@Composable
fun RoutePointDetails(
    value: RoutePointSelection,
    firstTime: Long,
    total: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val point = value.point
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "النقطة المسجلة ${value.index+1} من $total",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "إغلاق فحص النقطة") }
            }
            Text("الوقت: ${stamp(point.recordedAt,true)}")
            Text("من بداية المشوار: ${duration((point.recordedAt-firstTime).coerceAtLeast(0))}")
            Text(
                "السرعة المحفوظة: ${num(point.speed*3.6)} كم/س · الدقة المقدّرة: ±${num(point.accuracy)} م"
            )
            Text(
                "ارتفاع GPS المحفوظ: ${num(point.altitude)} م · الاتجاه المحفوظ: ${num(point.bearing)}°",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "الإحداثيات الأصلية: ${"%.6f".format(java.util.Locale.US,point.latitude)}, ${"%.6f".format(java.util.Locale.US,point.longitude)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (point.breakBefore || point.uncertainGap)
                Text(
                    "بداية جزء بعد انقطاع؛ لا توجد وصلة إلى النقطة السابقة.",
                    style = MaterialTheme.typography.bodySmall,
                )
            if (value.displayed.stopped)
                Text("ضمن توقف مسجل", style = MaterialTheme.typography.bodySmall)
            value.displayed.match?.let { match ->
                Text(
                    "عرض تقديري على ${match.name.ifBlank { "طريق محلي" }} · إزاحة ${num(match.offsetMeters)} م عن موضع العرض قبل المطابقة",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row {
                TextButton(onClick = { onSelect(value.index - 1) }, enabled = value.index > 0) {
                    Text("النقطة السابقة")
                }
                TextButton(
                    onClick = { onSelect(value.index + 1) },
                    enabled = value.index < total - 1,
                ) {
                    Text("النقطة التالية")
                }
            }
        }
    }
}
