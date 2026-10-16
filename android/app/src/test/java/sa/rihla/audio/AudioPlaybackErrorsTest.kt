package sa.rihla.audio

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class AudioPlaybackErrorsTest {
    private fun message(code: Int) = audioPlaybackError(PlaybackException("content://private-provider/secret.mp3", null, code))

    @Test
    fun internalAndOutputFailuresDoNotTellUserToRelinkValidFiles() {
        for (code in listOf(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)) {
            val text=message(code)
            assertTrue(text.contains(code.toString()))
            assertFalse(text.contains("إعادة ربط"))
            assertFalse(text.contains("private-provider"))
        }
    }

    @Test
    fun accessAndDecoderFailuresHaveDifferentRecoveryAdvice() {
        assertTrue(message(PlaybackException.ERROR_CODE_IO_NO_PERMISSION).contains("أعد ربطه"))
        assertTrue(message(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND).contains("إعادة ربط"))
        assertTrue(message(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED).contains("فك ترميز"))
        assertTrue(message(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED).contains("احفظ المقطع على الهاتف"))
    }
}
