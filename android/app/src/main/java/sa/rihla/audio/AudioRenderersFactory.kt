package sa.rihla.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioRenderersFactory(
    context: Context,
    private val scene: AudioScene,
    private val processor: StereoSpaceProcessor,
) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
    }

    public override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val adaptive = SpeedAdaptiveAudioProcessor()
        return when {
            scene == AudioScene.NATURAL ->
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(adaptive))
                    .build()
            scene.lecture ->
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(LectureAudioProcessor(scene), adaptive))
                    .build()
            else ->
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(processor, adaptive))
                    .build()
        }
    }
}
