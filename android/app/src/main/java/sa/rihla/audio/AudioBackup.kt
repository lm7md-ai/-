package sa.rihla.audio

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

class AudioBackup(private val database: AudioDatabase) {
    companion object { const val maxBytes = 20 * 1024 * 1024 }
    suspend fun export(): String = database.withTransaction {
        val dao = database.dao()
        val tracks = JSONArray(dao.tracks().first().map {
            JSONObject().put("id",it.id).put("uri",it.uri).put("title",it.title).put("artist",it.artist).put("album",it.album)
                .put("duration",it.durationMs).put("favorite",it.favorite).put("position",it.positionMs).put("played",it.lastPlayedAt).put("added",it.addedAt)
        })
        val lists = JSONArray(dao.playlists().first().map { JSONObject().put("id",it.id).put("name",it.name) })
        val members = JSONArray(dao.members().first().map { JSONObject().put("playlist",it.playlistId).put("track",it.trackId).put("position",it.position) })
        val marks = JSONArray(dao.bookmarks().first().map { JSONObject().put("id",it.id).put("track",it.trackId).put("position",it.positionMs).put("label",it.label) })
        val folders=JSONArray(dao.folders().first().map { JSONObject().put("id",it.id).put("topic",it.topic).put("source",it.source) })
        val filings=JSONArray(dao.filings().first().map { JSONObject().put("track",it.trackId).put("folder",it.folderId) })
        val tuning=JSONArray(dao.tunings().first().map { JSONObject().put("track",it.trackId).put("scene",it.scene) })
        JSONObject().put("format","MASHWARI_AUDIO").put("version",2).put("tracks",tracks).put("playlists",lists).put("members",members).put("bookmarks",marks).put("folders",folders).put("filings",filings).put("tuning",tuning).toString()
    }
    suspend fun restore(text: String): Int {
        require(text.toByteArray(Charsets.UTF_8).size <= maxBytes)
        val root = JSONObject(text)
        require(root.getString("format") == "MASHWARI_AUDIO" && root.getInt("version") in 1..2)
        return database.withTransaction {
            val dao = database.dao()
            require(dao.tracks().first().isEmpty() && dao.playlists().first().isEmpty()) { "AUDIO_NOT_EMPTY" }
            val tracks = root.getJSONArray("tracks")
            for (i in 0 until tracks.length()) {
                val t = tracks.getJSONObject(i)
                require(t.getString("uri").startsWith("content:") && t.getLong("duration") > 0 && t.getLong("position") >= 0)
                require(t.getString("id").length in 1..128 && t.getString("title").length in 1..1000)
                require(dao.insert(AudioTrack(t.getString("id"),t.getString("uri"),t.getString("title"),t.optString("artist"),t.optString("album"),t.getLong("duration"),t.getBoolean("favorite"),t.getLong("position"),t.optLong("played"),t.optLong("added"))) != -1L)
            }
            val lists = root.getJSONArray("playlists")
            for (i in 0 until lists.length()) {
                val l = lists.getJSONObject(i); require(l.getString("name").trim().length in 1..80 && l.getLong("id") > 0)
                dao.playlist(AudioPlaylist(l.getLong("id"),l.getString("name")))
            }
            val members = root.getJSONArray("members")
            for (i in 0 until members.length()) { val m = members.getJSONObject(i); require(m.getLong("position") >= 0); require(dao.member(AudioPlaylistItem(m.getLong("playlist"),m.getString("track"),m.getLong("position"))) != -1L) }
            val marks = root.getJSONArray("bookmarks")
            for (i in 0 until marks.length()) { val m = marks.getJSONObject(i); require(m.getLong("position") >= 0 && m.getString("label").length <= 160); dao.bookmark(AudioBookmark(m.getLong("id"),m.getString("track"),m.getLong("position"),m.getString("label"))) }
            if(root.getInt("version")==2) {
                val folders=root.getJSONArray("folders");val ids=mutableSetOf<String>()
                for(i in 0 until folders.length()) { val f=folders.getJSONObject(i);val id=f.getString("id");require(id.length in 1..128 && ids.add(id));require(f.getString("topic").length in 1..80 && f.getString("source").length in 1..80);dao.folder(AudioFolder(id,f.getString("topic"),f.getString("source"))) }
                val filings=root.getJSONArray("filings");val filed=mutableSetOf<String>()
                for(i in 0 until filings.length()) { val f=filings.getJSONObject(i);require(filed.add(f.getString("track")));dao.filing(AudioFiling(f.getString("track"),f.getString("folder"))) }
                val tuning=root.getJSONArray("tuning");val tuned=mutableSetOf<String>()
                for(i in 0 until tuning.length()) { val t=tuning.getJSONObject(i);require(tuned.add(t.getString("track")) && AudioScene.entries.any { it.key==t.getString("scene") });dao.tuning(AudioTuning(t.getString("track"),t.getString("scene"))) }
            }
            tracks.length()
        }
    }
}
