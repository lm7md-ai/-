package sa.rihla.audio

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowContentResolver
import sa.rihla.RihlaApp

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=RihlaApp::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioPlaybackTest {
    @Test
    fun catalogRelocationRefreshesCurrentAndQueuedUrisWithoutLosingPlaybackPosition() {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        val first = AudioTrack("move-a", "content://audio-fixture/first.wav", "أول", durationMs=120000)
        val second = AudioTrack("move-b", "content://audio-fixture/second.wav", "ثان", durationMs=120000)
        runBlocking { app.audio.add(first); app.audio.add(second) }
        val controller = Robolectric.buildService(AudioPlaybackService::class.java).create()
        try {
            val player = AudioPlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }.get(controller.get()) as ExoPlayer
            player.setMediaItems(listOf(AudioViewModel.item(first), AudioViewModel.item(second)), 1, 20000)
            player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            player.shuffleModeEnabled = true
            runBlocking {
                app.audio.dao.relink(first.id, "content://audio-fixture/moved-first.wav")
                app.audio.dao.relink(second.id, "content://audio-fixture/moved-second.wav")
            }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (player.currentMediaItem?.localConfiguration?.uri.toString() != "content://audio-fixture/moved-second.wav" && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals("content://audio-fixture/moved-first.wav", player.getMediaItemAt(0).localConfiguration!!.uri.toString())
            assertEquals("content://audio-fixture/moved-second.wav", player.currentMediaItem!!.localConfiguration!!.uri.toString())
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals(20000L, player.currentPosition)
            assertFalse(player.playWhenReady)
            assertTrue(player.shuffleModeEnabled)
            assertEquals(androidx.media3.common.Player.REPEAT_MODE_ALL, player.repeatMode)
            assertTrue(app.getSharedPreferences("audio_player", Context.MODE_PRIVATE).getString("queue", "")!!.contains("moved-second.wav"))
        } finally { controller.destroy(); AudioLive.state.value = AudioPlayback() }
    }

    @Test fun phoneMediaMuteIsReportedAndRecoveryRestoresOnlyTheAppVolume() {
        val app=RuntimeEnvironment.getApplication() as RihlaApp
        val manager=app.getSystemService(android.media.AudioManager::class.java)
        manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,0,0)
        val service=Robolectric.buildService(AudioPlaybackService::class.java).create()
        try {
            val player=AudioPlaybackService::class.java.getDeclaredField("player").apply{isAccessible=true}.get(service.get()) as ExoPlayer
            player.volume=0f
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0,AudioLive.state.value.mediaVolume)
            app.getSharedPreferences("audio_player",Context.MODE_PRIVATE).edit().putBoolean("recover",true).apply()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1f,player.volume)
            assertEquals(0,manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
        } finally { service.destroy();AudioLive.state.value=AudioPlayback() }
    }

    @Test
    fun localWaveContentUriRecoversAfterReadErrorAndPlaysToAudioTrack() {
        val app=RuntimeEnvironment.getApplication() as RihlaApp
        val wave=File(app.cacheDir,"audio-fixture.wav")
        val pcmBytes=19200
        val bytes=ByteBuffer.allocate(44+pcmBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+pcmBytes);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1);putShort(2);putInt(48000);putInt(192000);putShort(4);putShort(16)
            put("data".toByteArray());putInt(pcmBytes)
            repeat(pcmBytes/4){putShort(1000);putShort(-1000)}
        }.array()
        wave.delete()
        val provider=WaveProvider(wave)
        provider.attachInfo(app,ProviderInfo().apply { authority="audio-fixture" })
        ShadowContentResolver.registerProviderInternal("audio-fixture",provider)
        val written=java.util.concurrent.atomic.AtomicInteger()
        val audible=java.util.concurrent.atomic.AtomicBoolean()
        val listener=ShadowAudioTrack.OnAudioDataWrittenListener { _,data,_ -> written.addAndGet(data.size);if(data.any { it!=0.toByte() }) audible.set(true) }
        ShadowAudioTrack.addAudioDataListener(listener)
        val service=Robolectric.buildService(AudioPlaybackService::class.java).create()
        try {
            val player=AudioPlaybackService::class.java.getDeclaredField("player").apply{isAccessible=true}.get(service.get()) as ExoPlayer
            val source=ProgressiveMediaSource.Factory(DefaultDataSource.Factory(app))
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
                .createMediaSource(MediaItem.Builder().setMediaId("wave-fixture").setUri("content://audio-fixture/test.wav").build())
            player.setMediaSource(source)
            player.prepare();player.play()
            val errorDeadline=System.nanoTime()+10_000_000_000L
            while(player.playerError==null && System.nanoTime()<errorDeadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50));Thread.sleep(10)
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(player.playerError)
            assertNotNull(AudioLive.state.value.error)
            wave.writeBytes(bytes)
            player.prepare();player.play() // Retry the same item; no media-item transition clears its old error.
            val deadline=System.nanoTime()+20_000_000_000L
            while(written.get()<pcmBytes && player.playerError==null && System.nanoTime()<deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
                Thread.sleep(10)
            }
            assertNull("Unexpected player error: ${player.playerError}",player.playerError)
            assertEquals(pcmBytes,written.get())
            assertTrue("Output PCM must contain nonzero samples",audible.get())
            assertEquals(1f,player.volume)
            assertNull(AudioLive.state.value.error)
        } finally {
            service.destroy();AudioLive.state.value=AudioPlayback()
            ShadowAudioTrack.removeAudioDataListener(listener);wave.delete()
        }
    }

    private class WaveProvider(private val file:File):ContentProvider() {
        override fun onCreate()=true
        override fun getType(uri:Uri)="audio/wav"
        override fun openFile(uri:Uri,mode:String)=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
    }

    @Test
    fun serviceRestoresQueuePausedAndPersistsPositionWithoutChangingJourneys() = runBlocking {
        val app=RuntimeEnvironment.getApplication() as RihlaApp
        val id=app.repository.start(false,1000)
        val prefs=app.getSharedPreferences("audio_player",Context.MODE_PRIVATE)
        prefs.edit().putString("queue","[{\"id\":\"fixture\",\"uri\":\"content://test/missing\",\"title\":\"مقطع اختبار\"}]")
            .putInt("queue_index",0).putLong("queue_position",20000).apply()
        app.audio.dao.insert(AudioTrack("fixture","content://test/missing","مقطع اختبار",durationMs=120000))
        val controller=Robolectric.buildService(AudioPlaybackService::class.java).create()
        try {
            val service=controller.get()
            val player=AudioPlaybackService::class.java.getDeclaredField("player").apply{isAccessible=true}.get(service) as ExoPlayer
            assertEquals(1,player.mediaItemCount)
            assertEquals("fixture",player.currentMediaItem!!.mediaId)
            assertFalse(player.playWhenReady)
            assertEquals(20000L,player.currentPosition)
            assertEquals(id,app.repository.dao.active()!!.id)
            for(scene in listOf(AudioScene.SPACE_3D,AudioScene.SURROUND_71,AudioScene.NATURAL)) {
                selectAudioScene(prefs,scene)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val replacement=AudioPlaybackService::class.java.getDeclaredField("player").apply{isAccessible=true}.get(service) as ExoPlayer
                assertEquals("fixture",replacement.currentMediaItem!!.mediaId)
                assertEquals(20000L,replacement.currentPosition)
                assertFalse(replacement.playWhenReady)
                assertEquals(scene.key,AudioLive.state.value.scene)
                assertEquals(id,app.repository.dao.active()!!.id)
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        } finally {
            controller.destroy()
            AudioLive.state.value=AudioPlayback()
        }
        assertTrue(prefs.getString("queue","")!!.contains("fixture"))
    }
}
