package sa.rihla.audio

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import sa.rihla.R

@Composable
fun AudioPlayerScreen(vm: AudioViewModel = viewModel()) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val state by AudioLive.state.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    if (state.id.isBlank()) {
        sa.rihla.ui.EmptyState(
            "اختر ما تستمع إليه",
            "افتح المكتبة ثم اختر مقطعًا لبدء التشغيل.",
            Icons.Default.Headphones,
        )
        TextButton(
            onClick = { sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit("audio") }
        ) {
            Text("فتح المكتبة")
        }
    } else
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
        ) {
            AudioNowPlaying(vm, state, catalog, connected) { track, at ->
                vm.play(track, listOf(track), at)
            }
        }
}

@Composable
private fun AudioNowPlaying(
    vm: AudioViewModel,
    state: AudioPlayback,
    catalog: AudioCatalog,
    connected: Boolean,
    playAt: (AudioTrack, Long) -> Unit,
) {
    var seek by remember(state.id) { mutableStateOf<Float?>(null) }
    var effects by rememberSaveable { mutableStateOf(false) }
    var advancedEffects by rememberSaveable { mutableStateOf(false) }
    var queue by rememberSaveable { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var marks by rememberSaveable { mutableStateOf(false) }
    val track = catalog.tracks.find { it.id == state.id }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.atlas_now_playing),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track?.artist?.ifBlank { "مقطع محلي" } ?: "مقطع محلي",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (track != null)
                    IconButton(onClick = { vm.favorite(track.id) }) {
                        Icon(
                            if (track.favorite) Icons.Default.Favorite
                            else Icons.Default.FavoriteBorder,
                            "تبديل المفضلة",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (track != null) AudioCover(track, 228)
                    Text(
                        stringResource(R.string.atlas_local_audio),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.muted || state.mediaVolume == 0)
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.VolumeOff,
                            null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "صوت الهاتف مكتوم؛ ارفعه من أزرار الصوت.",
                            Modifier.weight(1f).padding(start = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            if (state.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Slider(
                    value =
                        seek
                            ?: state.position
                                .toFloat()
                                .coerceIn(0f, state.duration.coerceAtLeast(1).toFloat()),
                    onValueChange = { seek = it },
                    onValueChangeFinished = {
                        seek?.let { vm.seek(it.toLong()) }
                        seek = null
                    },
                    valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(),
                    enabled = connected && state.duration > 0,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(audioTime((seek?.toLong() ?: state.position)))
                    Text(audioTime(state.duration))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = vm::previous, enabled = connected) {
                        Icon(Icons.Default.SkipPrevious, "المقطع السابق")
                    }
                    IconButton(onClick = { vm.jump(-15000) }, enabled = connected) {
                        Icon(Icons.Default.Replay, "رجوع 15 ثانية")
                    }
                    FilledIconButton(
                        onClick = vm::toggle,
                        enabled = connected,
                        modifier = Modifier.size(80.dp),
                    ) {
                        Icon(
                            if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "تشغيل أو إيقاف مؤقت",
                        )
                    }
                    IconButton(onClick = { vm.jump(15000) }, enabled = connected) {
                        Icon(Icons.Default.FastForward, "تقديم 15 ثانية")
                    }
                    IconButton(onClick = vm::next, enabled = connected) {
                        Icon(Icons.Default.SkipNext, "المقطع التالي")
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconToggleButton(
                    checked = state.shuffle,
                    onCheckedChange = { vm.shuffle() },
                    enabled = connected,
                ) {
                    Icon(
                        Icons.Default.Shuffle,
                        "عشوائي",
                        tint =
                            if (state.shuffle) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = vm::repeat, enabled = connected) {
                    Icon(
                        if (state.repeat == 1) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        when (state.repeat) {
                            1 -> "تكرار المقطع"
                            2 -> "تكرار القائمة"
                            else -> "بدون تكرار"
                        },
                        tint =
                            if (state.repeat != 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { sleep = true }) { Icon(Icons.Default.Bedtime, "مؤقت النوم") }
                IconButton(onClick = { queue = !queue }) {
                    Icon(Icons.Default.QueueMusic, "قائمة الانتظار (${state.queue.size})")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerTool(
                    "الصوت والمؤثرات",
                    Icons.Default.GraphicEq,
                    effects,
                    Modifier.weight(1f),
                ) {
                    effects = !effects
                    tools = false
                    marks = false
                }
                PlayerTool("سرعة وخيارات", Icons.Default.Tune, tools, Modifier.weight(1f)) {
                    tools = !tools
                    effects = false
                    marks = false
                }
                PlayerTool("المواضع", Icons.Default.Bookmarks, marks, Modifier.weight(1f)) {
                    marks = !marks
                    effects = false
                    tools = false
                }
            }
            if (effects)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AudioSceneChooser(vm, state.scene)
                        TextButton(onClick = { advancedEffects = !advancedEffects }) {
                            Text(if (advancedEffects) "إخفاء التحكم اليدوي" else "معادل وتحكم يدوي")
                        }
                        if (advancedEffects)
                            key(state.soundRevision) { AudioEffectsPanel(vm, state.sound) }
                    }
                }
            if (tools)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.sleepAt > 0)
                        Text(
                            "يتوقف بعد نحو ${((state.sleepAt-System.currentTimeMillis())/60000).coerceAtLeast(0)} دقيقة"
                        )
                    if (state.sleepEnd) Text("يتوقف عند نهاية المقطع الحالي")
                    Text("سرعة الاستماع")
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            FilterChip(
                                state.speed == speed,
                                { vm.speed(speed) },
                                label = { Text("${speed}×") },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("تجاوز الصمت", Modifier.weight(1f))
                        Switch(state.silence, vm::silence)
                    }
                    Text("صوت المشغل · ${(state.volume*100).toInt()}٪")
                    Slider(state.volume, vm::volume, enabled = connected)
                    Text("صوت الوسائط في الهاتف: ${state.mediaVolume} / ${state.mediaMax}")
                    if (state.muted || state.mediaVolume == 0)
                        Text(
                            "صوت الوسائط في الهاتف مكتوم. ارفعه من أزرار الهاتف أو لوحة الصوت.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    if (state.suppression != 0)
                        Text(
                            "النظام أوقف الصوت مؤقتًا بسبب تركيز الصوت أو المخرج الحالي؛ راجع التطبيقات الأخرى ومخرج الصوت."
                        )
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(
                            onClick = {
                                val manager =
                                    vm.app.getSystemService(android.media.AudioManager::class.java)
                                manager.adjustStreamVolume(
                                    android.media.AudioManager.STREAM_MUSIC,
                                    android.media.AudioManager.ADJUST_SAME,
                                    android.media.AudioManager.FLAG_SHOW_UI,
                                )
                            }
                        ) {
                            Text("لوحة صوت الهاتف")
                        }
                        TextButton(onClick = vm::recoverSound) { Text("استعادة التشغيل المباشر") }
                    }
                }
            if (marks)
                Column(Modifier.fillMaxWidth()) {
                    TextButton(onClick = vm::bookmark) {
                        Icon(Icons.Default.BookmarkAdd, null)
                        Text("حفظ موضع +")
                    }
                    val bookmarks = catalog.bookmarks.filter { it.trackId == state.id }
                    if (bookmarks.isEmpty())
                        Text(
                            "احفظ موضعًا للعودة إليه لاحقًا.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    bookmarks.forEach { mark ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { if (track != null) playAt(track, mark.positionMs) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(mark.label)
                            }
                            IconButton(onClick = { vm.deleteBookmark(mark.id) }) {
                                Icon(Icons.Default.Close, "حذف الموضع")
                            }
                        }
                    }
                }
            if (queue)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(state.queue.size) { index ->
                        val id = state.queue[index]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { vm.selectQueued(index) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    catalog.tracks.find { it.id == id }?.title ?: "مقطع محفوظ",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { vm.moveQueued(index, -1) },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, "رفع في الانتظار")
                            }
                            IconButton(
                                onClick = { vm.moveQueued(index, 1) },
                                enabled = index < state.queue.lastIndex,
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, "خفض في الانتظار")
                            }
                            IconButton(onClick = { vm.removeQueued(index) }) {
                                Icon(Icons.Default.Close, "إزالة من الانتظار")
                            }
                        }
                    }
                }
        }
    }
    if (sleep)
        AlertDialog(
            modifier = Modifier.padding(horizontal = 24.dp),
            properties =
                androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { sleep = false },
            title = { Text("مؤقت إيقاف الصوت") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            vm.sleepAtEnd()
                            sleep = false
                        }
                    ) {
                        Text("نهاية المقطع الحالي")
                    }
                    listOf(15, 30, 45, 60, 90, 0).forEach { minutes ->
                        TextButton(
                            onClick = {
                                vm.sleep(minutes)
                                sleep = false
                            }
                        ) {
                            Text(if (minutes == 0) "إلغاء المؤقت" else "$minutes دقيقة")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sleep = false }) { Text("إغلاق") } },
        )
}

@Composable
private fun AudioEffectsPanel(vm: AudioViewModel, support: SoundSupport) {
    val context = LocalContext.current
    var enabled by
        remember(vm.prefs.getBoolean("eq_enabled", false)) {
            mutableStateOf(vm.prefs.getBoolean("eq_enabled", false))
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("معادل الصوت EQ", Modifier.weight(1f))
        Switch(
            enabled,
            {
                enabled = it
                vm.equalizer(it)
            },
            enabled = support.bands.isNotEmpty(),
        )
    }
    if (support.bands.isEmpty())
        Text("اختر الوضع المخصص لتجربة معادل الجهاز أثناء التشغيل؛ قد لا يدعمه المخرج الحالي.")
    else {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("flat" to "طبيعي", "voice" to "صوت بشري", "bass" to "جهير", "bright" to "وضوح")
                .forEach { (key, label) ->
                    TextButton(
                        onClick = {
                            vm.preset(key)
                            enabled = true
                        }
                    ) {
                        Text(label)
                    }
                }
        }
        support.bands.forEach { band ->
            var db by remember(band.index, band.db) { mutableFloatStateOf(band.db) }
            Text("${band.hz} Hz · ${"%.1f".format(java.util.Locale.US,db)} dB")
            Slider(
                db,
                { db = it },
                onValueChangeFinished = {
                    vm.band(band.index, db)
                    enabled = true
                },
                valueRange = band.min..band.max,
            )
        }
    }
    SoundSlider("تعزيز الجهير", vm.prefs.getInt("bass", 0) / 1000f, support.bass) {
        vm.soundInt("bass", (it * 1000).toInt())
    }
    SoundSlider(
        "محيطي افتراضي من الجهاز",
        vm.prefs.getInt("surround", 0) / 1000f,
        support.surround,
    ) {
        vm.soundInt("surround", (it * 1000).toInt())
    }
    SoundSlider("اتساع الستيريو", vm.prefs.getFloat("width", 0f), true) {
        vm.soundFloat("width", it)
    }
    Text(
        "اتساع الستيريو يحسن الإحساس بالمساحة للمقاطع الثنائية. تأثير الجهاز المحيطي يتوقف على دعم الهاتف والمخرج. هذه ليست ثماني قنوات 7.1 حقيقية؛ تشغيل مصدر متعدد القنوات يعتمد على الترميز ونظام السماعات.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(support.systemSpatial, style = MaterialTheme.typography.bodySmall)
    TextButton(
        onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)) }
    ) {
        Text("إعدادات صوت الهاتف")
    }
    TextButton(
        onClick = {
            vm.resetSound()
            enabled = false
        }
    ) {
        Text("استعادة الصوت الطبيعي")
    }
}

@Composable
private fun AudioSceneChooser(vm: AudioViewModel, selected: String) {
    Text("أوضاع الصوت الجاهزة", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AudioScene.entries.forEach { scene ->
            FilterChip(selected == scene.key, { vm.scene(scene) }, label = { Text(scene.label) })
        }
    }
    Text(
        when (selected) {
            "3d" -> "3D: اتساع وانعكاسات قصيرة؛ يُفضّل استخدام سماعات ستيريو."
            "7.1" -> "7.1 افتراضي: ثمانية انعكاسات مكانية إلى ستيريو، وليس ثماني قنوات منفصلة."
            "custom" -> "تحكم يدوي في الاتساع ومعادل الجهاز."
            "lecture_soft" -> "تحسين المحاضرات: تخفيف الحدة والصفير المرتفع."
            "lecture_clear" ->
                "تحسين المحاضرات: تقليل الرنين المنخفض وإبراز الكلام مع ضبط ديناميكي للمستوى."
            else -> "تشغيل مباشر دون مؤثرات؛ يستخدم مخرج الصوت الذي اختاره النظام."
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SoundSlider(label: String, initial: Float, enabled: Boolean, set: (Float) -> Unit) {
    var level by remember(initial) { mutableFloatStateOf(initial) }
    Text(label + if (!enabled) " · غير مدعوم حاليًا" else "")
    Slider(level, { level = it }, onValueChangeFinished = { set(level) }, enabled = enabled)
}

@Composable
private fun PlayerTool(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color =
            if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(vertical = 14.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
    }
}
