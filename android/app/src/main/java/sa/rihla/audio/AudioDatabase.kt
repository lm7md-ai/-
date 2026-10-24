package sa.rihla.audio

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "audio_tracks", indices = [Index(value = ["uri"], unique = true)])
data class AudioTrack(
    @PrimaryKey val id: String,
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val favorite: Boolean = false,
    val positionMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val artwork: String = "",
)

@Entity(tableName = "audio_playlists", indices = [Index(value = ["name"], unique = true)])
data class AudioPlaylist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "audio_playlist_items", primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [ForeignKey(entity = AudioPlaylist::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AudioTrack::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId")])
data class AudioPlaylistItem(val playlistId: Long, val trackId: String, val position: Long)

@Entity(tableName = "audio_bookmarks", foreignKeys = [ForeignKey(entity = AudioTrack::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)], indices = [Index("trackId")])
data class AudioBookmark(@PrimaryKey(autoGenerate = true) val id: Long = 0, val trackId: String, val positionMs: Long, val label: String)

@Entity(tableName="audio_folders")
data class AudioFolder(@PrimaryKey val id:String,val topic:String,val source:String)
@Entity(tableName="audio_filing",foreignKeys=[ForeignKey(entity=AudioTrack::class,parentColumns=["id"],childColumns=["trackId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=AudioFolder::class,parentColumns=["id"],childColumns=["folderId"],onDelete=ForeignKey.CASCADE)],indices=[Index("folderId")])
data class AudioFiling(@PrimaryKey val trackId:String,val folderId:String)
@Entity(tableName="audio_tuning",foreignKeys=[ForeignKey(entity=AudioTrack::class,parentColumns=["id"],childColumns=["trackId"],onDelete=ForeignKey.CASCADE)])
data class AudioTuning(@PrimaryKey val trackId:String,val scene:String)

@Dao
interface AudioDao {
    @Query("SELECT * FROM audio_tracks ORDER BY title COLLATE NOCASE,id") fun tracks(): Flow<List<AudioTrack>>
    @Query("SELECT * FROM audio_tracks WHERE id=:id") suspend fun track(id: String): AudioTrack?
    @Query("SELECT * FROM audio_tracks WHERE uri=:uri") suspend fun byUri(uri: String): AudioTrack?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(track: AudioTrack): Long
    @Query("UPDATE audio_tracks SET title=:title,artist=:artist,album=:album,durationMs=:duration,artwork=:artwork WHERE id=:id") suspend fun metadata(id: String, title: String, artist: String, album: String, duration: Long, artwork: String)
    @Query("UPDATE audio_tracks SET artwork=:artwork WHERE id=:id") suspend fun artwork(id: String, artwork: String)
    @Query("UPDATE audio_tracks SET uri=:uri WHERE id=:id") suspend fun relink(id: String, uri: String)
    @Query("UPDATE audio_tracks SET favorite=NOT favorite WHERE id=:id") suspend fun favorite(id: String)
    @Query("UPDATE audio_tracks SET positionMs=:position,lastPlayedAt=CASE WHEN :time>0 THEN :time ELSE lastPlayedAt END WHERE id=:id") suspend fun progress(id: String, position: Long, time: Long)
    @Query("DELETE FROM audio_tracks WHERE id=:id") suspend fun remove(id: String)
    @Query("SELECT * FROM audio_playlists ORDER BY name") fun playlists(): Flow<List<AudioPlaylist>>
    @Insert suspend fun playlist(value: AudioPlaylist): Long
    @Query("DELETE FROM audio_playlists WHERE id=:id") suspend fun removePlaylist(id: Long)
    @Query("SELECT * FROM audio_playlist_items ORDER BY position,trackId") fun members(): Flow<List<AudioPlaylistItem>>
    @Query("SELECT COALESCE(MAX(position),-1)+1 FROM audio_playlist_items WHERE playlistId=:id") suspend fun nextPosition(id: Long): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun member(value: AudioPlaylistItem): Long
    @Query("DELETE FROM audio_playlist_items WHERE playlistId=:playlist AND trackId=:track") suspend fun removeMember(playlist: Long, track: String)
    @Query("UPDATE audio_playlist_items SET position=:position WHERE playlistId=:playlist AND trackId=:track") suspend fun move(playlist: Long, track: String, position: Long)
    @Query("SELECT * FROM audio_bookmarks ORDER BY positionMs") fun bookmarks(): Flow<List<AudioBookmark>>
    @Insert suspend fun bookmark(value: AudioBookmark)
    @Query("DELETE FROM audio_bookmarks WHERE id=:id") suspend fun removeBookmark(id: Long)
    @Query("SELECT * FROM audio_folders ORDER BY topic,source") fun folders():Flow<List<AudioFolder>>
    @Query("SELECT * FROM audio_filing") fun filings():Flow<List<AudioFiling>>
    @Query("SELECT * FROM audio_tuning") fun tunings():Flow<List<AudioTuning>>
    @Query("SELECT * FROM audio_tuning WHERE trackId=:id") suspend fun tuning(id:String):AudioTuning?
    @Upsert suspend fun folder(folder:AudioFolder)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun filing(filing:AudioFiling)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun tuning(tuning:AudioTuning)
}

@Database(entities = [AudioTrack::class, AudioPlaylist::class, AudioPlaylistItem::class, AudioBookmark::class,AudioFolder::class,AudioFiling::class,AudioTuning::class], version = 2, exportSchema = true)
abstract class AudioDatabase : RoomDatabase() {
    abstract fun dao(): AudioDao
    companion object {
        val MIGRATION_1_2=object:androidx.room.migration.Migration(1,2) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS audio_folders (id TEXT NOT NULL PRIMARY KEY,topic TEXT NOT NULL,source TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS audio_filing (trackId TEXT NOT NULL PRIMARY KEY,folderId TEXT NOT NULL,FOREIGN KEY(trackId) REFERENCES audio_tracks(id) ON DELETE CASCADE,FOREIGN KEY(folderId) REFERENCES audio_folders(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audio_filing_folderId ON audio_filing(folderId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS audio_tuning (trackId TEXT NOT NULL PRIMARY KEY,scene TEXT NOT NULL,FOREIGN KEY(trackId) REFERENCES audio_tracks(id) ON DELETE CASCADE)")
            }
        }
    }
}

fun audioResume(position: Long, duration: Long): Long =
    if (position < 0 || (duration > 0 && position >= (duration - 5000).coerceAtLeast(0))) 0 else position

fun audioTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(java.util.Locale.US, seconds / 60, seconds % 60)
}
