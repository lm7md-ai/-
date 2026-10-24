package sa.rihla.review

import android.content.*
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import sa.rihla.RihlaApp
import sa.rihla.audio.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ReviewAudioSceneTest {
    private suspend fun until(condition: () -> Boolean) =
        withTimeout(15000) {
            while (!condition()) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }

    private fun checkAutomaticSelection(fromViewModel: Boolean) = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        val prefs = app.getSharedPreferences("audio_player", Context.MODE_PRIVATE)
        prefs
            .edit()
            .clear()
            .putString("scene", "custom")
            .putInt("bass", 450)
            .putInt("surround", 350)
            .putBoolean("eq_enabled", true)
            .putString("eq_levels", "[1,2,3]")
            .putString("eq_preset", "voice")
            .putFloat("width", .6f)
            .apply()
        val manual = prefs.all.filterKeys { it != "scene" }
        val first =
            AudioTrack(
                "scene-first",
                "content://review.audio/first",
                "مقطع تجريبي ألف",
                durationMs = 120000,
            )
        val second =
            AudioTrack(
                "scene-second",
                "content://review.audio/second",
                "مقطع تجريبي باء",
                durationMs = 120000,
            )
        app.audio.add(first)
        app.audio.add(second)
        app.audio.dao.tuning(AudioTuning(first.id, "3d"))
        app.audio.dao.tuning(AudioTuning(second.id, "7.1"))
        val owned = Robolectric.buildService(AudioPlaybackService::class.java).create()
        var vm: AudioViewModel? = null
        try {
            if (fromViewModel) {
                Shadows.shadowOf(app)
                    .setComponentNameAndServiceForBindService(
                        ComponentName(app, AudioPlaybackService::class.java),
                        owned.get().onBind(Intent("androidx.media3.session.MediaSessionService")),
                    )
                vm = AudioViewModel(app)
                val field =
                    AudioViewModel::class.java.getDeclaredField("controller").apply {
                        isAccessible = true
                    }
                until { field.get(vm) != null }
                vm.play(first, listOf(first))
                until { AudioScene.read(prefs) == AudioScene.SPACE_3D }
            } else {
                val player =
                    AudioPlaybackService::class
                        .java
                        .getDeclaredField("player")
                        .apply { isAccessible = true }
                        .get(owned.get()) as ExoPlayer
                player.setMediaItems(
                    listOf(AudioViewModel.item(first), AudioViewModel.item(second)),
                    0,
                    0,
                )
                player.seekTo(1, 0)
                until { AudioScene.read(prefs) == AudioScene.SURROUND_71 }
            }
            for ((key, value) in manual) assertEquals(key, value, prefs.all[key])
        } finally {
            vm?.viewModelScope?.cancel()
            vm?.let {
                AudioViewModel::class
                    .java
                    .getDeclaredMethod("onCleared")
                    .apply { isAccessible = true }
                    .invoke(it)
            }
            owned.destroy()
            AudioLive.state.value = AudioPlayback()
        }
    }

    @Test fun savedTuningDuringExplicitPlayKeepsManualEffects() = checkAutomaticSelection(true)

    @Test fun savedTuningOnQueueTransitionKeepsManualEffects() = checkAutomaticSelection(false)
}
