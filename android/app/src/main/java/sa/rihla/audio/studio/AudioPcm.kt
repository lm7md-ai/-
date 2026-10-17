package sa.rihla.audio.studio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

const val STUDIO_RATE = 44100

class StereoResampler(private val inputRate: Int, private val output: (Float, Float) -> Unit) {
    private var frames = 0L
    private var next = 0.0
    private var previousL = 0f
    private var previousR = 0f

    init {
        require(inputRate in 8000..192000)
    }

    fun add(left: Float, right: Float) {
        val l = if (left.isFinite()) left.coerceIn(-1f, 1f) else 0f
        val r = if (right.isFinite()) right.coerceIn(-1f, 1f) else 0f
        if (frames == 0L) {
            output(l, r)
            next = inputRate.toDouble() / STUDIO_RATE
        } else
            while (next <= frames) {
                val mix = (next - (frames - 1)).toFloat().coerceIn(0f, 1f)
                output(previousL + (l - previousL) * mix, previousR + (r - previousR) * mix)
                next += inputRate.toDouble() / STUDIO_RATE
            }
        previousL = l
        previousR = r
        frames++
    }

    fun finish() {
        while (next < frames) {
            output(previousL, previousR)
            next += inputRate.toDouble() / STUDIO_RATE
        }
    }
}

class StereoWindows(
    private val windowFrames: Int = STUDIO_RATE * 15,
    private val overlapFrames: Int = STUDIO_RATE,
    private val emit: (FloatArray) -> Unit,
) {
    private val buffer = FloatArray(windowFrames * 2)
    private var used = 0
    private var overlapOnly = false

    init {
        require(windowFrames > overlapFrames && overlapFrames > 0)
    }

    fun add(l: Float, r: Float) {
        buffer[used++] = l
        buffer[used++] = r
        overlapOnly = false
        if (used == buffer.size) {
            emit(buffer.copyOf())
            buffer.copyInto(buffer, 0, used - overlapFrames * 2, used)
            used = overlapFrames * 2
            overlapOnly = true
        }
    }

    fun finish() {
        if (used > 0 && !overlapOnly) emit(buffer.copyOf(used))
        used = 0
    }
}

class StereoJoiner(
    private val overlapFrames: Int = STUDIO_RATE,
    private val emit: (FloatArray) -> Unit,
) {
    private var pending: FloatArray? = null

    fun add(samples: FloatArray) {
        require(samples.size % 2 == 0)
        val old = pending
        if (old == null) {
            pending = samples
            return
        }
        val count = overlapFrames * 2
        require(old.size >= count && samples.size >= count)
        if (old.size > count) emit(old.copyOfRange(0, old.size - count))
        val blend = FloatArray(count)
        for (i in 0 until count) {
            val gain = (i / 2).toFloat() / overlapFrames
            blend[i] =
                (old[old.size - count + i] * (1 - gain) + samples[i] * gain).coerceIn(-1f, 1f)
        }
        emit(blend)
        pending = samples.copyOfRange(count, samples.size)
    }

    fun finish() {
        pending?.takeIf { it.isNotEmpty() }?.let(emit)
        pending = null
    }
}

object StudioWave {
    fun write(file: File, samples: FloatArray) {
        require(samples.size % 2 == 0)
        val size = samples.size * 2
        val header =
            ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(36 + size)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(2)
                putInt(STUDIO_RATE)
                putInt(STUDIO_RATE * 4)
                putShort(4)
                putShort(16)
                put("data".toByteArray())
                putInt(size)
            }
        file.outputStream().use { output ->
            output.write(header.array())
            val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach {
                bytes.putShort(
                    ((if (it.isFinite()) it else 0f).coerceIn(-1f, 1f) * 32767)
                        .roundToInt()
                        .toShort()
                )
            }
            output.write(bytes.array())
        }
    }

    fun read(file: File, maxFrames: Int = STUDIO_RATE * 20): FloatArray =
        RandomAccessFile(file, "r").use { input ->
            fun integer(): Int = Integer.reverseBytes(input.readInt())
            fun short(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
            val tag = ByteArray(4)
            input.readFully(tag)
            require(String(tag) == "RIFF")
            integer()
            input.readFully(tag)
            require(String(tag) == "WAVE")
            var channels = 0
            var encoding = 0
            var bits = 0
            while (input.filePointer + 8 <= input.length()) {
                input.readFully(tag)
                val length = integer().toLong() and 0xffffffffL
                val end = input.filePointer + length
                require(end <= input.length())
                when (String(tag)) {
                    "fmt " -> {
                        require(length >= 16)
                        encoding = short()
                        channels = short()
                        require(integer() == STUDIO_RATE)
                        integer()
                        short()
                        bits = short()
                    }
                    "data" -> {
                        require(
                            channels == 2 &&
                                encoding == 1 &&
                                bits == 16 &&
                                length % 4 == 0L &&
                                length / 4 <= maxFrames
                        )
                        val bytes = ByteArray(length.toInt())
                        input.readFully(bytes)
                        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        return@use FloatArray(bytes.size / 2) { buffer.short / 32768f }
                    }
                }
                input.seek(end + (length and 1))
            }
            error("لم ينتج نموذج الفصل ملف WAV صالحًا")
        }
}
