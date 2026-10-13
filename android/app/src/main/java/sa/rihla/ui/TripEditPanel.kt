package sa.rihla.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import sa.rihla.data.Outing

@Composable
fun TripEditPanel(vm: JourneyViewModel, data: ScreenData, outing: Outing, splitTime: Long?) {
    if (outing.endedAt == null) return
    var expanded by remember { mutableStateOf(false) }
    var merge by remember { mutableStateOf<Long?>(null) }
    var splitting by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "إخفاء مراجعة التسجيل" else "مراجعة التسجيل: دمج أو تقسيم")
    }
    if (expanded) {
        Text(
            "احفظ نسخة احتياطية من المزيد قبل تعديل الرحلات. التعديل للمشاوير المنتهية فقط ويتم مع بياناتها في عملية واحدة.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("دمج مع المشوار التالي خلال ساعة، إذا كانت إعدادات تكلفة الوقود متطابقة:")
        val candidates =
            data.outings.filter {
                it.id != outing.id &&
                    it.endedAt != null &&
                    it.startedAt >= outing.endedAt &&
                    it.startedAt - outing.endedAt <= 3_600_000 &&
                    it.fuelPriceHalala == outing.fuelPriceHalala &&
                    it.consumptionMinMl == outing.consumptionMinMl &&
                    it.consumptionMaxMl == outing.consumptionMaxMl &&
                    it.fuelGrade == outing.fuelGrade
            }
        if (candidates.isEmpty()) Text("لا توجد مشاوير مؤهلة للدمج.")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            candidates.forEach { value ->
                TextButton(onClick = { merge = value.id }) { Text(stamp(value.startedAt)) }
            }
        }
        if (splitTime == null)
            Text("للتقسيم فعّل استعراض المسار زمنيًا واختر نقطة داخلية من الشريط.")
        else
            TextButton(onClick = { splitting = true }) {
                Text("تقسيم عند " + stamp(splitTime, seconds = true))
            }
    }
    if (merge != null || splitting)
        AlertDialog(
            onDismissRequest = {
                merge = null
                splitting = false
            },
            properties =
                androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            title = { Text(if (splitting) "تأكيد تقسيم المشوار" else "تأكيد دمج المشوارين") },
            text = {
                Text(
                    if (splitting)
                        "يحتفظ بالمسار ويقسم التوقفات ويربط المصاريف حسب وقتها، ويعيد حساب ملخص كل جزء. يصبح التصنيف «أخرى» ويمكن تعديله. تبقى ملاحظاتك والمفضلة في الجزأين."
                    else
                        "ينقل نقاط المشوار التالي وتوقفاته ومصاريفه إلى هذا المشوار ويحتفظ بالملاحظات. يظهر الفراغ بينهما كوقت غير موثّق دون رسم خط اتصال. لا يمكن التراجع تلقائيًا؛ استخدم النسخة الاحتياطية عند الحاجة."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (splitting && splitTime != null) vm.splitTrip(outing.id, splitTime)
                        else merge?.let { vm.mergeTrips(outing.id, it) }
                        merge = null
                        splitting = false
                    }
                ) {
                    Text("تأكيد التعديل")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        merge = null
                        splitting = false
                    }
                ) {
                    Text("إلغاء")
                }
            },
        )
}
