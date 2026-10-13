package sa.rihla.map

import android.content.Context
import java.io.File

/** Bundled data requires no download or first-run connection. */
object OfflineMapStore {
    const val Asset = "maps/jeddah-makkah.mbtiles"
    const val Attribution = "© OpenStreetMap contributors · ODbL 1.0 · Geofabrik (2026-10-12)"
    private const val FileName = "jeddah-makkah-20261012.mbtiles"
    private val lock = Any()

    fun bundled(context: Context): File =
        synchronized(lock) {
            val target = File(context.noBackupFilesDir, FileName)
            if (!target.exists()) {
                val pending = File(context.noBackupFilesDir, "$FileName.pending")
                try {
                    context.assets.open(Asset).use { input ->
                        pending.outputStream().use { input.copyTo(it) }
                    }
                    check(pending.renameTo(target))
                } finally {
                    pending.delete()
                }
            }
            target
        }
}
