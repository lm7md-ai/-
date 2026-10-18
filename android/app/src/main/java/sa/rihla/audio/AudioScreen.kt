package sa.rihla.audio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AudioMiniPlayer(onOpen: () -> Unit, vm: AudioViewModel = viewModel()) {
    val playback by AudioLive.state.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    if (playback.id.isBlank()) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
                ) {
                    Icon(
                        Icons.Default.GraphicEq,
                        null,
                        Modifier.padding(10.dp).size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        playback.title.ifBlank { "مقطع صوتي" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "قيد الاستماع · ${audioTime(playback.position)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = vm::toggle, enabled = connected) {
                    Icon(
                        if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "تشغيل أو إيقاف مؤقت",
                    )
                }
                IconButton(onClick = vm::next, enabled = connected) {
                    Icon(Icons.Default.SkipNext, "المقطع التالي")
                }
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                LinearProgressIndicator(
                    progress = {
                        (playback.position.toFloat() / playback.duration.coerceAtLeast(1)).coerceIn(
                            0f,
                            1f,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .08f),
                )
            }
        }
    }
}

@Composable
fun AudioScreen(
    vm: AudioViewModel = viewModel(),
    onOpenPlayer: () -> Unit = {
        sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit("player")
    },
) {
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
    var libraryMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var addMenu by rememberSaveable { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<AudioTrack?>(null) }
    var deleting by remember { mutableStateOf<AudioTrack?>(null) }
    var deletingPlaylist by remember { mutableStateOf<AudioPlaylist?>(null) }
    var relinking by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val relinkFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) relinking?.let { vm.relink(it, uri) }
            relinking = null
        }
    val restoreFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            restoreUri = it
        }
    val exportFile =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) vm.export(uri)
        }
    val files =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            if (it.isNotEmpty()) vm.importFiles(it)
        }
    val folder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let(vm::importFolder)
        }
    val scanPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) vm.scan()
            else vm.message.value = "يمكنك اختيار ملفات أو مجلد دون إذن فحص الهاتف"
        }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun scan() {
        val permission =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) vm.scan()
        else scanPermission.launch(permission)
    }
    fun play(track: AudioTrack, tracks: List<AudioTrack>, at: Long? = null) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.play(track, tracks, at)
    }
    val members = catalog.members.filter { it.playlistId == playlist }.sortedBy { it.position }
    val visible =
        remember(catalog, query, tab, playlist, album, artist, sort) {
            val values =
                catalog.tracks.filter { track ->
                    (tab != "favorites" || track.favorite) &&
                        (tab != "recent" || track.lastPlayedAt > 0) &&
                        (playlist == null || members.any { it.trackId == track.id }) &&
                        (album == null || track.album == album) &&
                        (artist == null || track.artist == artist) &&
                        (query.isBlank() ||
                            listOf(track.title, track.artist, track.album).any {
                                it.contains(query.trim(), true)
                            })
                }
            when {
                playlist != null ->
                    values.sortedBy { track -> members.indexOfFirst { it.trackId == track.id } }
                tab == "recent" -> values.sortedByDescending { it.lastPlayedAt }
                sort == "new" -> values.sortedByDescending { it.addedAt }
                sort == "duration" -> values.sortedByDescending { it.durationMs }
                else -> values
            }
        }
    BackHandler(enabled = playlist != null || album != null || artist != null) {
        playlist = null
        album = null
        artist = null
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("مكتبتك الصوتية", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "${catalog.tracks.size} مقطع · على جهازك",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(onClick = { addMenu = true }, enabled = !busy) {
                    Icon(Icons.Default.Add, "إضافة صوتيات")
                }
                Box {
                    IconButton(onClick = { libraryMenu = true }) {
                        Icon(Icons.Default.MoreVert, "إدارة المكتبة")
                    }
                    DropdownMenu(libraryMenu, { libraryMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("قائمة جديدة +") },
                            leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                            onClick = {
                                create = true
                                name = ""
                                libraryMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("استوديو المحاضرات والمساعد المحلي") },
                            leadingIcon = { Icon(Icons.Default.AutoFixHigh, null) },
                            onClick = {
                                sa.rihla.audio.studio.AssistantNavigation.requests.tryEmit("studio")
                                libraryMenu = false
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("تصدير سجل المكتبة") },
                            onClick = {
                                exportFile.launch("mashwari-audio-library.json")
                                libraryMenu = false
                            },
                            enabled = !busy,
                        )
                        DropdownMenuItem(
                            text = { Text("استعادة سجل المكتبة") },
                            onClick = {
                                restoreFile.launch(arrayOf("application/json", "text/plain"))
                                libraryMenu = false
                            },
                            enabled = !busy,
                        )
                    }
                }
            }
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text("ابحث بالعنوان أو الفنان أو الألبوم") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty())
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, "مسح البحث")
                        }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("تجهيز بيانات المقاطع…", style = MaterialTheme.typography.bodySmall)
            }
            message?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        it,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(onClick = { vm.message.value = null }) {
                        Icon(Icons.Default.Close, "إخفاء")
                    }
                }
            }
        }
        item {
            val tabs =
                listOf(
                    "all" to "المقاطع",
                    "lists" to "قوائم التشغيل",
                    "favorites" to "المفضلة",
                    "albums" to "الألبومات",
                    "artists" to "الفنانون",
                    "recent" to "الأخيرة",
                )
            ScrollableTabRow(
                selectedTabIndex = tabs.indexOfFirst { it.first == tab }.coerceAtLeast(0),
                edgePadding = 0.dp,
                containerColor = MaterialTheme.colorScheme.background,
                divider = {},
            ) {
                tabs.forEach { (key, label) ->
                    Tab(
                        selected = tab == key,
                        onClick = {
                            tab = key
                            playlist = null
                            album = null
                            artist = null
                        },
                        text = { Text(label, maxLines = 1) },
                    )
                }
            }
        }
        if (tab == "lists") {
            val playlists =
                catalog.playlists.filter { query.isBlank() || it.name.contains(query.trim(), true) }
            item {
                TextButton(
                    onClick = {
                        create = true
                        name = ""
                    }
                ) {
                    Icon(Icons.Default.PlaylistAdd, null)
                    Spacer(Modifier.width(8.dp))
                    Text("قائمة جديدة +")
                }
            }
            items(playlists, key = { "list:${it.id}" }) { list ->
                ElevatedCard(
                    onClick = {
                        playlist = list.id
                        tab = "all"
                    }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.QueueMusic, null)
                        Text(list.name, Modifier.weight(1f).padding(12.dp))
                        Text("${catalog.members.count { it.playlistId == list.id }}")
                        IconButton(onClick = { deletingPlaylist = list }) {
                            Icon(Icons.Default.DeleteOutline, "حذف القائمة")
                        }
                    }
                }
            }
            if (playlists.isEmpty())
                item {
                    Text(
                        if (query.isBlank()) "أنشئ قائمة ثم أضف مقاطعك إليها من قائمة المقطع."
                        else "لا توجد قوائم مطابقة"
                    )
                }
        } else if (tab == "albums" || tab == "artists") {
            val groups =
                catalog.tracks
                    .groupBy { if (tab == "albums") it.album else it.artist }
                    .filter { (key, _) -> query.isBlank() || key.contains(query, true) }
            items(
                groups.entries.sortedBy { it.key }.chunked(2),
                key = { "group:${it.first().key}" },
            ) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { group ->
                        Card(
                            onClick = {
                                if (tab == "albums") album = group.key else artist = group.key
                                tab = "all"
                            },
                            modifier = Modifier.weight(1f),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AudioCover(group.value.first(), 100)
                                Text(
                                    group.key.ifBlank { "غير محدد" },
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${group.value.size} مقطع",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (groups.isEmpty())
                item {
                    sa.rihla.ui.EmptyState(
                        "لا توجد نتائج",
                        "أضف مقاطع أو غيّر البحث.",
                        Icons.Default.Album,
                    )
                }
        } else {
            item {
                if (playlist != null || album != null || artist != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            catalog.playlists.find { it.id == playlist }?.name
                                ?: album
                                ?: artist.orEmpty(),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TextButton(
                            onClick = {
                                playlist = null
                                album = null
                                artist = null
                            }
                        ) {
                            Text("عرض المكتبة كاملة")
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = { visible.firstOrNull()?.let { play(it, visible) } },
                        enabled = connected && visible.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("تشغيل الكل")
                    }
                    IconButton(
                        onClick = {
                            visible.shuffled().let { list ->
                                list.firstOrNull()?.let { play(it, list) }
                            }
                        },
                        enabled = connected && visible.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Shuffle, "تشغيل عشوائي")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${visible.size} مقطع",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Default.Sort, "ترتيب المقاطع")
                        }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            listOf("title" to "الاسم", "new" to "الأحدث", "duration" to "المدة")
                                .forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        trailingIcon = {
                                            if (sort == key) Icon(Icons.Default.Check, null)
                                        },
                                        onClick = {
                                            sort = key
                                            sortMenu = false
                                        },
                                    )
                                }
                        }
                    }
                }
                if (visible.isEmpty())
                    sa.rihla.ui.EmptyState(
                        if (catalog.tracks.isEmpty()) "مكتبتك تبدأ من هنا"
                        else "لا توجد مقاطع مطابقة",
                        if (catalog.tracks.isEmpty()) "أضف ملفاتك من زر + واستمع إليها دون إنترنت."
                        else "جرّب عنوانًا آخر أو غيّر التبويب.",
                        Icons.Default.LibraryMusic,
                    )
            }
            items(visible, key = { it.id }) { track ->
                var menu by remember { mutableStateOf(false) }
                Card(
                    onClick = {
                        if (connected) {
                            play(track, visible)
                            onOpenPlayer()
                        }
                    },
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (track.id == playback.id)
                                    MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.background
                        ),
                ) {
                    Column(Modifier.padding(vertical = 4.dp, horizontal = 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AudioCover(track, 48)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(
                                    track.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    listOf(track.artist, audioTime(track.durationMs))
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (audioResume(track.positionMs, track.durationMs) > 0)
                                    Text(
                                        "متابعة من ${audioTime(track.positionMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                            }

                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Default.MoreVert, "خيارات المقطع")
                                }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (track.favorite) "إزالة من المفضلة"
                                                else "إضافة للمفضلة"
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (track.favorite) Icons.Default.Favorite
                                                else Icons.Default.FavoriteBorder,
                                                null,
                                            )
                                        },
                                        onClick = {
                                            vm.favorite(track.id)
                                            menu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("إضافة لقائمة تشغيل") },
                                        onClick = {
                                            target = track
                                            menu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("إضافة للانتظار") },
                                        onClick = {
                                            vm.enqueue(track)
                                            menu = false
                                        },
                                        enabled = connected,
                                    )
                                    DropdownMenuItem(
                                        text = { Text("تشغيل بعد الحالي") },
                                        onClick = {
                                            vm.nextInQueue(track)
                                            menu = false
                                        },
                                        enabled = connected,
                                    )
                                    DropdownMenuItem(
                                        text = { Text("تشغيل من البداية") },
                                        onClick = {
                                            play(track, visible, 0)
                                            menu = false
                                        },
                                        enabled = connected,
                                    )
                                    if (playlist != null)
                                        DropdownMenuItem(
                                            text = { Text("إزالة من هذه القائمة") },
                                            onClick = {
                                                vm.removeMember(playlist!!, track.id)
                                                menu = false
                                            },
                                        )
                                    DropdownMenuItem(
                                        text = { Text("إزالة من المكتبة") },
                                        onClick = {
                                            deleting = track
                                            menu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("إعادة ربط الملف") },
                                        onClick = {
                                            relinking = track.id
                                            relinkFile.launch(arrayOf("audio/*"))
                                            menu = false
                                        },
                                    )
                                }
                            }
                        }
                        if (playlist != null) {
                            val index = members.indexOfFirst { it.trackId == track.id }
                            Row {
                                TextButton(
                                    onClick = {
                                        vm.move(playlist!!, members[index], members[index - 1])
                                    },
                                    enabled = index > 0,
                                ) {
                                    Text("أعلى ↑")
                                }
                                TextButton(
                                    onClick = {
                                        vm.move(playlist!!, members[index], members[index + 1])
                                    },
                                    enabled = index >= 0 && index < members.lastIndex,
                                ) {
                                    Text("أسفل ↓")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (addMenu)
        AudioAddSheet(
            onDismiss = { addMenu = false },
            files = {
                addMenu = false
                files.launch(arrayOf("audio/*"))
            },
            folder = {
                addMenu = false
                folder.launch(null)
            },
            scan = {
                addMenu = false
                scan()
            },
        )
    if (create)
        AlertDialog(
            modifier = Modifier.padding(horizontal = 24.dp),
            properties =
                androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { create = false },
            title = { Text("قائمة تشغيل جديدة") },
            text = {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("اسم القائمة") })
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.playlist(name)
                        create = false
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text("إنشاء")
                }
            },
            dismissButton = { TextButton(onClick = { create = false }) { Text("إلغاء") } },
        )
    target?.let { track ->
        AlertDialog(
            modifier = Modifier.padding(horizontal = 24.dp),
            properties =
                androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { target = null },
            title = { Text("إضافة إلى قائمة") },
            text = {
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                    if (catalog.playlists.isEmpty()) Text("أنشئ قائمة أولًا")
                    catalog.playlists.forEach { list ->
                        TextButton(
                            onClick = {
                                vm.addToPlaylist(list.id, track.id)
                                target = null
                            }
                        ) {
                            Text(list.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { target = null }) { Text("إغلاق") } },
        )
    }
    deleting?.let { track ->
        AlertDialog(
            modifier = Modifier.padding(horizontal = 24.dp),
            properties =
                androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { deleting = null },
            title = { Text("إزالة من المكتبة؟") },
            text = {
                Text(
                    "يُزال سجل ${track.title} والقوائم والإشارات المرتبطة به. يبقى ملف الهاتف في مكانه."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.remove(track.id)
                        deleting = null
                    }
                ) {
                    Text("إزالة")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("إلغاء") } },
        )
    }
    deletingPlaylist?.let { list ->
        AlertDialog(
            modifier = Modifier.padding(horizontal = 24.dp),
            properties =
                androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { deletingPlaylist = null },
            title = { Text("حذف قائمة ${list.name}؟") },
            text = { Text("تبقى المقاطع في المكتبة.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.removePlaylist(list.id)
                        playlist = null
                        deletingPlaylist = null
                    }
                ) {
                    Text("حذف")
                }
            },
            dismissButton = { TextButton(onClick = { deletingPlaylist = null }) { Text("إلغاء") } },
        )
    }
    restoreUri?.let { uri ->
        AlertDialog(
            modifier = Modifier.padding(horizontal = 24.dp),
            properties =
                androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { restoreUri = null },
            title = { Text("استعادة مكتبة صوتيات؟") },
            text = {
                Text(
                    "الاستعادة إلى مكتبة صوتيات فارغة فقط؛ لا تمس مشاويرك أو مصاريفك. يحوي السجل عناوين الملفات والقوائم والمفضلة والمواضع، وليس ملفات الصوت نفسها. قد تحتاج إعادة ربط الملفات بعد النقل إلى هاتف آخر."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.restore(uri)
                        restoreUri = null
                    }
                ) {
                    Text("استعادة")
                }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("إلغاء") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioAddSheet(
    onDismiss: () -> Unit,
    files: () -> Unit,
    folder: () -> Unit,
    scan: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "أضف إلى مكتبتك",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "إغلاق إضافة الصوتيات")
                }
            }
            sa.rihla.assistant.FeatureRow(
                "إضافة ملفات",
                "اختر مقطعًا أو عدة مقاطع",
                Icons.Default.AudioFile,
                files,
            )
            sa.rihla.assistant.FeatureRow(
                "إضافة مجلد",
                "أضف صوتيات مجلد كامل",
                Icons.Default.Folder,
                folder,
            )
            sa.rihla.assistant.FeatureRow(
                "فحص الهاتف",
                "اعثر على الصوتيات المحفوظة على الجهاز",
                Icons.Default.Search,
                scan,
            )
            Text(
                "تبقى الملفات الأصلية في مكانها. لا يتطلب تشغيلها اتصالًا بالإنترنت.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun AudioCover(track: AudioTrack, size: Int) {
    val image by
        produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, track.artwork) {
            value =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (track.artwork.isBlank()) null
                    else
                        runCatching {
                            android.graphics.BitmapFactory.decodeFile(track.artwork)
                                ?.asImageBitmap()
                        }
                            .getOrNull()
                }
        }
    Surface(
        Modifier.size(size.dp),
        shape = RoundedCornerShape((size / 6).dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        val cover = image
        if (cover != null) Image(cover, "غلاف المقطع", contentScale = ContentScale.Crop)
        else
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer,
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size((size * .72f).dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = .12f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size((size * .5f).dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = .08f),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.GraphicEq,
                            null,
                            Modifier.size((size * .3f).dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
    }
}
