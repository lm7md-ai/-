package sa.rihla.audio

import android.app.Application
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioScenesTest {
    @Test fun scenesHaveDistinctAudibleOutputForMonoAndStereoAndDoNotCreateNoiseFromSilence() {
        for(channels in listOf(1,2)) {
            val results=mutableListOf<ByteArray>()
            for(scene in listOf(AudioScene.SPACE_3D,AudioScene.SURROUND_71)) {
                val processor=StereoSpaceProcessor().apply { this.scene=scene }
                assertEquals(2,processor.configure(AudioProcessor.AudioFormat(48000,channels,C.ENCODING_PCM_16BIT)).channelCount)
                processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
                processor.queueInput(AudioProcessor.EMPTY_BUFFER)
                processor.queueInput(ByteBuffer.allocateDirect(4800*channels*2).order(ByteOrder.nativeOrder()).apply { repeat(4800*channels){putShort(4000)};flip() })
                val output=processor.output
                val bytes=ByteArray(output.remaining());output.get(bytes);results+=bytes
                assertEquals(4800*4,bytes.size)
                val samples=ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer()
                while(samples.hasRemaining()) assertTrue(samples.get().toInt() in 1000..4000)
                processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
                processor.queueInput(ByteBuffer.allocateDirect(4800*channels*2).order(ByteOrder.nativeOrder()))
                val silent=processor.output
                while(silent.hasRemaining()) assertEquals(0.toByte(),silent.get())
            }
            assertFalse(results[0].contentEquals(results[1]))
        }
    }
    @Test fun naturalSceneReleasesNativeEffectsAndPresetsDoNotOverwriteQueueOrHistoryReferences() {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("audio_player",Context.MODE_PRIVATE)
        prefs.edit().putString("queue","keep-queue").putLong("queue_position",22000).putBoolean("eq_enabled",true).putInt("bass",900).apply()
        selectAudioScene(prefs,AudioScene.SURROUND_71)
        assertEquals(AudioScene.SURROUND_71,AudioScene.read(prefs));assertTrue(prefs.getBoolean("eq_enabled",false))
        assertEquals("keep-queue",prefs.getString("queue",null));assertEquals(22000L,prefs.getLong("queue_position",0))
        val effects=AudioSound(app);effects.connect(23);effects.apply();assertFalse(effects.hasNativeEffects())
        selectAudioScene(prefs,AudioScene.NATURAL);effects.apply();assertFalse(effects.hasNativeEffects());effects.release()
    }
}
