package sa.rihla.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import sa.rihla.audio.*
import sa.rihla.audio.studio.*

@Composable
fun FeatureRow(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            ) {
                Icon(
                    icon,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronLeft, null)
        }
    }
}

@Composable
fun AssistantScreen(
    audio: AudioViewModel = viewModel(),
    vm: AssistantViewModel = viewModel(),
    voiceOnly: Boolean = false,
) {
    val context = LocalContext.current
    val profile by vm.profile.collectAsStateWithLifecycle()
    val reply by vm.reply.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val processing by StudioTasks.busy.collectAsStateWithLifecycle()
    val progress by StudioTasks.state.collectAsStateWithLifecycle()
    val executed by vm.executed.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    val currentInput =
        rememberUpdatedState<(String) -> Unit>({
            input = it
            listening = false
            vm.ask(it)
        })
    val voice =
        remember(context) {
            VoiceCommands(
                context,
                {
                    voiceStatus = it
                    if (!it.contains("أستمع") && !it.startsWith("تجهيز")) listening = false
                },
                { currentInput.value(it) },
            )
        }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, voice) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                voice.close()
                listening = false
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            voice.close()
        }
    }
    fun listen() {
        audio.pause()
        listening = true
        voice.start(profile.networkVoice)
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                listen()
            else {
                voiceStatus = "يمكنك كتابة الأمر دون إذن الميكروفون"
                listening = false
            }
        }
    LaunchedEffect(reply, profile.speak) {
        if (profile.speak && reply != null) voice.speak(reply!!.reply)
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(profile.name, style = MaterialTheme.typography.headlineSmall)
            Text("نموذج أوامر خاص بالتطبيق يعمل فورًا على الهاتف. راجع الاقتراح ثم نفّذه.")
        }
        if (!voiceOnly) {
            item {
                FeatureRow(
                    "نماذج الذكاء الاصطناعي",
                    "النموذج المدمج، Qwen، وفصل الموسيقى",
                    Icons.Default.Memory,
                ) {
                    vm.work { AssistantNavigation.requests.emit("models") }
                }
            }
            item {
                FeatureRow(
                    "تخصيص مساعدي وتدريب نموذجي",
                    "اسمك المفضل وتعليماتك وأمثلة أوامرك",
                    Icons.Default.Tune,
                ) {
                    vm.work { AssistantNavigation.requests.emit("assistant_profile") }
                }
            }
        }
        item {
            ElevatedCard {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (voiceOnly) "الأوامر الصوتية" else "اكتب أو تحدث",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "اضغط الميكروفون لأمر واحد. لا يوجد استماع مستمر. التعرف على الجهاز متاح حسب خدمة الهاتف واللغة المثبتة.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            if (listening) {
                                voice.stop()
                                listening = false
                                voiceStatus = "توقف الاستماع"
                            } else if (
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                            )
                                listen()
                            else permission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (listening) "إيقاف الميكروفون" else "تحدث بأمر صوتي")
                    }
                    if (voiceStatus.isNotBlank())
                        Text(voiceStatus, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        input,
                        { input = it.take(1200) },
                        label = { Text("أمرك للمساعد") },
                        placeholder = { Text("افتح الخريطة، وقف الصوت، ابحث عن محاضرة") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Button(
                        onClick = {
                            voice.close()
                            listening = false
                            vm.ask(input)
                        },
                        enabled = input.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("فهم الأمر")
                    }
                    if (busy || processing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (processing) progress.stage else "جارٍ فهم الأمر محليًا")
                    }
                    if (processing)
                        TextButton(onClick = { StudioTasks.cancel(context) }) {
                            Text("إلغاء طلب النموذج")
                        }
                }
            }
        }
        if (reply != null)
            item {
                ElevatedCard {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("مراجعة الاقتراح", style = MaterialTheme.typography.titleLarge)
                        Text(reply!!.reply)
                        Text(source, style = MaterialTheme.typography.labelSmall)
                        if (reply!!.action != "none")
                            Button(
                                onClick = {
                                    voice.close()
                                    vm.execute(audio)
                                },
                                enabled = !executed && !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (executed) "تم تنفيذ الأمر" else "تنفيذ الاقتراح")
                            }
                        TextButton(onClick = { voice.speak(reply!!.reply) }) {
                            Icon(Icons.Default.VolumeUp, null)
                            Text("قراءة الرد بصوت عربي محلي")
                        }
                    }
                }
            }
        if (message.isNotBlank())
            item {
                Text(message, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { vm.message.value = "" }) { Text("إخفاء الرسالة") }
            }
        items(results, key = { it.id }) { track ->
            FeatureRow(track.title, track.artist, Icons.Default.PlayArrow) {
                voice.close()
                audio.play(track, results)
            }
        }
        item {
            FeatureRow(
                "إعدادات الأوامر الصوتية",
                "اللغة العربية، إذن الميكروفون، وقراءة الردود",
                Icons.Default.SettingsVoice,
            ) {
                vm.work { AssistantNavigation.requests.emit("assistant_profile") }
            }
        }
        item {
            Text(
                "جرّب: «افتح النماذج»، «عرض المصروفات»، «المقطع التالي»، «وضح الكلام». عباراتك الخاصة تُضاف من تخصيص مساعدي.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun PersonalAssistantScreen(vm: AssistantViewModel = viewModel()) {
    val context = LocalContext.current
    val profile by vm.profile.collectAsStateWithLifecycle()
    val examples by vm.examples.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var name by rememberSaveable(profile.name) { mutableStateOf(profile.name) }
    var instructions by
        rememberSaveable(profile.instructions) { mutableStateOf(profile.instructions) }
    var notes by rememberSaveable(profile.notes) { mutableStateOf(profile.notes) }
    var phrase by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf("map") }
    var menu by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("") }
    val voice = remember(context) { VoiceCommands(context, { voiceStatus = it }, {}) }
    DisposableEffect(voice) { onDispose { voice.close() } }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) {
            it?.let(vm::export)
        }
    val restore =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(vm::restore)
        }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("مساعد خاص بك", style = MaterialTheme.typography.headlineSmall)
            Text(
                "نموذج تصنيف أوامر صغير يتدرّب داخل الهاتف على عبارات التطبيق وأمثلتك. تخصيص الاسم والتعليمات والملاحظات يُستخدم مع Qwen عند تنزيله؛ أوزان Qwen الأساسية تبقى كما هي."
            )
            OutlinedTextField(
                name,
                { name = it.take(50) },
                label = { Text("اسم مساعدي") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                instructions,
                { instructions = it.take(600) },
                label = { Text("طريقة الرد المفضلة") },
                placeholder = { Text("مثال: أجب باختصار وبلهجة سعودية") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                notes,
                { notes = it.take(1000) },
                label = { Text("ملاحظات يعرفها مساعدي") },
                placeholder = { Text("أسماء المحاضرين وتسمياتك المفضلة") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Button(
                onClick = {
                    vm.save(profile.copy(name = name, instructions = instructions, notes = notes))
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("حفظ تخصيص مساعدي")
            }
        }
        item {
            HorizontalDivider()
            Text("تدريب نموذجي على عباراتي", style = MaterialTheme.typography.titleLarge)
            Text(
                "اكتب عبارة، واختر الأمر الذي تقصده. التدريب سريع ومحلي ولا يحتاج تنزيلًا. البيانات المالية ومساراتك لا تدخل ملف التدريب."
            )
            OutlinedTextField(
                phrase,
                { phrase = it.take(160) },
                label = { Text("عبارة جديدة للتدريب") },
                placeholder = { Text("مثال: ورني الديرة") },
                modifier = Modifier.fillMaxWidth(),
            )
            Box {
                OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("الأمر المقصود: " + AppCommands.all.first { it.id == selected }.title)
                }
                DropdownMenu(menu, { menu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                    AppCommands.all.forEach { intent ->
                        DropdownMenuItem(
                            text = { Text(intent.title) },
                            onClick = {
                                selected = intent.id
                                menu = false
                            },
                        )
                    }
                }
            }
            Button(
                onClick = {
                    vm.teach(phrase, selected)
                    phrase = ""
                },
                enabled = phrase.trim().length >= 2 && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("إضافة المثال وتدريب نموذجي")
            }
            Text(
                "${examples.size} مثال شخصي · ${AppCommands.all.sumOf {it.examples.size}} مثال مدمج",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(examples, key = { it.text }) { row ->
            OutlinedCard {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(row.text)
                    Text(
                        AppCommands.all.first { it.id == row.intent }.title,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { vm.forget(row.text) }, enabled = !busy) {
                        Text("حذف مثال التدريب")
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Text("الصوت والخصوصية", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(profile.networkVoice, { vm.save(profile.copy(networkVoice = it)) })
                Text("السماح بالتعرف الصوتي عبر الإنترنت", Modifier.weight(1f))
            }
            Text(
                "مع تعطيل الخيار، يُستخدم التعرف على الجهاز فقط. بتفعيله، قد تعالج خدمة صوت الهاتف كلامك عبر الإنترنت وفق إعداداتها. لا يُفعّل الميكروفون إلا عند الضغط.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(profile.speak, { vm.save(profile.copy(speak = it)) })
                Text("قراءة ردود المساعد بصوت محلي", Modifier.weight(1f))
            }
            OutlinedButton(onClick = voice::downloadLanguage, modifier = Modifier.fillMaxWidth()) {
                Text("طلب تنزيل لغة التعرف العربية")
            }
            if (voiceStatus.isNotBlank()) Text(voiceStatus)
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                    }
                        .onFailure { voiceStatus = "إعدادات صوت الهاتف غير متاحة من هذا الاختصار" }
                }
            ) {
                Text("فتح إعدادات صوت الهاتف")
            }
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                    }
                        .onFailure {
                            voiceStatus = "افتح إعدادات تحويل النص إلى كلام من إعدادات الهاتف"
                        }
                }
            ) {
                Text("إعدادات الصوت العربي لقراءة الردود")
            }
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
                }
            ) {
                Text("إعدادات إذن الميكروفون للتطبيق")
            }
        }
        item {
            HorizontalDivider()
            Text("ملف نموذجي الشخصي", style = MaterialTheme.typography.titleLarge)
            Text(
                "يتضمن اسم المساعد وتعليماتك وملاحظاتك وأمثلة التدريب ومعاملات نموذج الأوامر. لا يتضمن أوزان Qwen أو ملفات الصوت."
            )
            Button(
                onClick = { export.launch("mashwari-my-assistant.json") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("تصدير نموذجي وتخصيصي")
            }
            OutlinedButton(
                onClick = { restore.launch(arrayOf("application/json", "text/plain")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("استيراد نموذجي الشخصي")
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        }
    }
}
