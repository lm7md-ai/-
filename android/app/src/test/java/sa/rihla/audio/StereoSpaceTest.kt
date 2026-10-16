package sa.rihla.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StereoSpaceTest {
    @Test
    fun sharedEmptyBufferBeforeAndBetweenPcmChunksIsHarmless() {
        for (width in listOf(0f,1f)) {
            val processor=StereoSpaceProcessor().apply { amount=width }
            processor.configure(AudioFormat(48000,2,C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertFalse(processor.output.hasRemaining())
            processor.queueInput(data(shortArrayOf(100)))
            assertFalse(processor.output.hasRemaining())
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertFalse(processor.output.hasRemaining())
            processor.queueInput(data(shortArrayOf(-100)))
            assertEquals(4,processor.output.remaining())
            processor.queueEndOfStream()
            assertTrue(processor.isEnded)
        }
    }
    @Test
    fun splitPcmFramesPreserveChannelAlignmentAcrossBuffers() {
        val processor=StereoSpaceProcessor();processor.configure(AudioFormat(48000,2,C.ENCODING_PCM_16BIT));processor.flush()
        processor.queueInput(data(shortArrayOf(1,2,3)))
        val first=processor.output.order(ByteOrder.nativeOrder());assertEquals(1.toShort(),first.short);assertEquals(2.toShort(),first.short)
        processor.queueInput(data(shortArrayOf(4)))
        val second=processor.output.order(ByteOrder.nativeOrder());assertEquals(3.toShort(),second.short);assertEquals(4.toShort(),second.short)
    }
    private fun data(values: ShortArray) = ByteBuffer.allocateDirect(values.size*2).order(ByteOrder.nativeOrder()).apply { values.forEach { putShort(it) }; flip() }
    @Test
    fun disabledProcessorPreservesPcmExactlyAndSpatialOutputStaysBounded() {
        val processor=StereoSpaceProcessor()
        processor.configure(AudioFormat(48000,2,C.ENCODING_PCM_16BIT));processor.flush()
        val samples=shortArrayOf(1000,-1000,32767,-32768,20000,20000,0,0)
        processor.queueInput(data(samples));val plain=processor.output.order(ByteOrder.nativeOrder())
        samples.forEach { assertEquals(it,plain.short) };assertFalse(plain.hasRemaining())
        processor.amount=1f;processor.flush()
        processor.queueInput(data(samples));val spatial=processor.output.order(ByteOrder.nativeOrder())
        assertEquals(samples.size*2,spatial.remaining())
        val output=ShortArray(samples.size){spatial.short}
        assertFalse(samples.contentEquals(output));assertTrue(output.all{it.toInt() in -32768..32767})
    }
    @Test
    fun monoAndMultichannelAreNotPretendedToBeSevenPointOne() {
        val processor=StereoSpaceProcessor()
        assertEquals(AudioFormat.NOT_SET,processor.configure(AudioFormat(48000,1,C.ENCODING_PCM_16BIT)))
        assertEquals(AudioFormat.NOT_SET,processor.configure(AudioFormat(48000,8,C.ENCODING_PCM_16BIT)))
        assertEquals(AudioFormat.NOT_SET,processor.configure(AudioFormat(48000,2,C.ENCODING_PCM_FLOAT)))
    }
    @Test
    fun voicePresetEmphasizesSpeechWithoutUnboundedGain() {
        assertEquals(3f,presetDb("voice",1000));assertEquals(-2f,presetDb("voice",80));assertEquals(0f,presetDb("flat",1000))
    }
}
