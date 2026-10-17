package sa.rihla.audio

import android.content.Context
import android.media.audiofx.*
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/** Stereo virtualization with early reflections; output always remains one or two channels. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StereoSpaceProcessor : BaseAudioProcessor() {
    @Volatile var amount = 0f
    @Volatile var scene = AudioScene.CUSTOM
    private var left = FloatArray(1)
    private var right = FloatArray(1)
    private var index = 0
    private var pending: Short? = null
    private var sourceChannels = 2
    private var rate = 48000
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount == 2) return inputAudioFormat
        if (inputAudioFormat.channelCount == 1 && scene in listOf(AudioScene.SPACE_3D,AudioScene.SURROUND_71))
            return AudioFormat(inputAudioFormat.sampleRate,2,C.ENCODING_PCM_16BIT)
        return AudioFormat.NOT_SET
    }
    override fun onFlush(streamMetadata: androidx.media3.common.audio.AudioProcessor.StreamMetadata) {
        rate=inputAudioFormat.sampleRate.coerceAtLeast(1)
        sourceChannels=inputAudioFormat.channelCount
        left=FloatArray((rate*.04f).toInt().coerceAtLeast(1)+1);right=FloatArray(left.size)
        index=0;pending=null
    }
    private fun delayed(values:FloatArray,milliseconds:Float):Float =
        values[(index-(rate*milliseconds/1000f).toInt().coerceIn(1,values.size-1)+values.size)%values.size]
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return // Media3's shared EMPTY_BUFFER may also be our initial output.
        val profile=scene
        val strength=(if(profile==AudioScene.CUSTOM) amount else profile.width).coerceIn(0f,1f)
        val room=profile.reflections
        val mono=sourceChannels==1
        val frames=(inputBuffer.remaining()+(if(pending!=null)2 else 0))/(if(mono)2 else 4)
        val output=replaceOutputBuffer(frames*4)
        if (!mono && strength==0f && room==0f && pending==null && inputBuffer.remaining()%4==0) {
            output.put(inputBuffer);output.flip();return
        }
        while (inputBuffer.remaining()>=(if(mono || pending!=null)2 else 4)) {
            val l=(pending ?: inputBuffer.short).toFloat();pending=null
            val r=if(mono)l else inputBuffer.short.toFloat()
            val mid=(l+r)/2f;val side=(l-r)/2f
            var reflectionL=0f;var reflectionR=0f
            val taps=if(profile==AudioScene.SURROUND_71)8 else 2
            for(tap in 0 until taps) {
                val delay=if(taps==8)4f+tap*3.5f else 9f+tap*6f
                reflectionL+=delayed(if(tap%2==0)right else left,delay)*room/taps
                reflectionR+=delayed(if(tap%2==0)left else right,delay+1.5f)*room/taps
            }
            val gain=1f/(1f+.6f*strength+.12f*strength+room)
            val outL=(mid+side*(1f+.6f*strength)+delayed(right,.5f)*.12f*strength+reflectionL)*gain
            val outR=(mid-side*(1f+.6f*strength)+delayed(left,.5f)*.12f*strength+reflectionR)*gain
            left[index]=l;right[index]=r;index=(index+1)%left.size
            output.putShort(outL.toInt().coerceIn(-32768,32767).toShort())
            output.putShort(outR.toInt().coerceIn(-32768,32767).toShort())
        }
        if (!mono && inputBuffer.remaining()>=2) pending=inputBuffer.short
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
    private var sessionId=0
    fun connect(session: Int) { release();sessionId=session }
    fun hasNativeEffects()=equalizer!=null || bass!=null || virtualizer!=null
    fun apply(): SoundSupport {
        val prefs = context.getSharedPreferences("audio_player", Context.MODE_PRIVATE)
        // Direct and software scenes never attach vendor effects to the output session.
        if (AudioScene.read(prefs)==AudioScene.CUSTOM && sessionId>0) {
            if(equalizer==null) equalizer=runCatching { Equalizer(0,sessionId) }.getOrNull()
            if(bass==null) bass=runCatching { BassBoost(0,sessionId) }.getOrNull()
            if(virtualizer==null) virtualizer=runCatching { Virtualizer(0,sessionId) }.getOrNull()
        } else release()
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
