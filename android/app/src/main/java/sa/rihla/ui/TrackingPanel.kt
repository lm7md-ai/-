package sa.rihla.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import sa.rihla.tracking.*

@Composable
@android.annotation.SuppressLint("MissingPermission")
fun TrackingOptions(vm: JourneyViewModel, data: ScreenData) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            refresh++
            list = it
        }
    Panel("auto_tracking") {
        Text("التسجيل الذكي وجودة التتبع", style = MaterialTheme.typography.titleLarge)
        Row {
            Text("دقة أعلى أثناء الحركة", Modifier.weight(1f))
            Switch(data.settings.preciseTracking, { vm.precise(it) })
        }
        Text(
            "الوضع المتوازن يحافظ على 5 ثوانٍ أثناء الحركة و30 عند التوقف. الدقة الأعلى تطلب 3 ثوانٍ أثناء الحركة و15 عند التوقف لتحسين الاستئناف، وقد تزيد استهلاك البطارية.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "بلوتوث السيارة: " +
                if (data.settings.bluetoothAddress.isBlank()) "غير محدد" else "جهاز مقترن محدد"
        )
        Text(
            "اقرن الهاتف بالسيارة من إعدادات النظام أولًا. اتصال الجهاز المحدد يبدأ فحص الحركة، ولا يسجل مشوارًا حتى تتأكد حركة القيادة. يلزم تفعيل التسجيل التلقائي وموقع الخلفية؛ فصل البلوتوث وحده لا ينهي الرحلة.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = {
                if (CarBluetoothReceiver.allowed(context)) list = !list
                else if (Build.VERSION.SDK_INT >= 31)
                    permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        ) {
            Text("اختيار بلوتوث السيارة")
        }
        if (list && refresh >= 0 && CarBluetoothReceiver.allowed(context)) {
            val paired = runCatching {
                context
                    .getSystemService(BluetoothManager::class.java)
                    ?.adapter
                    ?.bondedDevices
                    ?.toList()
                    .orEmpty()
            }
                .getOrDefault(emptyList())
            if (paired.isEmpty()) Text("لا توجد أجهزة مقترنة؛ افتح إعدادات بلوتوث وأقرن السيارة.")
            paired.forEach { device ->
                val address = runCatching { device.address }.getOrNull()
                val name = runCatching { device.name ?: "جهاز بلوتوث" }.getOrDefault("جهاز بلوتوث")
                TextButton(
                    enabled = address != null,
                    onClick = {
                        address?.let(vm::bluetooth)
                        list = false
                    },
                ) {
                    Text(name)
                }
            }
        }
        if (data.settings.bluetoothAddress.isNotBlank())
            TextButton(onClick = { vm.bluetooth("") }) { Text("إلغاء ربط السيارة") }
        val health by LiveTrackingHealth.state.collectAsState()
        if (data.active != null) {
            Text(
                if (health.receivedAt == 0L) "بانتظار إشارة الموقع"
                else "آخر إشارة: " + stamp(health.receivedAt) + " · " + health.provider
            )
            if (health.receivedAt > 0)
                Text(
                    "دقة الإشارة: " +
                        num(health.accuracy, 1) +
                        " متر · " +
                        if (health.accepted) "مقبولة" else "لم تضف نقطة إلى المسار"
                )
        }
        Text(
            "يستبعد التطبيق الإشارات القديمة والوهمية والقفزات غير المعقولة، ويحاول GPS المباشر عند تعثر مزود الموقع. راجع أذونات الخلفية واستثناء البطارية في RedMagic.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
