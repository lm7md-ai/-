package sa.rihla.audio.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import sa.rihla.audio.*

@Composable
fun StudioScreen(audio: AudioViewModel, onBack: () -> Unit, vm: StudioViewModel = viewModel()) {
    val catalog by audio.catalog.collectAsStateWithLifecycle()
    val playback by AudioLive.state.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val state by StudioTasks.state.collectAsStateWithLifecycle()
    val busy by StudioTasks.busy.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val filings by vm.filings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("lecture") }
    var selected by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var question by rememberSaveable { mutableStateOf("") }
    var metered by rememberSaveable { mutableStateOf(false) }
    var physical by rememberSaveable { mutableStateOf(false) }
    var approvedMove by remember { mutableStateOf(false) }
    var scene by rememberSaveable { mutableStateOf("lecture_soft") }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var licenses by remember { mutableStateOf(false) }
    var executed by remember(state.reply) { mutableStateOf(false) }
    val tree =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let { uri -> vm.apply(uri) }
        }
    val picked =
        catalog.tracks.find { it.id == selected } ?: catalog.tracks.find { it.id == playback.id }
    LaunchedEffect(picked?.id) {
        picked?.id?.let { id -> vm.app.audio.dao.tuning(id)?.let { scene = it.scene } }
    }
    val locked = busy || editing
    val shown =
        catalog.tracks.filter {
            query.isBlank() ||
                listOf(it.title, it.artist, it.album).any { v -> v.contains(query, true) }
        }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("العودة إلى المشغل") }
            Text("استوديو المحاضرات", style = MaterialTheme.typography.headlineSmall)
            Text("تحسين الاستماع · مساعد على الهاتف · تنظيم الملفات · فصل الموسيقى")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                        "lecture" to "تحسين الصوت",
                        "assistant" to "المساعد",
                        "organize" to "تنظيم",
                        "folders" to "المجلدات",
                        "separate" to "فصل الموسيقى",
                        "models" to "النماذج",
                    )
                    .forEach { (key, label) ->
                        FilterChip(tab == key, { tab = key }, label = { Text(label) })
                    }
            }
            if (state.stage.isNotBlank()) {
                Text(state.stage, color = MaterialTheme.colorScheme.primary)
                if (busy) {
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { StudioTasks.cancel(vm.app) }) { Text("إلغاء المعالجة") }
                }
            }
            if (message.isNotBlank()) {
                Text(message)
                TextButton(onClick = { vm.message.value = "" }) { Text("إخفاء") }
            }
        }
        if (tab == "models") {
            item {
                Text(
                    "التنزيل أول مرة يحتاج إنترنت؛ التشغيل لاحقًا محلي. لا تُرفع ملفاتك إلى خدمة ذكاء اصطناعي."
                )
                Row {
                    Checkbox(metered, { metered = it }, enabled = !locked)
                    Text("السماح بالتنزيل عبر بيانات الهاتف")
                }
                TextButton(onClick = { licenses = true }) { Text("مصادر المحركات والتراخيص") }
                Text(
                    "المساعد يحتاج نحو 1.6 جيجابايت للتنزيل وذاكرة متاحة نحو 2.5 جيجابايت. محرك CPU بأربع خيوط، ويُغلق بعد الطلب. يناسب ذاكرة هاتفك مبدئيًا؛ السرعة تعتمد على الجهاز."
                )
            }
            items(LocalModels.all, key = { it.id }) { model ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            when (model.id) {
                                "assistant" -> "مساعد Qwen2.5 · 1.5B"
                                "vocals" -> "Spleeter · نموذج الكلام"
                                else -> "Spleeter · نموذج المصاحبة"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${model.size/1_000_000} ميجابايت · ${statuses[model.id]?.state ?: "فحص الحالة"}"
                        )
                        LinearProgressIndicator(
                            progress = { (statuses[model.id]?.progress ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row {
                            Button(
                                onClick = { vm.download(model, metered) },
                                enabled = !locked && statuses[model.id]?.ready != true,
                            ) {
                                Text("تنزيل")
                            }
                            TextButton(onClick = { vm.remove(model) }, enabled = !locked) {
                                Text("إلغاء التنزيل / إزالة النموذج")
                            }
                        }
                    }
                }
            }
        }
        if (tab in setOf("lecture", "separate")) {
            item {
                Text(
                    "المقطع المحدد: ${picked?.title ?: "اختر من القائمة أدناه"}",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (tab == "lecture") {
                    Text(
                        "تخفيف الحدة يقلل الترددات المزعجة، ووضوح الكلام يخفف الرنين المنخفض ويرفع الكلام الهادئ باعتدال. المعالجة أثناء الاستماع؛ الملف الأصلي لا يتغير."
                    )
                    listOf(AudioScene.LECTURE_SOFT, AudioScene.LECTURE_CLEAR, AudioScene.NATURAL)
                        .forEach { preset ->
                            FilterChip(
                                scene == preset.key,
                                {
                                    scene = preset.key
                                    audio.scene(preset)
                                },
                                label = { Text(preset.label) },
                            )
                        }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Button(
                            onClick = {
                                picked?.let {
                                    audio.play(
                                        it,
                                        listOf(it),
                                        listeningScene =
                                            AudioScene.entries.first { it.key == scene },
                                    )
                                }
                            },
                            enabled = picked != null && !locked,
                        ) {
                            Text("استماع ومقارنة")
                        }
                        TextButton(
                            onClick = {
                                picked?.let {
                                    vm.saveTuning(
                                        it.id,
                                        AudioScene.entries.first { it.key == scene },
                                    )
                                }
                            },
                            enabled = picked != null && !locked,
                        ) {
                            Text("حفظ لهذا المقطع")
                        }
                        TextButton(onClick = audio::toggle) {
                            Text(if (playback.playing) "إيقاف مؤقت" else "متابعة")
                        }
                    }
                    Text(
                        "لن يعيد كلمات مفقودة أو يصلح تسجيلًا تالفًا. جرّب الملفين واختر الأريح لصوت المحاضر.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "ينتج ملف M4A جديدًا للكلام ويضيفه للمكتبة. يلزم نموذجا Spleeter (حوالي 40 ميجابايت). قد تبقى آثار موسيقى أو تتأثر بعض الكلمات، وقد تستغرق المحاضرات الطويلة وقتًا وتستهلك البطارية."
                    )
                    Button(
                        onClick = { picked?.let { vm.start("separate", it.id) } },
                        enabled =
                            picked != null &&
                                !locked &&
                                statuses["vocals"]?.ready == true &&
                                statuses["accompaniment"]?.ready == true,
                    ) {
                        Text("إنشاء نسخة دون موسيقى")
                    }
                    TextButton(onClick = { tab = "models" }) { Text("تنزيل نماذج الفصل") }
                }
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("بحث لاختيار المقطع") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(shown, key = { it.id }) { track ->
                OutlinedCard(
                    onClick = { selected = track.id },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text((if (picked?.id == track.id) "✓ " else "") + track.title)
                        Text(track.artist, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (tab == "assistant") {
            item {
                Text(
                    "ينفذ أوامر التشغيل والبحث والتنقل بعد مراجعتك. يطلع على أعداد المشاوير والمصاريف وعناوين أول 20 مقطعًا، ولا يعدّل سجلاتك المالية أو يحذف ملفاتك."
                )
                OutlinedTextField(
                    question,
                    { question = it.take(1200) },
                    label = { Text("مثال: شغّل محاضرة، افتح الخريطة، نظّم المكتبة") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.start("ask", question) },
                    enabled =
                        !locked && question.isNotBlank() && statuses["assistant"]?.ready == true,
                ) {
                    Text("إرسال إلى المساعد المحلي")
                }
                TextButton(onClick = { tab = "models" }) { Text("تنزيل نموذج المساعد") }
                state.reply?.let { reply ->
                    Text(reply.reply, style = MaterialTheme.typography.titleMedium)
                    if (reply.action != "none") {
                        Text("الإجراء المقترح: ${actionLabel(reply.action)} · ${reply.value}")
                        Button(
                            onClick = {
                                vm.work {
                                    when (reply.action) {
                                        "navigate" -> AssistantNavigation.requests.emit(reply.value)
                                        "scene" ->
                                            audio.scene(
                                                AudioScene.entries.first { it.key == reply.value }
                                            )
                                        "pause_audio" -> audio.pause()
                                        "next_audio" -> audio.next()
                                        "previous_audio" -> audio.previous()
                                        "search_audio" -> {
                                            query = reply.value
                                            tab = "lecture"
                                        }
                                        "play_audio",
                                        "favorite_audio" -> {
                                            val track =
                                                vm.app.audio.dao.track(reply.value)
                                                    ?: error("المقطع لم يعد موجودًا")
                                            if (reply.action == "play_audio")
                                                audio.play(track, listOf(track))
                                            else if (!track.favorite) audio.favorite(track.id)
                                        }
                                        "organize" -> {
                                            tab = "organize"
                                            vm.preliminary(catalog.tracks)
                                        }
                                    }
                                    executed = true
                                }
                            },
                            enabled = !locked && !executed,
                        ) {
                            Text(if (executed) "تم تنفيذ الإجراء" else "تنفيذ الاقتراح")
                        }
                    }
                }
            }
        }
        if (tab == "organize") {
            item {
                Text(
                    "التصنيف حسب الموضوع والمصدر من العنوان والفنان والألبوم، ولا يستمع النموذج إلى محتوى المحاضرة. راجع وعدّل الاقتراحات قبل تطبيقها."
                )
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    OutlinedButton(
                        onClick = { vm.preliminary(catalog.tracks) },
                        enabled = !locked,
                    ) {
                        Text("خطة أولية دون نموذج")
                    }
                    Button(
                        onClick = { vm.start("classify") },
                        enabled =
                            !locked &&
                                catalog.tracks.isNotEmpty() &&
                                statuses["assistant"]?.ready == true,
                    ) {
                        Text("اقتراحات المساعد")
                    }
                }
                FilterChip(
                    !physical,
                    { physical = false },
                    label = { Text("مجلدات داخل المكتبة") },
                    enabled = !locked,
                )
                FilterChip(
                    physical,
                    { physical = true },
                    label = { Text("نقل الملفات في الهاتف") },
                    enabled = !locked,
                )
                Text(
                    if (physical)
                        "سيُنسخ كل ملف إلى الموضوع / المصدر، ثم يُتحقق من بصمته ويُحدّث رابطه، وبعدها يُحذف الأصل فقط إن سمح مزود الملفات. عند منع الحذف تبقى النسختان."
                    else "يُحفظ التصنيف داخل التطبيق، وتبقى الملفات في مواقعها."
                )
                Button(
                    onClick = { if (physical) approvedMove = true else vm.apply(null) },
                    enabled = plan.isNotEmpty() && !locked,
                ) {
                    Text(
                        if (physical) "مراجعة النقل واختيار الوجهة"
                        else "تطبيق التصنيف (${plan.size})"
                    )
                }
            }
            itemsIndexed(plan, key = { _, row -> row.trackId }) { index, row ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            catalog.tracks.find { it.id == row.trackId }?.title ?: "مقطع غير موجود"
                        )
                        OutlinedTextField(
                            row.topic,
                            { vm.edit(index, it, row.source) },
                            label = { Text("الموضوع") },
                            enabled = !locked,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            row.source,
                            { vm.edit(index, row.topic, it) },
                            label = { Text("المصدر / المحاضر") },
                            enabled = !locked,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        if (tab == "folders") {
            item {
                Text("الموضوع ← المصدر", style = MaterialTheme.typography.titleMedium)
                if (folders.isEmpty()) Text("نظّم المكتبة أولًا لإنشاء مجلدات الموضوع والمصدر.")
                if (folder != null) TextButton(onClick = { folder = null }) { Text("كل المجلدات") }
            }
            if (folder == null)
                items(folders, key = { it.id }) { f ->
                    OutlinedCard(onClick = { folder = f.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("${f.topic} / ${f.source}")
                            Text("${filings.count { it.folderId==f.id }} مقطع")
                        }
                    }
                }
            else {
                val members = filings.filter { it.folderId == folder }.map { it.trackId }.toSet()
                val tracks = catalog.tracks.filter { it.id in members }
                items(tracks, key = { it.id }) { track ->
                    OutlinedCard(
                        onClick = { audio.play(track, tracks) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(track.title, Modifier.padding(14.dp))
                    }
                }
            }
        }
    }
    if (licenses) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val notice = remember {
            context.resources.openRawResource(sa.rihla.R.raw.studio_licenses).bufferedReader().use {
                it.readText()
            }
        }
        AlertDialog(
            onDismissRequest = { licenses = false },
            title = { Text("التراخيص مفتوحة المصدر") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        notice,
                        Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text("إغلاق") } },
        )
    }
    if (approvedMove)
        AlertDialog(
            onDismissRequest = { approvedMove = false },
            title = { Text("نقل ${plan.size} ملفًا") },
            text = {
                Text(
                    "اختر مجلد وجهة مختلفًا عن ملفاتك الأصلية. سيُنقل كل ملف بعد التحقق من نسخته، وقد يبقى الأصل إذا لم يمنح Android إذن حذفه. تأكد من صحة المواضيع والمصادر في الخطة."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        approvedMove = false
                        tree.launch(null)
                    }
                ) {
                    Text("اختيار الوجهة")
                }
            },
            dismissButton = { TextButton(onClick = { approvedMove = false }) { Text("رجوع") } },
        )
}

private fun actionLabel(action: String) =
    when (action) {
        "navigate" -> "فتح الصفحة"
        "scene" -> "إعداد الصوت"
        "search_audio" -> "بحث في المكتبة"
        "play_audio" -> "تشغيل المقطع"
        "favorite_audio" -> "إضافة للمفضلة"
        "pause_audio" -> "إيقاف مؤقت"
        "next_audio" -> "المقطع التالي"
        "previous_audio" -> "المقطع السابق"
        "organize" -> "فتح خطة التصنيف"
        else -> action
    }
