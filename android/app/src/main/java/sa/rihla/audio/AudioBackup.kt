package sa.rihla.audio

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import sa.rihla.data.RestoreMode

class AudioBackup(private val database: AudioDatabase) {
    companion object {
        const val maxBytes = 32 * 1024 * 1024
    }

    suspend fun export(): String = database.withTransaction {
        val dao = database.dao()
        val tracks =
            JSONArray(
                dao.tracks().first().map {
                    JSONObject()
                        .put("id", it.id)
                        .put("uri", it.uri)
                        .put("title", it.title)
                        .put("artist", it.artist)
                        .put("album", it.album)
                        .put("duration", it.durationMs)
                        .put("favorite", it.favorite)
                        .put("position", it.positionMs)
                        .put("played", it.lastPlayedAt)
                        .put("added", it.addedAt)
                }
            )
        val lists =
            JSONArray(
                dao.playlists().first().map { JSONObject().put("id", it.id).put("name", it.name) }
            )
        val members =
            JSONArray(
                dao.members().first().map {
                    JSONObject()
                        .put("playlist", it.playlistId)
                        .put("track", it.trackId)
                        .put("position", it.position)
                }
            )
        val marks =
            JSONArray(
                dao.bookmarks().first().map {
                    JSONObject()
                        .put("id", it.id)
                        .put("track", it.trackId)
                        .put("position", it.positionMs)
                        .put("label", it.label)
                }
            )
        val folders =
            JSONArray(
                dao.folders().first().map {
                    JSONObject().put("id", it.id).put("topic", it.topic).put("source", it.source)
                }
            )
        val filings =
            JSONArray(
                dao.filings().first().map {
                    JSONObject().put("track", it.trackId).put("folder", it.folderId)
                }
            )
        val tuning =
            JSONArray(
                dao.tunings().first().map {
                    JSONObject().put("track", it.trackId).put("scene", it.scene)
                }
            )
        val transcripts =
            JSONArray(
                dao.allTranscripts().map {
                    JSONObject()
                        .put("track", it.trackId)
                        .put("startMs", it.startMs)
                        .put("endMs", it.endMs)
                        .put("text", it.text)
                        .put("bookmark", it.isBookmarkContext)
                }
            )
        JSONObject()
            .put("format", "MASHWARI_AUDIO")
            .put("version", 3)
            .put("tracks", tracks)
            .put("playlists", lists)
            .put("members", members)
            .put("bookmarks", marks)
            .put("folders", folders)
            .put("filings", filings)
            .put("tuning", tuning)
            .put("transcripts", transcripts)
            .toString()
    }

    suspend fun restore(
        text: String,
        mode: RestoreMode = RestoreMode.SMART_MERGE,
    ): Int {
        require(text.toByteArray(Charsets.UTF_8).size <= maxBytes)
        val root = JSONObject(text)
        val version = root.getInt("version")
        require(root.getString("format") == "MASHWARI_AUDIO" && version in 1..3)
        return database.withTransaction {
            val dao = database.dao()
            if (mode == RestoreMode.FULL_OVERWRITE) {
                dao.clearPlaylists()
                dao.clearFolders()
                dao.clearTracks()
            }
            val existingTracksById = dao.trackRecords().associateBy { it.id }
            val tracks = root.getJSONArray("tracks")
            var importedCount = 0
            for (i in 0 until tracks.length()) {
                val t = tracks.getJSONObject(i)
                val id = t.getString("id")
                val uri = t.getString("uri")
                val duration = t.getLong("duration")
                val pos = t.getLong("position")
                require(uri.startsWith("content:") && duration > 0 && pos >= 0)
                require(id.length in 1..128 && t.getString("title").length in 1..1000)
                val existing = existingTracksById[id] ?: dao.byUri(uri)
                if (existing == null) {
                    require(
                        dao.insert(
                            AudioTrack(
                                id,
                                uri,
                                t.getString("title"),
                                t.optString("artist"),
                                t.optString("album"),
                                duration,
                                t.getBoolean("favorite"),
                                pos,
                                t.optLong("played"),
                                t.optLong("added"),
                            )
                        ) != -1L
                    )
                    importedCount++
                } else {
                    // Smart merge: keep latest playback progress & favorite flag
                    val bestPlayed = maxOf(existing.lastPlayedAt, t.optLong("played"))
                    val bestPos = if (t.optLong("played") > existing.lastPlayedAt) pos else existing.positionMs
                    dao.progress(existing.id, bestPos, bestPlayed)
                    if (t.getBoolean("favorite") && !existing.favorite) dao.favorite(existing.id)
                }
            }
            val playlistIdMap = HashMap<Long, Long>()
            val existingPlaylists = dao.playlistRecords().associateBy { it.name }
            val lists = root.getJSONArray("playlists")
            for (i in 0 until lists.length()) {
                val l = lists.getJSONObject(i)
                val cleanName = l.getString("name").trim()
                val oldId = l.getLong("id")
                require(cleanName.length in 1..80 && oldId > 0)
                val existing = existingPlaylists[cleanName]
                if (existing != null && mode == RestoreMode.SMART_MERGE) {
                    playlistIdMap[oldId] = existing.id
                } else {
                    val insertedId =
                        if (mode == RestoreMode.FULL_OVERWRITE) {
                            dao.playlist(AudioPlaylist(oldId, cleanName))
                        } else {
                            dao.playlist(AudioPlaylist(name = cleanName))
                        }
                    playlistIdMap[oldId] = insertedId
                }
            }
            val members = root.getJSONArray("members")
            for (i in 0 until members.length()) {
                val m = members.getJSONObject(i)
                require(m.getLong("position") >= 0)
                val mappedPlaylist = playlistIdMap[m.getLong("playlist")] ?: error("Missing playlist")
                val trackId = m.getString("track")
                require(dao.track(trackId) != null) { "Missing track reference $trackId" }
                dao.member(AudioPlaylistItem(mappedPlaylist, trackId, m.getLong("position")))
            }
            val existingBookmarks = dao.bookmarks().first()
            val marks = root.getJSONArray("bookmarks")
            for (i in 0 until marks.length()) {
                val m = marks.getJSONObject(i)
                val pos = m.getLong("position")
                val label = m.getString("label")
                val trackId = m.getString("track")
                require(pos >= 0 && label.length <= 160 && dao.track(trackId) != null)
                val dup =
                    existingBookmarks.any {
                        it.trackId == trackId && it.positionMs == pos && it.label == label
                    }
                if (!dup) {
                    dao.bookmark(
                        AudioBookmark(
                            id = if (mode == RestoreMode.FULL_OVERWRITE) m.getLong("id") else 0L,
                            trackId = trackId,
                            positionMs = pos,
                            label = label,
                        )
                    )
                }
            }
            if (version >= 2) {
                val folders = root.optJSONArray("folders") ?: JSONArray()
                val ids = mutableSetOf<String>()
                for (i in 0 until folders.length()) {
                    val f = folders.getJSONObject(i)
                    val id = f.getString("id")
                    require(id.length in 1..128 && ids.add(id))
                    require(
                        f.getString("topic").length in 1..80 &&
                            f.getString("source").length in 1..80
                    )
                    dao.folder(AudioFolder(id, f.getString("topic"), f.getString("source")))
                }
                val filings = root.optJSONArray("filings") ?: JSONArray()
                val filed = mutableSetOf<String>()
                for (i in 0 until filings.length()) {
                    val f = filings.getJSONObject(i)
                    require(filed.add(f.getString("track")))
                    if (dao.track(f.getString("track")) != null) {
                        dao.filing(AudioFiling(f.getString("track"), f.getString("folder")))
                    }
                }
                val tuning = root.optJSONArray("tuning") ?: JSONArray()
                val tuned = mutableSetOf<String>()
                for (i in 0 until tuning.length()) {
                    val t = tuning.getJSONObject(i)
                    require(
                        tuned.add(t.getString("track")) &&
                            AudioScene.entries.any { it.key == t.getString("scene") }
                    )
                    if (dao.track(t.getString("track")) != null) {
                        dao.tuning(AudioTuning(t.getString("track"), t.getString("scene")))
                    }
                }
            }
            if (version >= 3 && root.has("transcripts")) {
                val existingTranscripts = dao.allTranscripts()
                val arr = root.getJSONArray("transcripts")
                for (i in 0 until arr.length()) {
                    val tr = arr.getJSONObject(i)
                    val trackId = tr.getString("track")
                    val startMs = tr.getLong("startMs")
                    val endMs = tr.getLong("endMs")
                    val txt = tr.getString("text")
                    val bm = tr.optBoolean("bookmark", false)
                    require(startMs >= 0 && endMs >= startMs && txt.length in 1..4000)
                    if (dao.track(trackId) != null) {
                        val exists =
                            existingTranscripts.any {
                                it.trackId == trackId && it.startMs == startMs && it.text == txt
                            }
                        if (!exists) {
                            dao.insertTranscript(
                                AudioTranscript(
                                    trackId = trackId,
                                    startMs = startMs,
                                    endMs = endMs,
                                    text = txt,
                                    isBookmarkContext = bm,
                                )
                            )
                        }
                    }
                }
            }
            if (mode == RestoreMode.FULL_OVERWRITE) tracks.length() else importedCount.coerceAtLeast(tracks.length())
        }
    }
}
