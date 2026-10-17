package sa.rihla.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.*

/** Reversible listening treatment, not reconstruction of clipped/missing speech. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LectureAudioProcessor(private val scene: AudioScene) : BaseAudioProcessor() {
    private var filters = emptyArray<Array<Biquad>>()
    private var envelopes = FloatArray(0)
    private var pending: Short? = null

    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (format.encoding == C.ENCODING_PCM_16BIT && format.channelCount in 1..2) format
        else AudioProcessor.AudioFormat.NOT_SET

    override fun onFlush(metadata: AudioProcessor.StreamMetadata) {
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        filters =
            Array(inputAudioFormat.channelCount.coerceIn(1, 2)) {
                if (scene == AudioScene.LECTURE_SOFT)
                    arrayOf(Biquad.highPass(rate, 75.0), Biquad.lowPass(rate, 3500.0))
                else
                    arrayOf(
                        Biquad.highPass(rate, 120.0),
                        Biquad.peak(rate, 1800.0, 3.0),
                        Biquad.lowPass(rate, 6000.0),
                    )
            }
        envelopes = FloatArray(filters.size)
        pending = null
    }

    override fun queueInput(input: ByteBuffer) {
        if (!input.hasRemaining()) return
        val frameBytes = filters.size * 2
        val output =
            replaceOutputBuffer(
                (input.remaining() + (if (pending != null) 2 else 0)) / frameBytes * frameBytes
            )
        while (input.remaining() >= (if (pending != null) 2 else frameBytes)) for (channel in
            filters.indices) {
            val sample =
                if (channel == 0 && pending != null) pending!!.also { pending = null }
                else input.short
            var value = sample / 32768.0
            for (filter in filters[channel]) value = filter.process(value)
            val magnitude = abs(value).toFloat()
            val smoothing = if (magnitude > envelopes[channel]) .08f else .001f
            envelopes[channel] += smoothing * (magnitude - envelopes[channel])
            // Boost quiet speech modestly; don't amplify digital silence/noise floors.
            val gain =
                if (scene == AudioScene.LECTURE_CLEAR && envelopes[channel] > .004f)
                    (0.14f / envelopes[channel]).coerceIn(.6f, 1.7f)
                else 1f
            value *= gain
            val limited = value / (1.0 + max(0.0, abs(value) - .75) * 2.0)
            output.putShort((limited * 32767).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        if (filters.size == 2 && input.remaining() >= 2) pending = input.short
        output.flip()
    }
}

private class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        private fun make(rate: Int, hz: Double, kind: Int, db: Double = 0.0): Biquad {
            val w = 2 * PI * hz.coerceAtMost(rate * .42) / rate
            val c = cos(w)
            val alpha = sin(w) / (2 * .7071)
            val gain = 10.0.pow(db / 40)
            val a0 = if (kind == 2) 1 + alpha / gain else 1 + alpha
            val b =
                when (kind) {
                    0 -> doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2)
                    1 -> doubleArrayOf((1 - c) / 2, 1 - c, (1 - c) / 2)
                    else -> doubleArrayOf(1 + alpha * gain, -2 * c, 1 - alpha * gain)
                }
            return Biquad(
                b[0] / a0,
                b[1] / a0,
                b[2] / a0,
                -2 * c / a0,
                (if (kind == 2) 1 - alpha / gain else 1 - alpha) / a0,
            )
        }

        fun highPass(rate: Int, hz: Double) = make(rate, hz, 0)

        fun lowPass(rate: Int, hz: Double) = make(rate, hz, 1)

        fun peak(rate: Int, hz: Double, db: Double) = make(rate, hz, 2, db)
    }
}
