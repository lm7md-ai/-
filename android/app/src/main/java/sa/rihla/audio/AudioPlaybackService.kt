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
import kotlinx.coroutines.flow.MutableStateFlow
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
)
object AudioLive { val state = MutableStateFlow(AudioPlayback()) }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var sound: AudioSound
    private val stereo = StereoSpaceProcessor()
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
        if (key in listOf("width", "eq_enabled", "eq_levels", "eq_preset", "bass", "surround", "sleep_at", "speed", "silence", "sleep_end"))
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
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(stereo)).build()
        }
        player = ExoPlayer.Builder(this, renderers).build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        sound = AudioSound(this)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                channels = format.channelCount.coerceAtLeast(0); codec = format.sampleMimeType.orEmpty(); publish()
            }
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                sound.connect(audioSessionId); applySettings()
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && prefs.getBoolean("sleep_end",false)) {
                    prefs.edit().putBoolean("sleep_end",false).apply(); player.pause()
                }
            }
            override fun onEvents(player: Player, events: Player.Events) { publish(); persist(events.contains(Player.EVENT_TIMELINE_CHANGED)) }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                val id = oldPosition.mediaItem?.mediaId ?: return
                val position = oldPosition.positionMs.coerceAtLeast(0)
                saves.trySend(Triple(id, position, if (player.isPlaying) System.currentTimeMillis() else 0))
            }
            override fun onPlayerError(error: PlaybackException) {
                playbackError = audioPlaybackError(error)
                publish()
            }
            override fun onPlayerErrorChanged(error: PlaybackException?) {
                if (error == null) { playbackError = null; publish() }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                playbackError = null
                if (prefs.getBoolean("sleep_end",false) && reason in listOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) {
                    prefs.edit().putBoolean("sleep_end",false).apply(); player.pause()
                }
            }
        })
        val open = android.app.PendingIntent.getActivity(this, 90, android.content.Intent(this, sa.rihla.MainActivity::class.java).putExtra("open_audio", true), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        prefs.registerOnSharedPreferenceChangeListener(settingsListener)
        getSystemService(android.media.AudioManager::class.java).registerAudioDeviceCallback(devices,handler)
        applySettings()
        restoreQueue()
        handler.post(tick)
    }
    private fun applySettings() {
        stereo.amount = prefs.getFloat("width", 0f).coerceIn(0f, 1f)
        soundStatus = sound.apply()
        sleepAt = prefs.getLong("sleep_at", 0)
        val speed = prefs.getFloat("speed", 1f).coerceIn(.5f, 2f)
        if (player.playbackParameters.speed != speed) player.playbackParameters = PlaybackParameters(speed)
        player.skipSilenceEnabled = prefs.getBoolean("silence",false)
        soundRevision++
        publish()
    }
    private fun publish() {
        if (destroying) return
        AudioLive.state.value = AudioPlayback(
            player.currentMediaItem?.mediaId.orEmpty(), player.mediaMetadata.title?.toString().orEmpty(), player.isPlaying,
            player.currentPosition.coerceAtLeast(0), player.duration.takeIf { it > 0 } ?: 0,
            player.playbackState == Player.STATE_BUFFERING,
            (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }, player.repeatMode,
            player.shuffleModeEnabled, player.playbackParameters.speed, player.volume, sleepAt, playbackError, soundStatus,
            channels,codec,player.skipSilenceEnabled,prefs.getBoolean("sleep_end",false),soundRevision)
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
