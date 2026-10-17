package sa.rihla.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import sa.rihla.audio.studio.*

@Composable
fun ModelCenterScreen(vm: StudioViewModel = viewModel()) {
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val busy by StudioTasks.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var metered by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ModelFile?>(null) }
    var license by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val store = remember { PersonalAssistantStore(context) }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("مركز النماذج", style = MaterialTheme.typography.headlineSmall)
            Text(
                "اختر ما تحتاجه. نموذج أوامرك مدمج ويعمل فورًا، وبقية النماذج تنزيلات اختيارية مستقلة."
            )
        }
        item {
            ElevatedCard {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Text("مشواري · نموذج أوامري", style = MaterialTheme.typography.titleLarge)
                    Text("جاهز دون تنزيل · يعمل على الهاتف")
                    Text(
                        "يتعلم عباراتك لأوامر التطبيق بتصنيف إحصائي خفيف. ${store.examples().size} مثال شخصي. يمكن تصدير نموذجك واستيراده."
                    )
                    Button(
                        onClick = {
                            vm.work { AssistantNavigation.requests.emit("assistant_profile") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("تخصيص وتدريب نموذجي")
                    }
                }
            }
        }
        item {
            FeatureRow(
                "التعرف على الأوامر الصوتية",
                "لغة عربية من خدمة الهاتف؛ الإعداد والتنزيل من صفحة تخصيص مساعدي",
                Icons.Default.Mic,
            ) {
                vm.work { AssistantNavigation.requests.emit("voice") }
            }
        }
        item {
            Text("نماذج قابلة للتنزيل", style = MaterialTheme.typography.titleLarge)
            Row {
                Checkbox(metered, { metered = it }, enabled = !busy)
                Text("السماح بالتنزيل عبر بيانات الهاتف")
            }
            Text(
                "التنزيل عبر Wi-Fi افتراضيًا. تظهر الجاهزية بعد اكتمال فحص الملف. يعمل Qwen وفصل الموسيقى محليًا بعد التنزيل.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(LocalModels.all, key = { it.id }) { model ->
            ElevatedCard {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        when (model.id) {
                            "assistant" -> "Qwen · مساعد الأسئلة والتصنيف"
                            "vocals" -> "فصل الموسيقى · نموذج الكلام"
                            else -> "فصل الموسيقى · نموذج المصاحبة"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (model.id == "assistant")
                            "1.6 جيجابايت · Qwen2.5 1.5B · نحو 2.5 جيجابايت ذاكرة متاحة عند التشغيل"
                        else "نحو 20 ميجابايت · يلزم نموذجا الفصل معًا"
                    )
                    Text(
                        statuses[model.id]?.state ?: "فحص الحالة",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (statuses[model.id]?.ready != true)
                        LinearProgressIndicator(
                            progress = { (statuses[model.id]?.progress ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    Button(
                        onClick = { vm.download(model, metered) },
                        enabled = !busy && statuses[model.id]?.ready != true,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (statuses[model.id]?.ready == true) "جاهز للاستخدام"
                            else "تنزيل النموذج"
                        )
                    }
                    OutlinedButton(
                        onClick = { removing = model },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("إدارة التنزيل أو إزالة النموذج")
                    }
                }
            }
        }
        item {
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
            Text(
                "أوزان Qwen العامة مستقلة عن نموذج أوامرك الشخصي. تخصيص تعليمات Qwen لا يعني إعادة تدريب أوزانه. سرعة النماذج وجودة فصل المحاضرات تحتاجان تجربة على جهازك.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { license = true }) { Text("مصادر النماذج والتراخيص") }
        }
    }
    removing?.let { model ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("إدارة ${if(model.id=="assistant")"Qwen" else "نموذج فصل الموسيقى"}") },
            text = {
                Text(
                    "يلغي التنزيل ويحذف ملف هذا النموذج. يمكن تنزيله مجددًا؛ لا تُحذف المحاضرات أو نموذج أوامرك الشخصي."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.remove(model)
                        removing = null
                    }
                ) {
                    Text("إلغاء وإزالة النموذج")
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("رجوع") } },
        )
    }
    if (license) {
        val text = remember {
            context.resources.openRawResource(sa.rihla.R.raw.studio_licenses).bufferedReader().use {
                it.readText()
            }
        }
        AlertDialog(
            onDismissRequest = { license = false },
            title = { Text("المصادر والتراخيص") },
            text = {
                Text(text, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = { license = false }) { Text("إغلاق") } },
        )
    }
}
