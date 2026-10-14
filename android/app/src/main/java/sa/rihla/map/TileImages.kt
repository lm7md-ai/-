package sa.rihla.map

import android.graphics.BitmapFactory
import java.io.InputStream

object TileImages {
    const val maxBytes = 2_000_000

    fun read(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes) { "TILE_TOO_LARGE" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun valid(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > maxBytes) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024
    }
}
