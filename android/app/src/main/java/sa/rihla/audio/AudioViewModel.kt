package sa.rihla.audio

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import sa.rihla.RihlaApp

data class AudioCatalog(val tracks: List<AudioTrack> = emptyList(), val playlists: List<AudioPlaylist> = emptyList(), val members: List<AudioPlaylistItem> = emptyList(), val bookmarks: List<AudioBookmark> = emptyList())

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as RihlaApp
    val repo = app.audio
    val catalog = combine(repo.dao.tracks(), repo.dao.playlists(), repo.dao.members(), repo.dao.bookmarks()) { t,p,m,b -> AudioCatalog(t,p,m,b) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioCatalog())
    val connected = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, AudioPlaybackService::class.java))).buildAsync()
    private var controller: MediaController? = null
    val prefs = app.getSharedPreferences("audio_player", Context.MODE_PRIVATE)
    init {
        future.addListener({
            try { controller = future.get(); connected.value = true }
            catch (_: Exception) { message.value = "تعذر الاتصال بالمشغل؛ أعد فتح المكتبة" }
        }, ContextCompat.getMainExecutor(app))
    }
    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = "تعذرت العملية. راجع الوصول للملفات واسم القائمة والمساحة المتاحة." }
        }
    }
    fun importFiles(uris: List<Uri>) = importing { repo.importFiles(uris) }
    fun importFolder(uri: Uri) = importing { repo.importFolder(uri) }
    fun scan() = importing { repo.scanPhone() }
    private fun importing(block: suspend () -> AudioImport) {
        if (busy.value) return
        busy.value = true
        work {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                message.value = "أُضيف ${result.added} · موجود مسبقًا ${result.updated} · تعذر ${result.failed}"
            } finally { busy.value = false }
        }
    }
    fun favorite(id: String) = work { repo.dao.favorite(id) }
    fun playlist(name: String) = work { repo.createPlaylist(name) }
    fun addToPlaylist(playlist: Long, id: String) = work { repo.addToPlaylist(playlist, id) }
    fun removePlaylist(id: Long) = work { repo.dao.removePlaylist(id) }
    fun removeMember(playlist: Long, id: String) = work { repo.dao.removeMember(playlist, id) }
    fun move(playlist: Long, first: AudioPlaylistItem, second: AudioPlaylistItem) = work { repo.swap(playlist, first, second) }
    fun remove(id: String) = work {
        controller?.let { p -> (p.mediaItemCount - 1 downTo 0).filter { p.getMediaItemAt(it).mediaId == id }.forEach { p.removeMediaItem(it) } }
        repo.dao.remove(id)
    }
    fun relink(id: String, uri: Uri) = work {
        withContext(Dispatchers.IO) { repo.relink(id,uri) }
        controller?.let { p ->
            val track = repo.dao.track(id) ?: return@let
            for (i in 0 until p.mediaItemCount) if (p.getMediaItemAt(i).mediaId == id) p.replaceMediaItem(i,item(track))
        }
        message.value = "أُعيد ربط الملف مع الاحتفاظ بالمفضلة والقوائم والمواضع"
    }
    fun restore(uri: Uri) {
        if (busy.value) return
        busy.value = true
        work {
            try {
                val count = withContext(Dispatchers.IO) {
                    val text = app.contentResolver.openInputStream(uri)!!.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size()+n <= AudioBackup.maxBytes); output.write(buffer,0,n) }
                        output.toString(Charsets.UTF_8.name())
                    }
                    AudioBackup(app.audioDatabase).restore(text)
                }
                message.value = "استُعيد $count مقطعًا. الملفات ليست داخل النسخة؛ أعد ربطها إن تغير موقعها أو إذن الوصول."
            } finally { busy.value = false }
        }
    }
    fun export(uri: Uri) {
        if (busy.value) return
        busy.value = true
        work {
            try {
                withContext(Dispatchers.IO) {
                    val data = AudioBackup(app.audioDatabase).export().toByteArray(Charsets.UTF_8)
                    app.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(data) }
                }
                message.value = "حُفظ سجل المكتبة؛ ملفات الصوت الأصلية تبقى في مكانها"
            } finally { busy.value = false }
        }
    }
    fun play(track: AudioTrack, tracks: List<AudioTrack>, at: Long? = null, listeningScene:AudioScene?=null) = work {
        val p = controller ?: return@work
        val tuning=listeningScene ?: repo.dao.tuning(track.id)?.let { row->AudioScene.entries.find { it.key==row.scene } }
        if(tuning!=null)selectAudioScene(prefs,tuning)
        val queue = tracks.distinctBy { it.id }.ifEmpty { listOf(track) }
        val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        p.setMediaItems(queue.map(::item), index, at?.coerceIn(0,track.durationMs) ?: audioResume(track.positionMs, track.durationMs))
        p.prepare(); p.play()
    }
    fun enqueue(track: AudioTrack) { controller?.addMediaItem(item(track)); message.value = "أضيف المقطع لقائمة الانتظار" }
    fun nextInQueue(track: AudioTrack) { controller?.let { it.addMediaItem((it.currentMediaItemIndex + 1).coerceIn(0, it.mediaItemCount), item(track)) } }
    fun removeQueued(index: Int) { controller?.let { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) } }
    fun moveQueued(index: Int, offset: Int) { controller?.let { p -> val target = index + offset; if (index in 0 until p.mediaItemCount && target in 0 until p.mediaItemCount) p.moveMediaItem(index,target) } }
    fun selectQueued(index: Int) { controller?.seekToDefaultPosition(index); controller?.play() }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.prepare(); it.play() } } }
    fun pause() { controller?.pause() }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun seek(position: Long) { controller?.let { it.seekTo(position.coerceIn(0, it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) } }
    fun jump(amount: Long) { controller?.let { seek(it.currentPosition + amount) } }
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun repeat() { controller?.let { it.repeatMode = (it.repeatMode + 1) % 3 } }
    fun volume(value: Float) { controller?.volume = value.coerceIn(0f,1f) }
    fun speed(value: Float) { prefs.edit().putFloat("speed", value.coerceIn(.5f,2f)).apply() }
    fun sleep(minutes: Int) { prefs.edit().putLong("sleep_at", if (minutes <= 0) 0 else System.currentTimeMillis() + minutes * 60_000L).putBoolean("sleep_end",false).apply() }
    fun sleepAtEnd() { prefs.edit().putLong("sleep_at",0).putBoolean("sleep_end",true).apply() }
    fun silence(enabled: Boolean) { prefs.edit().putBoolean("silence",enabled).apply() }
    fun bookmark() { val state = AudioLive.state.value; if (state.id.isNotBlank()) work { repo.dao.bookmark(AudioBookmark(trackId = state.id, positionMs = state.position, label = audioTime(state.position))) } }
    fun deleteBookmark(id: Long) = work { repo.dao.removeBookmark(id) }
    fun soundFloat(key: String, value: Float) { require(key == "width"); prefs.edit().putString("scene","custom").putFloat(key,value.coerceIn(0f,1f)).apply() }
    fun soundInt(key: String, value: Int) { require(key in listOf("bass", "surround")); prefs.edit().putString("scene","custom").putInt(key,value.coerceIn(0,1000)).apply() }
    fun equalizer(enabled: Boolean) { prefs.edit().putString("scene","custom").putBoolean("eq_enabled",enabled).apply() }
    fun preset(name: String) { require(name in listOf("flat","voice","bass","bright")); prefs.edit().putString("scene","custom").putString("eq_preset",name).remove("eq_levels").putBoolean("eq_enabled",true).apply() }
    fun band(index: Int, value: Float) {
        val bands = AudioLive.state.value.sound.bands
        val levels = org.json.JSONArray(bands.map { if (it.index == index) value.coerceIn(it.min,it.max) else it.db })
        prefs.edit().putString("scene","custom").putString("eq_levels",levels.toString()).putBoolean("eq_enabled",true).apply()
    }
    fun scene(scene:AudioScene) { selectAudioScene(prefs,scene) }
    fun resetSound() { scene(AudioScene.NATURAL) }
    fun recoverSound() {
        selectAudioScene(prefs,AudioScene.NATURAL)
        prefs.edit().putBoolean("silence",false).putLong("sleep_at",0).putBoolean("sleep_end",false).putBoolean("recover",true).apply()
    }
    override fun onCleared() { MediaController.releaseFuture(future); super.onCleared() }
    companion object {
        fun item(track: AudioTrack): MediaItem = MediaItem.Builder().setMediaId(track.id).setUri(track.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
                .apply { if (track.artwork.isNotBlank() && java.io.File(track.artwork).exists()) setArtworkUri(Uri.fromFile(java.io.File(track.artwork))) }.build()).build()
    }
}
