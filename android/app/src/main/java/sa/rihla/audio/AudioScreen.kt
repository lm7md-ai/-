package sa.rihla.audio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AudioMiniPlayer(onOpen: () -> Unit, vm: AudioViewModel = viewModel()) {
    val playback by AudioLive.state.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    if (playback.id.isBlank()) return
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(playback.title.ifBlank { "مقطع صوتي" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(audioTime(playback.position), style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = vm::toggle, enabled = connected) { Icon(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "تشغيل أو إيقاف مؤقت") }
            IconButton(onClick = vm::next, enabled = connected) { Icon(Icons.Default.SkipNext, "المقطع التالي") }
        }
    }
}

@Composable
fun AudioScreen(vm: AudioViewModel = viewModel()) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val playback by AudioLive.state.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("all") }
    var sort by rememberSaveable { mutableStateOf("title") }
    var playlist by rememberSaveable { mutableStateOf<Long?>(null) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    var artist by rememberSaveable { mutableStateOf<String?>(null) }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<AudioTrack?>(null) }
    var deleting by remember { mutableStateOf<AudioTrack?>(null) }
    var deletingPlaylist by remember { mutableStateOf<AudioPlaylist?>(null) }
    var relinking by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val relinkFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) relinking?.let { vm.relink(it,uri) }; relinking = null }
    val restoreFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri = it }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.export(uri) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.importFiles(it) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::importFolder) }
    val scanPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.scan() else vm.message.value = "يمكنك اختيار ملفات أو مجلد دون إذن فحص الهاتف" }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun scan() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) vm.scan() else scanPermission.launch(permission)
    }
    fun play(track: AudioTrack, tracks: List<AudioTrack>, at: Long? = null) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.play(track,tracks,at)
    }
    val members = catalog.members.filter { it.playlistId == playlist }.sortedBy { it.position }
    val visible = remember(catalog, query, tab, playlist, album, artist, sort) {
        val values = catalog.tracks.filter { track ->
            (tab != "favorites" || track.favorite) &&
                (tab != "recent" || track.lastPlayedAt > 0) &&
                (playlist == null || members.any { it.trackId == track.id }) &&
                (album == null || track.album == album) && (artist == null || track.artist == artist) &&
                (query.isBlank() || listOf(track.title,track.artist,track.album).any { it.contains(query.trim(),true) })
        }
        when {
            playlist != null -> values.sortedBy { track -> members.indexOfFirst { it.trackId == track.id } }
            tab == "recent" -> values.sortedByDescending { it.lastPlayedAt }
            sort == "new" -> values.sortedByDescending { it.addedAt }
            sort == "duration" -> values.sortedByDescending { it.durationMs }
            else -> values
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Headphones, null, Modifier.size(36.dp))
                    Text("مكتبتك الصوتية", style = MaterialTheme.typography.headlineSmall)
                    Text("${catalog.tracks.size} مقطع · ${catalog.playlists.size} قائمة · تشغيل محلي دون إنترنت")
                    Button(onClick={ sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit("studio") }) { Text("استوديو المحاضرات والمساعد المحلي") }
                    AudioSceneChooser(vm,playback.scene)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { files.launch(arrayOf("audio/*")) }, enabled = !busy) { Text("إضافة ملفات") }
                        OutlinedButton(onClick = { folder.launch(null) }, enabled = !busy) { Text("إضافة مجلد") }
                        OutlinedButton(onClick = ::scan, enabled = !busy) { Text("فحص الهاتف") }
                    }
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("تجهيز بيانات المقاطع…") }
                    Text("الملفات تبقى في مكانها. نقلها أو حذفها أو سحب إذن الوصول قد يتطلب إضافتها مرة أخرى.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { exportFile.launch("mashwari-audio-library.json") }, enabled = !busy) { Text("تصدير سجل المكتبة") }
                        TextButton(onClick = { restoreFile.launch(arrayOf("application/json","text/plain")) }, enabled = !busy) { Text("استعادة سجل المكتبة") }
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary); TextButton(onClick = { vm.message.value = null }) { Text("إخفاء") } }
        }
        if (playback.id.isNotBlank()) item { AudioNowPlaying(vm, playback, catalog, connected) { t,at -> play(t,listOf(t),at) } }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "كل المقاطع", "favorites" to "المفضلة", "recent" to "الأخيرة", "albums" to "الألبومات", "artists" to "الفنانون", "lists" to "قوائم التشغيل").forEach { (key,label) ->
                    FilterChip(tab == key, { tab = key; playlist = null; album = null; artist = null }, label = { Text(label) })
                }
            }
            OutlinedTextField(query, { query = it }, label = { Text("ابحث بالعنوان أو الفنان أو الألبوم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("title" to "الاسم", "new" to "الأحدث", "duration" to "المدة").forEach { (key,label) -> FilterChip(sort == key, { sort = key }, label = { Text(label) }) }
                TextButton(onClick = { create = true; name = "" }) { Text("قائمة جديدة +") }
            }
        }
        if (tab == "lists") {
            items(catalog.playlists, key = { "list:${it.id}" }) { list ->
                ElevatedCard(onClick = { playlist = list.id; tab = "all" }) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QueueMusic,null)
                        Text(list.name, Modifier.weight(1f).padding(12.dp))
                        Text("${catalog.members.count { it.playlistId == list.id }}")
                        IconButton(onClick = { deletingPlaylist = list }) { Icon(Icons.Default.DeleteOutline,"حذف القائمة") }
                    }
                }
            }
            if (catalog.playlists.isEmpty()) item { Text("أنشئ قائمة ثم أضف مقاطعك إليها من قائمة المقطع.") }
        } else if (tab == "albums" || tab == "artists") {
            val groups = catalog.tracks.groupBy { if (tab == "albums") it.album else it.artist }.filter { (key,_) -> query.isBlank() || key.contains(query,true) }
            items(groups.entries.sortedBy { it.key }, key = { "group:${it.key}" }) { group ->
                OutlinedButton(onClick = { if (tab == "albums") album = group.key else artist = group.key; tab = "all" }, modifier = Modifier.fillMaxWidth()) {
                    Text("${group.key.ifBlank { "غير محدد" }} · ${group.value.size} مقطع")
                }
            }
        } else {
            item {
                if (playlist != null || album != null || artist != null) {
                    Text(catalog.playlists.find { it.id == playlist }?.name ?: album ?: artist.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { playlist = null; album = null; artist = null }) { Text("عرض المكتبة كاملة") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { visible.firstOrNull()?.let { play(it, visible) } }, enabled = connected && visible.isNotEmpty()) { Text("تشغيل الكل") }
                    OutlinedButton(onClick = { visible.shuffled().let { list -> list.firstOrNull()?.let { play(it,list) } } }, enabled = connected && visible.isNotEmpty()) { Text("تشغيل عشوائي") }
                }
                if (visible.isEmpty()) Text(if (catalog.tracks.isEmpty()) "أضف صوتياتك لتبدأ؛ لا تحتاج اتصالًا بالإنترنت." else "لا توجد مقاطع مطابقة")
            }
            items(visible, key = { it.id }) { track ->
                var menu by remember { mutableStateOf(false) }
                Card(onClick = { if (connected) play(track,visible) }, colors = CardDefaults.cardColors(containerColor = if (track.id == playback.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AudioCover(track,48)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                Text(audioTime(track.durationMs) + if (audioResume(track.positionMs,track.durationMs) > 0) " · متابعة من ${audioTime(track.positionMs)}" else "", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { vm.favorite(track.id) }) { Icon(if (track.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,"تبديل المفضلة") }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert,"خيارات المقطع") }
                                DropdownMenu(menu,{ menu = false }) {
                                    DropdownMenuItem(text = { Text("إضافة لقائمة تشغيل") }, onClick = { target = track; menu = false })
                                    DropdownMenuItem(text = { Text("إضافة للانتظار") }, onClick = { vm.enqueue(track); menu = false }, enabled = connected)
                                    DropdownMenuItem(text = { Text("تشغيل بعد الحالي") }, onClick = { vm.nextInQueue(track); menu = false }, enabled = connected)
                                    DropdownMenuItem(text = { Text("تشغيل من البداية") }, onClick = { play(track,visible,0); menu = false }, enabled = connected)
                                    if (playlist != null) DropdownMenuItem(text = { Text("إزالة من هذه القائمة") }, onClick = { vm.removeMember(playlist!!,track.id); menu = false })
                                    DropdownMenuItem(text = { Text("إزالة من المكتبة") }, onClick = { deleting = track; menu = false })
                                    DropdownMenuItem(text = { Text("إعادة ربط الملف") }, onClick = { relinking = track.id; relinkFile.launch(arrayOf("audio/*")); menu = false })
                                }
                            }
                        }
                        if (playlist != null) {
                            val index = members.indexOfFirst { it.trackId == track.id }
                            Row {
                                TextButton(onClick = { vm.move(playlist!!,members[index],members[index-1]) }, enabled = index > 0) { Text("أعلى ↑") }
                                TextButton(onClick = { vm.move(playlist!!,members[index],members[index+1]) }, enabled = index >= 0 && index < members.lastIndex) { Text("أسفل ↓") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (create) AlertDialog(modifier=Modifier.padding(horizontal=24.dp), properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false), onDismissRequest = { create = false }, title = { Text("قائمة تشغيل جديدة") }, text = { OutlinedTextField(name,{name=it.take(80)},label={Text("اسم القائمة")}) }, confirmButton = { TextButton(onClick = { vm.playlist(name); create = false }, enabled = name.isNotBlank()) { Text("إنشاء") } }, dismissButton = { TextButton(onClick = { create = false }) { Text("إلغاء") } })
    target?.let { track -> AlertDialog(modifier=Modifier.padding(horizontal=24.dp), properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false), onDismissRequest = { target = null }, title = { Text("إضافة إلى قائمة") }, text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) { if (catalog.playlists.isEmpty()) Text("أنشئ قائمة أولًا"); catalog.playlists.forEach { list -> TextButton(onClick = { vm.addToPlaylist(list.id,track.id); target = null }) { Text(list.name) } } } }, confirmButton = { TextButton(onClick = { target = null }) { Text("إغلاق") } }) }
    deleting?.let { track -> AlertDialog(modifier=Modifier.padding(horizontal=24.dp), properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false), onDismissRequest = { deleting = null }, title = { Text("إزالة من المكتبة؟") }, text = { Text("يُزال سجل ${track.title} والقوائم والإشارات المرتبطة به. يبقى ملف الهاتف في مكانه.") }, confirmButton = { TextButton(onClick = { vm.remove(track.id); deleting = null }) { Text("إزالة") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("إلغاء") } }) }
    deletingPlaylist?.let { list -> AlertDialog(modifier=Modifier.padding(horizontal=24.dp), properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false), onDismissRequest = { deletingPlaylist = null }, title = { Text("حذف قائمة ${list.name}؟") }, text = { Text("تبقى المقاطع في المكتبة.") }, confirmButton = { TextButton(onClick = { vm.removePlaylist(list.id); playlist = null; deletingPlaylist = null }) { Text("حذف") } }, dismissButton = { TextButton(onClick = { deletingPlaylist = null }) { Text("إلغاء") } }) }
    restoreUri?.let { uri -> AlertDialog(modifier=Modifier.padding(horizontal=24.dp), properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false), onDismissRequest={restoreUri=null},title={Text("استعادة مكتبة صوتيات؟")},text={Text("الاستعادة إلى مكتبة صوتيات فارغة فقط؛ لا تمس مشاويرك أو مصاريفك. يحوي السجل عناوين الملفات والقوائم والمفضلة والمواضع، وليس ملفات الصوت نفسها. قد تحتاج إعادة ربط الملفات بعد النقل إلى هاتف آخر.")},confirmButton={TextButton(onClick={vm.restore(uri);restoreUri=null}){Text("استعادة")}},dismissButton={TextButton(onClick={restoreUri=null}){Text("إلغاء")}}) }
}

@Composable
private fun AudioNowPlaying(vm: AudioViewModel, state: AudioPlayback, catalog: AudioCatalog, connected: Boolean, playAt: (AudioTrack, Long) -> Unit) {
    var seek by remember(state.id) { mutableStateOf<Float?>(null) }
    var effects by rememberSaveable { mutableStateOf(false) }
    var queue by rememberSaveable { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    val track = catalog.tracks.find { it.id == state.id }
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("قيد الاستماع", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (track != null) Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) { AudioCover(track,160) }
            Text(state.title, style = MaterialTheme.typography.titleLarge)
            track?.artist?.takeIf { it.isNotBlank() }?.let { Text(it) }
            if (state.channels > 0) Text("مصدر ${state.channels} قناة · ${state.codec.substringAfter('/')}",style=MaterialTheme.typography.labelSmall)
            if (state.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Slider(value = seek ?: state.position.toFloat().coerceIn(0f,state.duration.coerceAtLeast(1).toFloat()), onValueChange = { seek = it }, onValueChangeFinished = { seek?.let { vm.seek(it.toLong()) }; seek = null }, valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(), enabled = connected && state.duration > 0)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(audioTime((seek?.toLong() ?: state.position))); Text(audioTime(state.duration)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::previous, enabled = connected) { Icon(Icons.Default.SkipPrevious,"المقطع السابق") }
                    IconButton(onClick = { vm.jump(-15000) }, enabled = connected) { Icon(Icons.Default.Replay,"رجوع 15 ثانية") }
                    FilledIconButton(onClick = vm::toggle, enabled = connected, modifier = Modifier.size(56.dp)) { Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,"تشغيل أو إيقاف مؤقت") }
                    IconButton(onClick = { vm.jump(15000) }, enabled = connected) { Icon(Icons.Default.FastForward,"تقديم 15 ثانية") }
                    IconButton(onClick = vm::next, enabled = connected) { Icon(Icons.Default.SkipNext,"المقطع التالي") }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(state.shuffle,vm::shuffle,label={Text("عشوائي")},enabled=connected)
                FilterChip(state.repeat != 0,vm::repeat,label={Text(when(state.repeat){1->"تكرار المقطع";2->"تكرار القائمة";else->"بدون تكرار"})},enabled=connected)
                TextButton(onClick = { sleep = true }) { Text("مؤقت النوم") }
                TextButton(onClick = vm::bookmark) { Text("حفظ موضع +") }
            }
            if (state.sleepAt > 0) Text("يتوقف بعد نحو ${((state.sleepAt-System.currentTimeMillis())/60000).coerceAtLeast(0)} دقيقة")
            if (state.sleepEnd) Text("يتوقف عند نهاية المقطع الحالي")
            Text("سرعة الاستماع")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(.5f,.75f,1f,1.25f,1.5f,2f).forEach { speed -> FilterChip(state.speed==speed,{vm.speed(speed)},label={Text("${speed}×")}) } }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("تجاوز الصمت",Modifier.weight(1f));Switch(state.silence,vm::silence) }
            Text("صوت المشغل · ${(state.volume*100).toInt()}٪")
            Slider(state.volume,vm::volume,enabled=connected)
            Text("صوت الوسائط في الهاتف: ${state.mediaVolume} / ${state.mediaMax}")
            if (state.muted || state.mediaVolume==0) Text("صوت الوسائط في الهاتف مكتوم. ارفعه من أزرار الهاتف أو لوحة الصوت.",color=MaterialTheme.colorScheme.error)
            if (state.suppression!=0) Text("النظام أوقف الصوت مؤقتًا بسبب تركيز الصوت أو المخرج الحالي؛ راجع التطبيقات الأخرى ومخرج الصوت.")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick={
                    val manager=vm.app.getSystemService(android.media.AudioManager::class.java)
                    manager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,android.media.AudioManager.ADJUST_SAME,android.media.AudioManager.FLAG_SHOW_UI)
                }) { Text("لوحة صوت الهاتف") }
                TextButton(onClick=vm::recoverSound) { Text("استعادة التشغيل المباشر") }
            }
            TextButton(onClick = { effects = !effects }) { Text(if(effects) "إغلاق إعدادات الصوت" else "المعادل والصوت المحيطي") }
            if (effects) key(state.soundRevision) { AudioEffectsPanel(vm,state.sound) }
            val bookmarks = catalog.bookmarks.filter { it.trackId == state.id }
            if (bookmarks.isNotEmpty()) {
                Text("المواضع المحفوظة")
                bookmarks.forEach { mark -> Row(verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick = { if (track != null) playAt(track,mark.positionMs) }) { Text(mark.label) }
                    IconButton(onClick = { vm.deleteBookmark(mark.id) }) { Icon(Icons.Default.Close,"حذف الموضع") }
                } }
            }
            TextButton(onClick = { queue = !queue }) { Text("قائمة الانتظار (${state.queue.size})") }
            if (queue) LazyColumn(Modifier.fillMaxWidth().heightIn(max=320.dp)) { items(state.queue.size) { index ->
                val id = state.queue[index]
                Row(verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={vm.selectQueued(index)},modifier=Modifier.weight(1f)) { Text(catalog.tracks.find{it.id==id}?.title ?: "مقطع محفوظ", maxLines=1,overflow=TextOverflow.Ellipsis) }
                    IconButton(onClick={vm.moveQueued(index,-1)},enabled=index>0) { Icon(Icons.Default.KeyboardArrowUp,"رفع في الانتظار") }
                    IconButton(onClick={vm.moveQueued(index,1)},enabled=index<state.queue.lastIndex) { Icon(Icons.Default.KeyboardArrowDown,"خفض في الانتظار") }
                    IconButton(onClick={vm.removeQueued(index)}) { Icon(Icons.Default.Close,"إزالة من الانتظار") }
                }
            } }
        }
    }
    if (sleep) AlertDialog(modifier=Modifier.padding(horizontal=24.dp), properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false), onDismissRequest={sleep=false},title={Text("مؤقت إيقاف الصوت")},text={Column{TextButton(onClick={vm.sleepAtEnd();sleep=false}){Text("نهاية المقطع الحالي")};listOf(15,30,45,60,90,0).forEach{minutes->TextButton(onClick={vm.sleep(minutes);sleep=false}){Text(if(minutes==0)"إلغاء المؤقت" else "$minutes دقيقة")}}}},confirmButton={TextButton(onClick={sleep=false}){Text("إغلاق")}})
}

@Composable
private fun AudioEffectsPanel(vm: AudioViewModel, support: SoundSupport) {
    val context = LocalContext.current
    var enabled by remember(vm.prefs.getBoolean("eq_enabled",false)) { mutableStateOf(vm.prefs.getBoolean("eq_enabled",false)) }
    Row(verticalAlignment=Alignment.CenterVertically) { Text("معادل الصوت EQ",Modifier.weight(1f)); Switch(enabled,{enabled=it;vm.equalizer(it)},enabled=support.bands.isNotEmpty()) }
    if (support.bands.isEmpty()) Text("اختر الوضع المخصص لتجربة معادل الجهاز أثناء التشغيل؛ قد لا يدعمه المخرج الحالي.")
    else {
        Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("flat" to "طبيعي","voice" to "صوت بشري","bass" to "جهير","bright" to "وضوح").forEach{(key,label)->TextButton(onClick={vm.preset(key);enabled=true}){Text(label)}} }
        support.bands.forEach { band ->
            var db by remember(band.index,band.db) { mutableFloatStateOf(band.db) }
            Text("${band.hz} Hz · ${"%.1f".format(java.util.Locale.US,db)} dB")
            Slider(db,{db=it},onValueChangeFinished={vm.band(band.index,db);enabled=true},valueRange=band.min..band.max)
        }
    }
    SoundSlider("تعزيز الجهير", vm.prefs.getInt("bass",0)/1000f, support.bass) { vm.soundInt("bass",(it*1000).toInt()) }
    SoundSlider("محيطي افتراضي من الجهاز", vm.prefs.getInt("surround",0)/1000f,support.surround) { vm.soundInt("surround",(it*1000).toInt()) }
    SoundSlider("اتساع الستيريو", vm.prefs.getFloat("width",0f),true) { vm.soundFloat("width",it) }
    Text("اتساع الستيريو يحسن الإحساس بالمساحة للمقاطع الثنائية. تأثير الجهاز المحيطي يتوقف على دعم الهاتف والمخرج. هذه ليست ثماني قنوات 7.1 حقيقية؛ تشغيل مصدر متعدد القنوات يعتمد على الترميز ونظام السماعات.",style=MaterialTheme.typography.bodySmall)
    Text(support.systemSpatial,style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={context.startActivity(Intent(android.provider.Settings.ACTION_SOUND_SETTINGS))}){Text("إعدادات صوت الهاتف")}
    TextButton(onClick={vm.resetSound();enabled=false}){Text("استعادة الصوت الطبيعي")}
}

@Composable
private fun AudioSceneChooser(vm:AudioViewModel,selected:String) {
    Text("أوضاع الصوت الجاهزة",style=MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        AudioScene.entries.filterNot { it.lecture }.forEach { scene ->
            FilterChip(selected==scene.key,{vm.scene(scene)},label={Text(scene.label)})
        }
    }
    Text(when(selected) {
        "3d" -> "3D: اتساع وانعكاسات قصيرة؛ يُفضّل استخدام سماعات ستيريو."
        "7.1" -> "7.1 افتراضي: ثمانية انعكاسات مكانية إلى ستيريو، وليس ثماني قنوات منفصلة."
        "custom" -> "تحكم يدوي في الاتساع ومعادل الجهاز."
        "lecture_soft" -> "تحسين المحاضرات: تخفيف الحدة والصفير المرتفع."
        "lecture_clear" -> "تحسين المحاضرات: تقليل الرنين المنخفض وإبراز الكلام مع ضبط ديناميكي للمستوى."
        else -> "تشغيل مباشر دون مؤثرات؛ يستخدم مخرج الصوت الذي اختاره النظام."
    },style=MaterialTheme.typography.bodySmall)
}

@Composable
private fun SoundSlider(label: String, initial: Float, enabled: Boolean, set: (Float)->Unit) {
    var level by remember(initial) { mutableFloatStateOf(initial) }
    Text(label + if (!enabled) " · غير مدعوم حاليًا" else "")
    Slider(level,{level=it},onValueChangeFinished={set(level)},enabled=enabled)
}

@Composable
private fun AudioCover(track: AudioTrack, size: Int) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null,track.artwork) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (track.artwork.isBlank()) null else runCatching { android.graphics.BitmapFactory.decodeFile(track.artwork)?.asImageBitmap() }.getOrNull()
        }
    }
    Surface(Modifier.size(size.dp),shape=RoundedCornerShape((size/6).dp),color=MaterialTheme.colorScheme.secondaryContainer) {
        val cover = image
        if (cover != null) Image(cover,"غلاف المقطع",contentScale=ContentScale.Crop)
        else Box(contentAlignment=Alignment.Center) { Icon(Icons.Default.MusicNote,null,Modifier.size((size/2).dp),tint=MaterialTheme.colorScheme.primary) }
    }
}
