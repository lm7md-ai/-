package sa.rihla.audio

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import sa.rihla.RihlaApp

data class AudioPlayback(
    val id: String = "", val title: String = "", val playing: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val buffering: Boolean = false,
    val queue: List<String> = emptyList(), val repeat: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false, val speed: Float = 1f, val volume: Float = 1f,
    val sleepAt: Long = 0, val error: String? = null, val sound: SoundSupport = SoundSupport(),
    val channels: Int = 0, val codec: String = "", val silence: Boolean = false, val sleepEnd: Boolean = false,
    val soundRevision: Long = 0,
    val scene:String="natural", val mediaVolume:Int=0, val mediaMax:Int=1, val muted:Boolean=false, val suppression:Int=0,
)
object AudioLive { val state = MutableStateFlow(AudioPlayback()) }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var sound: AudioSound
    private var stereo = StereoSpaceProcessor()
    private var currentScene=AudioScene.NATURAL
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app get() = application as RihlaApp
    private val prefs by lazy { getSharedPreferences("audio_player", Context.MODE_PRIVATE) }
    private var soundStatus = SoundSupport()
    @Volatile private var playbackError: String? = null
    private var channels = 0
    private var codec = ""
    private var sleepAt = 0L
    private var lastSave = 0L
    private var destroying = false
    private var soundRevision = 0L
    private val saves = Channel<Triple<String, Long, Long>>(Channel.UNLIMITED)
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in listOf("scene", "recover", "width", "eq_enabled", "eq_levels", "eq_preset", "bass", "surround", "sleep_at", "speed", "silence", "sleep_end"))
            handler.post { if (session != null) applySettings() }
    }
    private val devices = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) { if (session != null) applySettings() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) { if (session != null) applySettings() }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (session == null) return
            if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                sleepAt = 0; prefs.edit().putLong("sleep_at", 0).apply(); player.pause()
            }
            publish()
            if (player.isPlaying && System.currentTimeMillis() - lastSave >= 5000) { persist(); lastSave = System.currentTimeMillis() }
            handler.postDelayed(this, if (player.isPlaying) 1000 else 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for ((id, position, time) in saves) {
                try { app.audio.dao.progress(id, position, time) }
                catch (_: Exception) { playbackError = "تعذر حفظ موضع الاستماع" }
            }
            scope.cancel()
        }
        sound = AudioSound(this)
        createPlayer()
        val open = android.app.PendingIntent.getActivity(this, 90, android.content.Intent(this, sa.rihla.MainActivity::class.java).putExtra("open_audio", true), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        prefs.registerOnSharedPreferenceChangeListener(settingsListener)
        getSystemService(android.media.AudioManager::class.java).registerAudioDeviceCallback(devices,handler)
        applySettings()
        restoreQueue()
        scope.launch {
            app.audio.dao.tracks()
                .map { tracks -> tracks.associate { it.id to it.uri } }
                .distinctUntilChanged()
                .catch { playbackError = "تعذر تحديث مواقع الملفات في قائمة التشغيل" }
                .collect { uris ->
                    handler.post { if (!destroying) refreshUris(uris) }
                }
        }
        handler.post(tick)
    }
    private fun refreshUris(uris: Map<String, String>) {
        val originalIndex = player.currentMediaItemIndex
        val originalPosition = player.currentPosition.coerceAtLeast(0)
        val playWhenReady = player.playWhenReady
        var changed = false
        var currentChanged = false
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            val uri = uris[item.mediaId] ?: continue
            if (item.localConfiguration?.uri.toString() == uri) continue
            if (index == originalIndex) currentChanged = true
            player.replaceMediaItem(index, item.buildUpon().setUri(uri).build())
            changed = true
        }
        if (changed) {
            if (originalIndex in 0 until player.mediaItemCount) player.seekTo(originalIndex, originalPosition)
            player.playWhenReady = playWhenReady
            if (currentChanged && player.playerError != null) player.prepare()
            persist(true)
        }
    }

    private fun createPlayer() {
        currentScene=AudioScene.read(prefs)
        stereo=StereoSpaceProcessor().apply { scene=currentScene;amount=prefs.getFloat("width",0f) }
        val renderers = AudioRenderersFactory(this,currentScene,stereo)
        player = ExoPlayer.Builder(this, renderers).build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        val ownedPlayer=player
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                if (player !== ownedPlayer) return
                channels = format.channelCount.coerceAtLeast(0); codec = format.sampleMimeType.orEmpty(); publish()
            }
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                if (player !== ownedPlayer) return
                sound.connect(audioSessionId); applySettings()
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== ownedPlayer) return
                if (playbackState == Player.STATE_ENDED && prefs.getBoolean("sleep_end",false)) {
                    prefs.edit().putBoolean("sleep_end",false).apply(); player.pause()
                }
            }
            override fun onEvents(player: Player, events: Player.Events) { if (player===this@AudioPlaybackService.player) { publish(); persist(events.contains(Player.EVENT_TIMELINE_CHANGED)) } }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (player !== ownedPlayer) return
                val id = oldPosition.mediaItem?.mediaId ?: return
                val position = oldPosition.positionMs.coerceAtLeast(0)
                saves.trySend(Triple(id, position, if (player.isPlaying) System.currentTimeMillis() else 0))
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player !== ownedPlayer) return
                playbackError = audioPlaybackError(error)
                publish()
            }
            override fun onPlayerErrorChanged(error: PlaybackException?) {
                if (player !== ownedPlayer) return
                if (error == null) { playbackError = null; publish() }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player !== ownedPlayer) return
                playbackError = null
                if(reason!=Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) mediaItem?.mediaId?.let { id -> scope.launch {
                    val tuning=app.audio.dao.tuning(id) ?: return@launch
                    val scene=AudioScene.entries.find { it.key==tuning.scene } ?: return@launch
                    handler.post { if(!destroying && player.currentMediaItem?.mediaId==id && AudioScene.read(prefs)!=scene) selectAudioScene(prefs,scene) }
                } }
                if (prefs.getBoolean("sleep_end",false) && reason in listOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) {
                    prefs.edit().putBoolean("sleep_end",false).apply(); player.pause()
                }
            }
        })
    }
    private fun changeScene() {
        val old=player
        val items=(0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index=old.currentMediaItemIndex.coerceAtLeast(0)
        val position=old.currentPosition.coerceAtLeast(0)
        val playing=old.playWhenReady
        persist(true)
        sound.connect(0)
        createPlayer()
        player.volume=old.volume
        player.repeatMode=old.repeatMode;player.shuffleModeEnabled=old.shuffleModeEnabled
        session?.setPlayer(player)
        old.release()
        if(items.isNotEmpty()) {
            player.setMediaItems(items,index.coerceIn(items.indices),position)
            player.prepare();player.playWhenReady=playing
        }
    }
    private fun applySettings() {
        if (AudioScene.read(prefs)!=currentScene) changeScene()
        stereo.scene=currentScene
        stereo.amount = prefs.getFloat("width", 0f).coerceIn(0f, 1f)
        soundStatus = sound.apply()
        sleepAt = prefs.getLong("sleep_at", 0)
        val speed = prefs.getFloat("speed", 1f).coerceIn(.5f, 2f)
        if (player.playbackParameters.speed != speed) player.playbackParameters = PlaybackParameters(speed)
        player.skipSilenceEnabled = prefs.getBoolean("silence",false)
        if (prefs.getBoolean("recover",false)) {
            prefs.edit().putBoolean("recover",false).apply()
            player.volume=1f;player.setPreferredAudioDevice(null)
            playbackError=null
            if(player.mediaItemCount>0) { player.prepare();player.play() }
        }
        soundRevision++
        publish()
    }
    private fun publish() {
        if (destroying) return
        val manager=getSystemService(android.media.AudioManager::class.java)
        AudioLive.state.value = AudioPlayback(
            player.currentMediaItem?.mediaId.orEmpty(), player.mediaMetadata.title?.toString().orEmpty(), player.isPlaying,
            player.currentPosition.coerceAtLeast(0), player.duration.takeIf { it > 0 } ?: 0,
            player.playbackState == Player.STATE_BUFFERING,
            (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }, player.repeatMode,
            player.shuffleModeEnabled, player.playbackParameters.speed, player.volume, sleepAt, playbackError, soundStatus,
            channels,codec,player.skipSilenceEnabled,prefs.getBoolean("sleep_end",false),soundRevision,currentScene.key,
            manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC),manager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
            manager.isStreamMute(android.media.AudioManager.STREAM_MUSIC),player.playbackSuppressionReason)
    }
    private fun persist(includeQueue: Boolean = false) {
        if (destroying) return
        val editor = prefs.edit()
        if (includeQueue) {
            val items = org.json.JSONArray()
            for (index in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(index)
                items.put(org.json.JSONObject().put("id", item.mediaId).put("uri", item.localConfiguration?.uri.toString())
                    .put("title", item.mediaMetadata.title?.toString().orEmpty()))
            }
            editor.putString("queue", items.toString())
        }
        val position = player.currentPosition.coerceAtLeast(0)
        editor.putInt("queue_index", player.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("queue_position", position).putBoolean("shuffle", player.shuffleModeEnabled).putInt("repeat", player.repeatMode).apply()
        val id = player.currentMediaItem?.mediaId ?: return
        saves.trySend(Triple(id, position, if (player.isPlaying) System.currentTimeMillis() else 0))
    }
    private fun restoreQueue() {
        runCatching {
            val saved = org.json.JSONArray(prefs.getString("queue", "[]"))
            val items = (0 until saved.length()).mapNotNull { i ->
                val row = saved.getJSONObject(i); val uri = row.getString("uri")
                if (!uri.startsWith("content:")) null else MediaItem.Builder().setMediaId(row.getString("id")).setUri(uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(row.optString("title")).build()).build()
            }
            if (items.isNotEmpty()) {
                player.setMediaItems(items, prefs.getInt("queue_index", 0).coerceIn(items.indices), prefs.getLong("queue_position", 0).coerceAtLeast(0))
                player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
                player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF).coerceIn(0, 2)
                player.prepare() // Restored queues remain paused; only a user play command starts audio.
            }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.uid == android.os.Process.myUid() || controllerInfo.isTrusted) session else null

    override fun onDestroy() {
        if (::player.isInitialized) persist(true)
        destroying = true
        prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
        getSystemService(android.media.AudioManager::class.java).unregisterAudioDeviceCallback(devices)
        handler.removeCallbacksAndMessages(null)
        session?.release(); session = null
        if (::player.isInitialized) player.release()
        if (::sound.isInitialized) sound.release()
        AudioLive.state.value = AudioLive.state.value.copy(playing = false, buffering = false)
        saves.close() // The worker drains ordered progress writes before releasing its scope.
        super.onDestroy()
    }
}
