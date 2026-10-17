package sa.rihla.audio.studio

import android.content.Context
import android.media.*
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

internal fun studioInputFlags(flags: Int): Int {
    require(flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
        "فصل الصوت لا يدعم المقاطع المشفرة أو المحمية"
    }
    return (if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME
    else 0) or
        (if (flags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0)
            MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        else 0)
}

object StudioAudioDecoder {
    fun decode(
        context: Context,
        uri: Uri,
        checkActive: () -> Unit,
        progress: (Long) -> Unit,
        output: (Float, Float) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor
                        .getTrackFormat(it)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: error("لا يوجد مسار صوتي")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var inputEnded = false
            var outputEnded = false
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            var resampler = StereoResampler(rate, output)
            val info = MediaCodec.BufferInfo()
            var idleSince = System.nanoTime()
            while (!outputEnded) {
                checkActive()
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                index,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                index,
                                0,
                                size,
                                extractor.sampleTime,
                                studioInputFlags(extractor.sampleFlags),
                            )
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual = codec.outputFormat
                    val newRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(channels in 1..2) { "فصل الموسيقى يدعم المصادر الأحادية والستيريو" }
                    if (newRate != rate) {
                        rate = newRate
                        resampler = StereoResampler(rate, output)
                    }
                    encoding =
                        if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            actual.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        else android.media.AudioFormat.ENCODING_PCM_16BIT
                } else if (index >= 0) {
                    try {
                        val buffer =
                            codec
                                .getOutputBuffer(index)!!
                                .duplicate()
                                .order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes =
                            if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        require(
                            encoding in
                                listOf(
                                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                                    android.media.AudioFormat.ENCODING_PCM_FLOAT,
                                )
                        )
                        while (buffer.remaining() >= bytes * channels) {
                            val l = if (bytes == 4) buffer.float else buffer.short / 32768f
                            val r =
                                if (channels == 1) l
                                else if (bytes == 4) buffer.float else buffer.short / 32768f
                            resampler.add(l, r)
                        }
                        progress(info.presentationTimeUs / 1000)
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        idleSince = System.nanoTime()
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
                check(System.nanoTime() - idleSince < 30_000_000_000L) {
                    "توقف مفكك الصوت عن الاستجابة"
                }
            }
            resampler.finish()
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}

class StudioAacEncoder(private val file: File) : AutoCloseable {
    private val encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
    private val muxer =
        try {
            MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (error: Throwable) {
            encoder.release()
            throw error
        }
    private var started = false
    private var track = -1
    private var frames = 0L
    private var finished = false

    init {
        val format =
            MediaFormat.createAudioFormat("audio/mp4a-latm", STUDIO_RATE, 2).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        } catch (e: Throwable) {
            encoder.release()
            muxer.release()
            throw e
        }
    }

    private fun drain(end: Boolean, checkActive: () -> Unit) {
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            checkActive()
            val index = encoder.dequeueOutputBuffer(info, if (end) 10000 else 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(!started)
                track = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                started = true
            } else if (index >= 0) {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(started)
                        val buffer = encoder.getOutputBuffer(index)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        finished = true
                        return
                    }
                } finally {
                    encoder.releaseOutputBuffer(index, false)
                }
            } else if (!end) return
            check(System.nanoTime() < deadline) { "توقف حفظ الصوت عن الاستجابة" }
        }
    }

    fun add(samples: FloatArray, checkActive: () -> Unit) {
        require(samples.size % 2 == 0)
        var offset = 0
        var deadline = System.nanoTime() + 20_000_000_000L
        while (offset < samples.size) {
            checkActive()
            val index = encoder.dequeueInputBuffer(10000)
            if (index >= 0) {
                val buffer = encoder.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                buffer.clear()
                val count = minOf(samples.size - offset, buffer.remaining() / 4 * 2)
                for (i in offset until offset + count) buffer.putShort(
                    (samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
                )
                encoder.queueInputBuffer(index, 0, count * 2, frames * 1_000_000 / STUDIO_RATE, 0)
                frames += count / 2
                offset += count
                deadline = System.nanoTime() + 20_000_000_000L
            }
            drain(false, checkActive)
            check(System.nanoTime() < deadline) { "تعذر حفظ الصوت" }
        }
    }

    fun finish(checkActive: () -> Unit) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            checkActive()
            val index = encoder.dequeueInputBuffer(10000)
            if (index >= 0) {
                encoder.queueInputBuffer(
                    index,
                    0,
                    0,
                    frames * 1_000_000 / STUDIO_RATE,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                break
            }
            drain(false, checkActive)
            check(System.nanoTime() < deadline)
        }
        drain(true, checkActive)
        check(started && finished && frames > 0)
        muxer.stop()
        started = false
    }

    override fun close() {
        runCatching { encoder.stop() }
        encoder.release()
        if (started) runCatching { muxer.stop() }
        muxer.release()
    }
}
