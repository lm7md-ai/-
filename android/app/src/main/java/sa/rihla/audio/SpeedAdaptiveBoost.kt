package sa.rihla.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class SpeedAdaptiveSnapshot(
    val enabled: Boolean = true,
    val outingActive: Boolean = false,
    val speedKmh: Float = 0f,
    val smoothedFactor: Float = 0f, // 0.0 (natural <40 km/h) .. 1.0 (highway >=110 km/h)
    val volumeTrimMultiplier: Float = 1f, // 1.00 .. 1.15 (+15% max)
    val presenceBoostDb: Float = 0f, // 0.0 .. +4.5 dB around 1.8kHz-3kHz
) {
    val activeBoost: Boolean
        get() = enabled && outingActive && smoothedFactor > 0.04f

    val boostPercent: Int
        get() = ((volumeTrimMultiplier - 1f) * 100f).roundToInt().coerceAtLeast(0)
}

/**
 * Real-time speed-adaptive lecture clarity & subtle volume compensation for 2016 Lancer road noise.
 * Smoothly ramps between 40 km/h (0%) and 110 km/h (100% -> +15% volume compensation + 4.5dB speech presence).
 */
object SpeedAdaptiveAudio {
    val state = MutableStateFlow(SpeedAdaptiveSnapshot())

    @Volatile var presenceBoostFactor: Float = 0f
        private set

    fun targetFactorForSpeedKmh(speedKmh: Float, enabled: Boolean, outingActive: Boolean): Float {
        if (!enabled || !outingActive || !speedKmh.isFinite() || speedKmh < 40f) return 0f
        return ((speedKmh - 40f) / 70f).coerceIn(0f, 1f)
    }

    fun stepTowards(currentFactor: Float, targetFactor: Float, alpha: Float = 0.22f): Float {
        val next = currentFactor + (targetFactor - currentFactor) * alpha.coerceIn(0.02f, 1f)
        return if (next < 0.005f && targetFactor == 0f) 0f else next.coerceIn(0f, 1f)
    }

    /**
     * At 60 km/h (factor ~0.286) -> ~1.06..1.08 (+8%), at 110+ km/h (factor 1.0) -> 1.15 (+15% max).
     */
    fun volumeMultiplierForFactor(factor: Float): Float {
        val f = factor.coerceIn(0f, 1f)
        if (f <= 0f) return 1.0f
        // Smooth concave curve hitting ~1.08 at 60 km/h and 1.15 at 110 km/h
        val curved = sqrt(f) * 0.6f + f * 0.4f
        return (1.0f + 0.15f * curved).coerceIn(1.0f, 1.15f)
    }

    fun updateSpeed(speedMetersPerSec: Double, outingActive: Boolean, enabled: Boolean) {
        val kmh =
            if (outingActive && speedMetersPerSec.isFinite() && speedMetersPerSec > 0)
                (speedMetersPerSec * 3.6).toFloat()
            else 0f
        state.update { prev ->
            val target = targetFactorForSpeedKmh(kmh, enabled, outingActive)
            val smoothed = stepTowards(prev.smoothedFactor, target)
            presenceBoostFactor = smoothed
            SpeedAdaptiveSnapshot(
                enabled = enabled,
                outingActive = outingActive,
                speedKmh = kmh,
                smoothedFactor = smoothed,
                volumeTrimMultiplier = volumeMultiplierForFactor(smoothed),
                presenceBoostDb = smoothed * 4.5f,
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        state.update { prev ->
            val target = targetFactorForSpeedKmh(prev.speedKmh, enabled, prev.outingActive)
            val smoothed = if (!enabled) 0f else stepTowards(prev.smoothedFactor, target, 0.5f)
            presenceBoostFactor = smoothed
            prev.copy(
                enabled = enabled,
                smoothedFactor = smoothed,
                volumeTrimMultiplier = volumeMultiplierForFactor(smoothed),
                presenceBoostDb = smoothed * 4.5f,
            )
        }
    }

    fun clearOuting() {
        presenceBoostFactor = 0f
        state.update {
            it.copy(
                outingActive = false,
                speedKmh = 0f,
                smoothedFactor = 0f,
                volumeTrimMultiplier = 1f,
                presenceBoostDb = 0f,
            )
        }
    }

    fun reset() = clearOuting()
}

/**
 * Media3 PCM processor applying real-time sample-smoothed speech presence boost (1.8kHz–3kHz),
 * dynamic quiet-syllable lift, and +8%..+15% road-noise compensation when SpeedAdaptiveAudio is active.
 * 100% bit-transparent when SpeedAdaptiveAudio.presenceBoostFactor == 0f.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SpeedAdaptiveAudioProcessor : BaseAudioProcessor() {
    private var presenceFilters = emptyArray<PresencePeakFilter>()
    private var envelopes = FloatArray(0)
    private var sampleSmoothedFactor = 0f
    private var pending: Short? = null

    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (format.encoding == C.ENCODING_PCM_16BIT && format.channelCount in 1..2) format
        else AudioProcessor.AudioFormat.NOT_SET

    override fun onFlush(metadata: AudioProcessor.StreamMetadata) {
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        val ch = inputAudioFormat.channelCount.coerceIn(1, 2)
        presenceFilters = Array(ch) { PresencePeakFilter(rate, 2350.0, 4.5) }
        envelopes = FloatArray(ch)
        pending = null
    }

    override fun queueInput(input: ByteBuffer) {
        if (!input.hasRemaining()) return
        val targetFactor = SpeedAdaptiveAudio.presenceBoostFactor
        // Bit-transparent fast path when parked or <40 km/h and ramped down to 0
        if (targetFactor == 0f && sampleSmoothedFactor < 0.0005f && pending == null) {
            sampleSmoothedFactor = 0f
            val out = replaceOutputBuffer(input.remaining())
            out.put(input)
            out.flip()
            return
        }
        val chCount = presenceFilters.size.coerceAtLeast(1)
        val frameBytes = chCount * 2
        val output =
            replaceOutputBuffer(
                (input.remaining() + (if (pending != null) 2 else 0)) / frameBytes * frameBytes
            )
        while (input.remaining() >= (if (pending != null) 2 else frameBytes)) {
            sampleSmoothedFactor += 0.00025f * (targetFactor - sampleSmoothedFactor)
            val f = sampleSmoothedFactor.coerceIn(0f, 1f)
            val volMul = SpeedAdaptiveAudio.volumeMultiplierForFactor(f)
            for (ch in 0 until chCount) {
                val rawShort =
                    if (ch == 0 && pending != null) pending!!.also { pending = null }
                    else input.short
                val dry = rawShort / 32768.0
                val boostedBand = presenceFilters[ch].process(dry)
                // Wet/dry blend of 1.8kHz–3kHz presence filter according to speed factor f
                var sample = dry + (boostedBand - dry) * f
                val mag = abs(sample).toFloat()
                val envAlpha = if (mag > envelopes[ch]) 0.06f else 0.0015f
                envelopes[ch] += envAlpha * (mag - envelopes[ch])
                // Dynamic quiet-speech lift above noise floor
                val quietLift =
                    if (envelopes[ch] in 0.005f..0.12f)
                        1.0 + f * ((0.12f - envelopes[ch]) / 0.12f) * 0.25
                    else 1.0
                sample *= quietLift * volMul
                val limited = sample / (1.0 + max(0.0, abs(sample) - 0.80) * 2.2)
                output.putShort((limited * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        if (chCount == 2 && input.remaining() >= 2) pending = input.short
        output.flip()
    }
}

private class PresencePeakFilter(rate: Int, hz: Double, db: Double) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var z1 = 0.0
    private var z2 = 0.0

    init {
        val w = 2.0 * PI * hz.coerceAtMost(rate * 0.42) / rate
        val c = cos(w)
        val alpha = sin(w) / (2.0 * 0.85) // Q=0.85 covers ~1.8kHz to ~3.0kHz smoothly
        val gain = 10.0.pow(db / 40.0)
        val a0 = 1.0 + alpha / gain
        b0 = (1.0 + alpha * gain) / a0
        b1 = (-2.0 * c) / a0
        b2 = (1.0 - alpha * gain) / a0
        a1 = (-2.0 * c) / a0
        a2 = (1.0 - alpha / gain) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}
