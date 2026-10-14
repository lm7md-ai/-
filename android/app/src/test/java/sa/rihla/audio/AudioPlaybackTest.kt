package sa.rihla.audio

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import sa.rihla.RihlaApp

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=RihlaApp::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioPlaybackTest {
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
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        } finally {
            controller.destroy()
            AudioLive.state.value=AudioPlayback()
        }
        assertTrue(prefs.getString("queue","")!!.contains("fixture"))
    }
}
