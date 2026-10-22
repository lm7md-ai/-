package sa.rihla.map

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import sa.rihla.core.*
import sa.rihla.data.*
import sa.rihla.ui.*

@Stable
class SectionSelectionState {
    var anchor by mutableStateOf<Int?>(null)
    var end by mutableStateOf<Int?>(null)
    var comparison by mutableStateOf<RouteSection?>(null)

    fun reset() {
        anchor = null
        end = null
    }

    fun range(value: RoutePointSelection?): IntRange? {
        value ?: return null
        val a = anchor
        val b = end ?: value.index
        if (a != null) return minOf(a, b)..maxOf(a, b)
        return value.segmentEnd?.let { (it - 1)..it } ?: (value.index..value.index)
    }
}

@Composable
fun rememberRouteAnalysis(points: List<RoutePoint>, stops: List<StopDetails>): RouteAnalysis? {
    val result by
        produceState<RouteAnalysis?>(null, points, stops) {
            value = null
            value =
                withContext(Dispatchers.Default) {
                    val current = currentCoroutineContext()
                    RouteAnalysis(points, stops) { current.ensureActive() }
                }
        }
    // Never display the previous trip's summary while its replacement is preparing.
    return result?.takeIf { it.points === points }
}

fun sectionShareText(section: RouteSection): String = buildString {
    val format =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(SaudiZone)
    fun date(time: Long) = format.format(java.time.Instant.ofEpochMilli(time))
    appendLine("ملخص جزء من مشواري")
    appendLine("من ${date(section.start.recordedAt)} إلى ${date(section.end.recordedAt)}")
    appendLine(
        "المدة: ${durationParts(section.elapsedMs).let { "${it.hours.toString().padStart(2,'0')}:${it.minutes.toString().padStart(2,'0')}:${it.seconds.toString().padStart(2,'0')}" }}"
    )
    appendLine("المسافة المحفوظة: ${"%.1f".format(java.util.Locale.US,section.recordedMeters)} م")
    appendLine(
        "المسافة الهندسية من نقاط GPS الموصولة: ${"%.1f".format(java.util.Locale.US,section.gpsMeters)} م"
    )
    appendLine("التوقف: ${section.stoppedMs/1000} ثانية · الانقطاعات: ${section.gaps}")
    append(
        "المسافة والسرعة مبنيتان على التسجيل، وليستا قياسًا لطريق مؤكد. لا يتضمن هذا الملخص إحداثيات."
    )
}

@Composable
fun RouteSectionInspector(
    analysis: RouteAnalysis?,
    value: RoutePointSelection,
    trip: Outing?,
    state: SectionSelectionState,
    camera: MapCamera,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var rawDetails by remember { mutableStateOf(false) }
    val range = state.range(value)
    val section = remember(analysis, range) { range?.let { analysis?.summary(it.first, it.last) } }
    if (section == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.anchor == null && section.points == 2) "تفاصيل المقطع بين النقطتين"
                    else "تفاصيل الجزء المحدد",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    onClick = {
                        state.reset()
                        onClose()
                    }
                ) {
                    Icon(Icons.Default.Close, "إغلاق تفاصيل المقطع")
                }
            }
            Text(
                "من النقطة ${section.from+1} إلى ${section.to+1} · ${section.points} نقطة",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "البداية: ${stamp(section.start.recordedAt,true)}\nالنهاية: ${stamp(section.end.recordedAt,true)}"
            )
            SectionMetrics(section)
            section.fuel(trip)?.let { fuel ->
                val liters =
                    if (fuel.minLiters == fuel.maxLiters) num(fuel.minLiters, 3)
                    else "${num(fuel.minLiters,3)}–${num(fuel.maxLiters,3)}"
                val cost =
                    if (fuel.minHalala == fuel.maxHalala) num(fuel.minHalala / 100.0, 2)
                    else "${num(fuel.minHalala/100.0,2)}–${num(fuel.maxHalala/100.0,2)}"
                Text(
                    "تقدير وقود المسافة المحفوظة: $liters لتر · $cost ر.س",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "وفق استهلاك وسعر الوقود المحفوظين مع الرحلة؛ لا يشمل استهلاك المحرك أثناء الوقوف.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (section.recordedMeters == 0.0 && section.gpsMeters > 10)
                Text(
                    "لم تُحفظ مساهمة مسافة لهذا الجزء؛ قد يحدث ذلك في بيانات قديمة أو عند استبعاد اهتزاز GPS. لا تستبدل المسافة الهندسية المسافة المحتسبة.",
                    style = MaterialTheme.typography.bodySmall,
                )
            if (section.gaps > 0)
                Text(
                    "يوجد انقطاع في هذا الجزء: استُبعدت وصلاته من المسافة والمتوسط، ولم تُخمن حركة السيارة خلاله.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = {
                        state.anchor = value.index
                        state.end = null
                    }
                ) {
                    Text("اجعل النقطة بداية الجزء")
                }
                if (state.anchor != null)
                    TextButton(onClick = { state.end = value.index }) { Text("تثبيت نهاية الجزء") }
                if (state.anchor != null)
                    TextButton(onClick = state::reset) { Text("إلغاء تحديد الجزء") }
            }
            if (state.anchor != null && state.end == null)
                Text(
                    "اختر نقطة ثانية بالضغط أو السحب على الخط، ثم ثبّت النهاية. يمكنك اختيارها قبل البداية أيضًا.",
                    style = MaterialTheme.typography.bodySmall,
                )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { camera.section(section.range) }) {
                    Text("احتواء الجزء على الخريطة")
                }
                TextButton(
                    onClick = { state.comparison = section },
                    enabled = section.to > section.from,
                ) {
                    Text("حفظ الجزء للمقارنة")
                }
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, sectionShareText(section)),
                                "مشاركة ملخص الجزء",
                            )
                        )
                    }
                ) {
                    Text("مشاركة ملخص دون إحداثيات")
                }
            }
            state.comparison?.let { saved ->
                HorizontalDivider()
                Text(
                    "مقارنة مع الجزء المحفوظ (${saved.from+1}–${saved.to+1})",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "المحفوظ: ${duration(saved.elapsedMs)} · ${num(saved.gpsMeters)} م GPS؛ الحالي: ${duration(section.elapsedMs)} · ${num(section.gpsMeters)} م GPS",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "فرق الزمن: ${signedDuration(section.elapsedMs-saved.elapsedMs)} · فرق مسافة GPS: ${signedMeters(section.gpsMeters-saved.gpsMeters)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "الموجب يعني أن الجزء الحالي أطول. الأجزاء قد تختلف في الطريق والمسافة؛ هذه ليست مقارنة ازدحام.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { state.comparison = null }) { Text("مسح المقارنة") }
            }
            TextButton(onClick = { rawDetails = !rawDetails }) {
                Text(
                    if (rawDetails) "إخفاء بيانات النقطة الأصلية"
                    else "بيانات النقطة الأصلية والارتفاع والاتجاه"
                )
            }
        }
    }
    if (rawDetails)
        RoutePointDetails(
            value,
            trip?.startedAt ?: analysis!!.points.first().recordedAt,
            analysis!!.points.size,
            { camera.inspect(it) },
            {
                state.reset()
                onClose()
            },
        )
}

@Composable
private fun signedDuration(delta: Long): String =
    (if (delta > 0) "+" else if (delta < 0) "−" else "") + duration(kotlin.math.abs(delta))

@Composable
private fun signedMeters(delta: Double): String =
    (if (delta > 0) "+" else if (delta < 0) "−" else "") + num(kotlin.math.abs(delta)) + " م"

@Composable
private fun SectionMetrics(value: RouteSection) {
    Text("المدة الكلية: ${duration(value.elapsedMs)}", style = MaterialTheme.typography.titleMedium)
    Text("المسافة المحتسبة من التسجيل: ${num(value.recordedMeters)} م")
    Text(
        "مسافة GPS الهندسية: ${num(value.gpsMeters)} م · الخط المستقيم بين الطرفين: ${num(value.directMeters)} م",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "متوسط سرعة GPS للوقت الموثق: ${value.averageGpsKmh?.let { num(it) }?:"غير متاح"} كم/س · أعلى سرعة محفوظة: ${num(value.maxSpeed*3.6)} كم/س",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "وقت موثق خارج التوقفات: ${duration(value.movingMs)} · توقف مسجل: ${duration(value.stoppedMs)} · ${value.stops} توقف",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "انقطاع تسجيل: ${duration(value.gapMs)} · ${value.gaps} فجوة",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "الدقة المعلنة: متوسط ±${num(value.averageAccuracy)} م · أسوأ ±${num(value.worstAccuracy)} م · ${value.weakPoints} نقطة بدقة أضعف من 25م",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun RouteSectionHighlights(
    analysis: RouteAnalysis?,
    state: SectionSelectionState,
    camera: MapCamera,
) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text(if (open) "إخفاء أبرز أجزاء المشوار" else "أبرز أجزاء المشوار والأجزاء الأبطأ")
    }
    if (open && analysis != null) {
        Text(
            "حسب التسجيل الحالي؛ لا تمثل هذه النتائج ازدحامًا مباشرًا.",
            style = MaterialTheme.typography.bodySmall,
        )
        analysis.slowSections.forEach { section ->
            TextButton(
                onClick = {
                    state.anchor = section.from
                    state.end = section.to
                    camera.inspect(section.to)
                    camera.section(section.range)
                }
            ) {
                Text(
                    "جزء أبطأ · ${num(section.gpsMeters/1000,2)} كم · ${duration(section.elapsedMs)} · ${duration((section.knownMs*1000/section.gpsMeters).toLong())} لكل كم"
                )
            }
        }
        listOf(
                "أعلى سرعة محفوظة" to analysis.peakIndex,
                "أضعف دقة معلنة" to analysis.weakestIndex,
                "أطول انقطاع تسجيل" to analysis.longestGapIndex,
            )
            .forEach { (label, index) ->
                if (index != null)
                    TextButton(
                        onClick = {
                            state.reset()
                            if (index == analysis.longestGapIndex && label == "أطول انقطاع تسجيل") {
                                state.anchor = index - 1
                                state.end = index
                            }
                            camera.inspect(index)
                        }
                    ) {
                        Text(label)
                    }
            }
    }
}
