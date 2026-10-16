package sa.rihla.audio

import android.app.Application
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioOutputTest {
    @Test
    fun stereoPcmReachesAndroidAudioTrackWithWideningOffAndOn() {
        for (width in listOf(0f,1f)) {
            val processor=StereoSpaceProcessor().apply { amount=width }
            val sink=DefaultAudioSink.Builder(RuntimeEnvironment.getApplication()).setAudioProcessors(arrayOf(processor)).build()
            var bytesWritten=0
            val listener=ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ -> bytesWritten+=data.size }
            ShadowAudioTrack.addAudioDataListener(listener)
            try {
                sink.setListener(object:AudioSink.Listener {
                    override fun onPositionDiscontinuity() {}
                    override fun onUnderrun(bufferSize:Int,bufferSizeMs:Long,elapsedSinceLastFeedMs:Long) {}
                    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled:Boolean) {}
                })
                sink.configure(AudioSink.AudioSinkConfig.Builder(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(2).setSampleRate(48000).build()).build())
                sink.play()
                val input=ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder()).apply {
                    repeat(1024) { putShort(1000);putShort(-1000) };flip()
                }
                repeat(10) { if(input.hasRemaining()) sink.handleBuffer(input,0,1);Shadows.shadowOf(Looper.getMainLooper()).idle() }
                assertFalse(input.hasRemaining())
                assertEquals(4096,bytesWritten)
            } finally { sink.reset();ShadowAudioTrack.removeAudioDataListener(listener) }
        }
    }
}
