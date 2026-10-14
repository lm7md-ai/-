package sa.rihla.audio

import android.content.Context
import android.media.audiofx.*
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/** Optional stereo widening with a short crossfeed. No discrete channels are invented. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StereoSpaceProcessor : BaseAudioProcessor() {
    @Volatile var amount = 0f
    private var left = FloatArray(1)
    private var right = FloatArray(1)
    private var index = 0
    private var pending: Short? = null
    private var previousStrength = 0f
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2)
            return AudioFormat.NOT_SET
        val delay = (inputAudioFormat.sampleRate / 2000).coerceAtLeast(1)
        left = FloatArray(delay); right = FloatArray(delay); index = 0
        return inputAudioFormat
    }
    override fun onFlush() { left.fill(0f); right.fill(0f); index = 0; pending = null; previousStrength = 0f }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val strength = amount.coerceIn(0f, 1f)
        if (previousStrength != strength && strength == 0f) { left.fill(0f); right.fill(0f); index = 0 }
        previousStrength = strength
        val output = replaceOutputBuffer(((inputBuffer.remaining() + if (pending != null) 2 else 0) / 4) * 4)
        if (strength == 0f && pending == null && inputBuffer.remaining() % 4 == 0) { output.put(inputBuffer); output.flip(); return }
        while (inputBuffer.remaining() >= (if (pending == null) 4 else 2)) {
            val l = (pending ?: inputBuffer.short).toFloat(); pending = null
            val r = inputBuffer.short.toFloat()
            if (strength == 0f) { output.putShort(l.toInt().toShort()); output.putShort(r.toInt().toShort()); continue }
            val mid = (l + r) / 2f; val side = (l - r) / 2f
            val gain = 1f / (1f + .6f * strength + .12f * strength)
            val outL = (mid + side * (1f + .6f * strength) + right[index] * .12f * strength) * gain
            val outR = (mid - side * (1f + .6f * strength) + left[index] * .12f * strength) * gain
            left[index] = l; right[index] = r; index = (index + 1) % left.size
            output.putShort(outL.toInt().coerceIn(-32768, 32767).toShort())
            output.putShort(outR.toInt().coerceIn(-32768, 32767).toShort())
        }
        if (inputBuffer.remaining() >= 2) pending = inputBuffer.short
        output.flip()
    }
}

data class SoundBand(val index: Int, val hz: Int, val db: Float, val min: Float, val max: Float)
data class SoundSupport(val bands: List<SoundBand> = emptyList(), val bass: Boolean = false, val surround: Boolean = false, val systemSpatial: String = "يُفحص عند بدء التشغيل")

fun presetDb(preset: String, hz: Int): Float = when (preset) {
    "voice" -> if (hz in 500..4000) 3f else -2f
    "bass" -> if (hz < 300) 4f else 0f
    "bright" -> if (hz > 3000) 3f else 0f
    else -> 0f
}

@Suppress("DEPRECATION")
class AudioSound(private val context: Context) {
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    fun connect(session: Int) {
        release()
        if (session <= 0) return
        equalizer = runCatching { Equalizer(0, session) }.getOrNull()
        bass = runCatching { BassBoost(0, session) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, session) }.getOrNull()
    }
    fun apply(): SoundSupport {
        val prefs = context.getSharedPreferences("audio_player", Context.MODE_PRIVATE)
        val bands = runCatching {
            val eq = equalizer ?: return@runCatching emptyList<SoundBand>()
            val range = eq.bandLevelRange
            val custom = org.json.JSONArray(prefs.getString("eq_levels", "[]"))
            eq.enabled = prefs.getBoolean("eq_enabled", false)
            (0 until eq.numberOfBands).map { i ->
                val hz = eq.getCenterFreq(i.toShort()) / 1000
                val value = if (custom.length() > i) custom.optDouble(i, 0.0).toFloat()
                    else presetDb(prefs.getString("eq_preset", "flat").orEmpty(), hz)
                eq.setBandLevel(i.toShort(), (value * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                SoundBand(i, hz, eq.getBandLevel(i.toShort()) / 100f, range[0] / 100f, range[1] / 100f)
            }
        }.getOrDefault(emptyList())
        val hasBass = runCatching {
            val effect = bass ?: return@runCatching false
            if (!effect.strengthSupported) return@runCatching false
            val level = prefs.getInt("bass", 0).coerceIn(0, 1000)
            effect.setStrength(level.toShort()); effect.enabled = level > 0; true
        }.getOrDefault(false)
        val hasSurround = runCatching {
            val effect = virtualizer ?: return@runCatching false
            if (!effect.strengthSupported) return@runCatching false
            val level = prefs.getInt("surround", 0).coerceIn(0, 1000)
            effect.setStrength(level.toShort()); effect.enabled = level > 0; true
        }.getOrDefault(false)
        val spatial = if (android.os.Build.VERSION.SDK_INT >= 32) runCatching {
            val system = context.getSystemService(android.media.AudioManager::class.java).spatializer
            when {
                !system.isAvailable -> "الصوت المكاني النظامي غير متاح للمخرج الحالي"
                system.isEnabled -> "الصوت المكاني النظامي مفعّل؛ تطبيقه يعتمد على المقطع والمخرج"
                else -> "الصوت المكاني متاح؛ يُفعّل من إعدادات صوت الهاتف"
            }
        }.getOrDefault("تعذر فحص الصوت المكاني") else "الصوت المكاني النظامي يحتاج Android 12L أو أحدث"
        return SoundSupport(bands, hasBass, hasSurround, spatial)
    }
    fun release() {
        runCatching { equalizer?.release() }; runCatching { bass?.release() }; runCatching { virtualizer?.release() }
        equalizer = null; bass = null; virtualizer = null
    }
}
