package sa.rihla.audio

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AudioImport(val added: Int, val updated: Int, val failed: Int)

class AudioRepository(private val context: Context, val database: AudioDatabase) {
    val dao = database.dao()
    private val artworkLock = Mutex()

    suspend fun ensureArtwork(track: AudioTrack): java.io.File? =
        withContext(Dispatchers.IO) {
            artworkLock.withLock {
                val current = dao.track(track.id) ?: track
                val existing =
                    current.artwork
                        .takeIf { it.isNotBlank() }
                        ?.let { java.io.File(it) }
                        ?.takeIf { it.isFile }
                if (
                    existing != null &&
                        existing.canonicalPath.startsWith(
                            context.noBackupFilesDir.canonicalPath + java.io.File.separator
                        )
                )
                    return@withLock existing
                val restored = runCatching {
                    if (existing != null) {
                        require(existing.length() in 1..2_000_000)
                        val directory =
                            java.io.File(context.noBackupFilesDir, "audio_art").apply { mkdirs() }
                        // Restored track IDs are untrusted filenames; hash instead of using them as
                        // paths.
                        val target = java.io.File(directory, id(current.id) + ".jpg")
                        val pending = java.io.File.createTempFile("cover-", ".pending", directory)
                        try {
                            existing.copyTo(pending, overwrite = true)
                            check(pending.renameTo(target))
                        } finally {
                            pending.delete()
                        }
                        target
                    } else {
                        metadata(current.uri.toUri())
                            .artwork
                            .takeIf { it.isNotBlank() }
                            ?.let { java.io.File(it) }
                            ?.takeIf { it.isFile }
                    }
                }
                    .getOrNull()
                if (restored != null && dao.track(current.id) != null)
                    dao.artwork(current.id, restored.path)
                restored
            }
        }

    suspend fun add(track: AudioTrack): Boolean = database.withTransaction {
        require(
            track.durationMs > 0 && track.title.isNotBlank() && track.uri.startsWith("content:")
        )
        val previous = dao.byUri(track.uri)
        if (previous != null) {
            dao.metadata(
                previous.id,
                track.title,
                track.artist,
                track.album,
                track.durationMs,
                track.artwork,
            )
            return@withTransaction false
        }
        val inserted = dao.insert(track) != -1L
        if (!inserted)
            dao.metadata(
                track.id,
                track.title,
                track.artist,
                track.album,
                track.durationMs,
                track.artwork,
            )
        inserted
    }

    suspend fun importFiles(uris: List<Uri>): AudioImport {
        var added = 0
        var updated = 0
        var failed = 0
        for (uri in uris.distinct()) {
            currentCoroutineContext().ensureActive()
            try {
                require(uri.scheme == "content")
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val track = metadata(uri)
                if (add(track)) added++ else updated++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed++
            }
        }
        return AudioImport(added, updated, failed)
    }

    suspend fun importFolder(uri: Uri): AudioImport {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val root = DocumentFile.fromTreeUri(context, uri) ?: error("FOLDER_UNAVAILABLE")
        val files = mutableListOf<Uri>()
        val visited = mutableSetOf<String>()
        val active = currentCoroutineContext()
        fun collect(folder: DocumentFile, depth: Int) {
            active.ensureActive()
            require(depth <= 16 && visited.size < 20000) { "FOLDER_TOO_LARGE" }
            if (!visited.add(folder.uri.toString())) return
            folder.listFiles().forEach {
                active.ensureActive()
                if (it.isDirectory) collect(it, depth + 1)
                else if (it.type?.startsWith("audio/") == true) {
                    require(files.size < 20000) { "FOLDER_TOO_LARGE" }
                    files.add(it.uri)
                }
            }
        }
        collect(root, 0)
        return importFiles(files)
    }

    suspend fun scanPhone(): AudioImport {
        val uris = mutableListOf<Uri>()
        context.contentResolver
            .query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DURATION}>0",
                null,
                null,
            )
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    uris.add(
                        android.content.ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(0),
                        )
                    )
                }
            }
        return importFiles(uris)
    }

    private fun metadata(uri: Uri): AudioTrack {
        val name =
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                .orEmpty()
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(context, uri)
            val duration =
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: 0
            require(duration > 0) { "INVALID_AUDIO" }
            val artwork = runCatching {
                val data = reader.embeddedPicture ?: return@runCatching ""
                if (data.size > 2_000_000) return@runCatching ""
                val bounds =
                    android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching ""
                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 }
                while (
                    maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 512
                ) options.inSampleSize *= 2
                val image =
                    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, options)
                        ?: return@runCatching ""
                try {
                    val directory =
                        java.io.File(context.noBackupFilesDir, "audio_art").apply { mkdirs() }
                    val file = java.io.File(directory, id(uri.toString()) + ".jpg")
                    val pending = java.io.File.createTempFile("cover-", ".pending", directory)
                    try {
                        pending.outputStream().use {
                            check(
                                image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)
                            )
                        }
                        check(pending.renameTo(file))
                    } finally {
                        pending.delete()
                    }
                    file.path
                } finally {
                    image.recycle()
                }
            }
                .getOrDefault("")
            return AudioTrack(
                id(uri.toString()),
                uri.toString(),
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf {
                    it.isNotBlank()
                } ?: name.ifBlank { "مقطع صوتي" },
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                duration,
                artwork = artwork,
            )
        } finally {
            reader.release()
        }
    }

    suspend fun createPlaylist(name: String): Long {
        val clean = name.trim()
        require(clean.length in 1..80)
        return dao.playlist(AudioPlaylist(name = clean))
    }

    suspend fun relink(id: String, uri: Uri) {
        require(uri.scheme == "content")
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val data = metadata(uri)
        database.withTransaction {
            require(dao.track(id) != null)
            require(dao.byUri(uri.toString())?.id?.let { it == id } != false)
            dao.relink(id, uri.toString())
            dao.metadata(id, data.title, data.artist, data.album, data.durationMs, data.artwork)
        }
    }

    suspend fun addToPlaylist(playlist: Long, track: String) = database.withTransaction {
        dao.member(AudioPlaylistItem(playlist, track, dao.nextPosition(playlist)))
    }

    suspend fun swap(playlist: Long, first: AudioPlaylistItem, second: AudioPlaylistItem) =
        database.withTransaction {
            require(first.playlistId == playlist && second.playlistId == playlist)
            dao.move(playlist, first.trackId, second.position)
            dao.move(playlist, second.trackId, first.position)
        }

    companion object {
        fun id(uri: String) =
            MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
    }
}
