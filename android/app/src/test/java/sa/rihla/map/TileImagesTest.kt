package sa.rihla.map

import android.app.Application
import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileImagesTest {
    private fun png(width: Int): ByteArray {
        val image = Bitmap.createBitmap(width, 2, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
            image.recycle()
            it.toByteArray()
        }
    }

    @Test
    fun validTileAcceptedAndOversizedDimensionsOrGarbageRejectedBeforeFullDecode() {
        assertTrue(TileImages.valid(png(256)))
        assertFalse(TileImages.valid(png(4096)))
        assertFalse(TileImages.valid("not an image".toByteArray()))
    }

    @Test
    fun downloadStopsBeforeAllocatingUnboundedRemotePayload() {
        assertArrayEquals(byteArrayOf(1, 2), TileImages.read(ByteArrayInputStream(byteArrayOf(1, 2))))
        val stream = object : java.io.InputStream() {
            var consumed = 0
            override fun read(): Int { consumed++; return 0 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                consumed += length
                return length
            }
        }
        assertThrows(IllegalArgumentException::class.java) { TileImages.read(stream) }
        assertTrue(stream.consumed <= TileImages.maxBytes + 8192)
    }
}
