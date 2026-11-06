package sa.rihla.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import java.io.File
import sa.rihla.data.Expense

data class ExpiringWarrantyAlert(
    val expense: Expense,
    val daysRemaining: Long,
    val expired: Boolean,
)

object ReceiptStore {
    fun receiptsDir(context: Context): File =
        File(context.filesDir, "receipts").apply { mkdirs() }

    /**
     * Compresses an in-memory Bitmap (e.g. from camera capture) into local receipts storage
     * as WebP/JPEG (~100-180 KB) and returns its absolute file path.
     */
    fun saveBitmap(context: Context, bitmap: Bitmap, nowMs: Long = System.currentTimeMillis()): String {
        val scaled = scaleDown(bitmap, 1600)
        val ext = if (Build.VERSION.SDK_INT >= 30) "webp" else "jpg"
        val file = File(receiptsDir(context), "receipt_${nowMs}_${(1000..9999).random()}.$ext")
        file.outputStream().use { out ->
            @Suppress("DEPRECATION")
            val format =
                if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                else Bitmap.CompressFormat.JPEG
            scaled.compress(format, 80, out)
        }
        if (scaled !== bitmap) scaled.recycle()
        return file.absolutePath
    }

    /**
     * Reads an image Uri from Gallery/SAF, downsamples safely, compresses to local receipts dir,
     * and returns its absolute file path.
     */
    fun saveFromUri(context: Context, uri: Uri, nowMs: Long = System.currentTimeMillis()): String? {
        val bytes =
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16384)
                var total = 0
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    require(total <= 25 * 1024 * 1024) { "RECEIPT_TOO_LARGE" }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val decoded =
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
        return try {
            saveBitmap(context, decoded, nowMs)
        } finally {
            decoded.recycle()
        }
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val largest = maxOf(w, h)
        if (largest <= maxDim || largest <= 0) return src
        val ratio = maxDim.toFloat() / largest.toFloat()
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    fun expiringWarranties(
        expenses: List<Expense>,
        nowMs: Long = System.currentTimeMillis(),
        withinDays: Long = 30L,
    ): List<ExpiringWarrantyAlert> {
        val dayMs = 86_400_000L
        return expenses
            .filter { it.warrantyExpiryAt != null && it.warrantyExpiryAt > 0L }
            .mapNotNull { exp ->
                val expiry = exp.warrantyExpiryAt ?: return@mapNotNull null
                val diffMs = expiry - nowMs
                val days = diffMs / dayMs
                if (days in -7L..withinDays) {
                    ExpiringWarrantyAlert(exp, days, diffMs < 0)
                } else null
            }
            .sortedBy { it.expense.warrantyExpiryAt }
    }
}
