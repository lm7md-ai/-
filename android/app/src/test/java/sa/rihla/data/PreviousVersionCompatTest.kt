package sa.rihla.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import sa.rihla.audio.AudioBackup
import sa.rihla.audio.AudioDatabase
import sa.rihla.audio.AudioTranscript

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PreviousVersionCompatTest {

    @Test
    fun audioUpgradeFromV64Schema2PreservesTracksBookmarksAndFolders(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dbName = "audio-v2-upgrade.db"
        context.deleteDatabase(dbName)
        val schema =
            JSONObject(File("schemas/sa.rihla.audio.AudioDatabase/2.json").readText())
                .getJSONObject("database")
        val old = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        old.execSQL("PRAGMA foreign_keys=ON")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: JSONArray()
            for (j in 0 until indices.length()) {
                old.execSQL(
                    indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
                )
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
        old.version = 2

        old.execSQL(
            "INSERT INTO `audio_tracks` (`id`,`uri`,`title`,`artist`,`album`,`durationMs`,`favorite`,`positionMs`,`lastPlayedAt`,`addedAt`,`artwork`) VALUES ('t1','content://media/1','محاضرة نظم التشغيل','د. خالد','جامعة أم القرى',2400000,1,615000,1761000000000,1760000000000,'')"
        )
        old.execSQL(
            "INSERT INTO `audio_bookmarks` (`id`,`trackId`,`positionMs`,`label`) VALUES (1,'t1',600000,'شرح الجدولة')"
        )
        old.execSQL(
            "INSERT INTO `audio_folders` (`id`,`topic`,`source`) VALUES ('f1','مقررات الحاسب','العابدية')"
        )
        old.execSQL("INSERT INTO `audio_filing` (`trackId`,`folderId`) VALUES ('t1','f1')")
        old.close()

        val migrated =
            Room.databaseBuilder(context, AudioDatabase::class.java, dbName)
                .addMigrations(AudioDatabase.MIGRATION_1_2, AudioDatabase.MIGRATION_2_3)
                .build()
        try {
            val tracks = migrated.dao().tracks().first()
            assertEquals(1, tracks.size)
            assertEquals("محاضرة نظم التشغيل", tracks.single().title)
            assertEquals(615_000L, tracks.single().positionMs)
            val bookmarks = migrated.dao().bookmarks().first()
            assertEquals(1, bookmarks.size)
            assertEquals("شرح الجدولة", bookmarks.single().label)
            migrated
                .dao()
                .insertTranscript(
                    AudioTranscript(
                        trackId = "t1",
                        startMs = 600_000L,
                        endMs = 630_000L,
                        text = "خوارزمية الجدولة الدائرية Round Robin",
                        isBookmarkContext = true,
                    )
                )
            assertEquals(1, migrated.dao().searchTranscripts("Round Robin").size)
        } finally {
            migrated.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun v64AndV65LegacyJsonBackupsRestoreCleanlyIntoV70(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val jDb =
            Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val aDb =
            Room.inMemoryDatabaseBuilder(context, AudioDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            val v64JourneyJson =
                JSONObject()
                    .put("format", "RIHLA_BACKUP")
                    .put("formatVersion", 1)
                    .put("schemaVersion", 7)
                    .put("exportedAt", 1761200000000L)
                    .put(
                        "tables",
                        JSONObject()
                            .put(
                                "outings",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("id", 10L)
                                            .put("title", "جدة إلى جامعة أم القرى")
                                            .put("startedAt", 1761100000000L)
                                            .put("endedAt", 1761104200000L)
                                            .put("distanceMeters", 74500.0)
                                            .put("phase", "ENDED")
                                            .put("stopCount", 1)
                                            .put("movingMs", 3600000L)
                                            .put("stoppedMs", 600000L)
                                            .put("maxSpeedMps", 31.5)
                                            .put("lastLat", 21.3289)
                                            .put("lastLon", 39.9482)
                                            .put("lastTime", 1761104200000L)
                                            .put("homeHoldStart", 0L)
                                    ),
                            )
                            .put(
                                "points",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("id", 1L)
                                            .put("outingId", 10L)
                                            .put("latitude", 21.5433)
                                            .put("longitude", 39.1728)
                                            .put("accuracy", 6.0)
                                            .put("speedMps", 22.0)
                                            .put("recordedAt", 1761100060000L)
                                            .put("breakBefore", 0)
                                    ),
                            )
                            .put("events", JSONArray())
                            .put("places", JSONArray())
                            .put(
                                "expenses",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("id", 5L)
                                            .put("outingId", 10L)
                                            .put("category", "FUEL")
                                            .put("amountHalala", 8500L)
                                            .put("note", "محطة ساسكو")
                                            .put("createdAt", 1761101000000L)
                                            .put("source", "SMS_NOTIFICATION")
                                            .put("merchant", "SASCO")
                                            .put("importKey", "sms:v64:1")
                                    ),
                            )
                            .put("fuel", JSONArray())
                            .put("active_slot", JSONArray())
                            .put("imported_sms", JSONArray())
                            .put("trip_details", JSONArray())
                            .put(
                                "merchant_rules",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("id", 1L)
                                            .put("pattern", "SASCO")
                                            .put("category", "FUEL")
                                    ),
                            )
                            .put("recurring_expenses", JSONArray()),
                    )
                    .toString()

            val preview = LocalBackup(jDb).restore(v64JourneyJson, RestoreMode.SMART_MERGE)
            assertEquals(1, preview.outings)
            assertEquals(1, preview.expenses)
            assertEquals(1, preview.points)
            assertEquals("جدة إلى جامعة أم القرى", jDb.dao().outings().single().title)
            assertEquals(8500L, jDb.dao().expenseRecords().single().amountHalala)

            val v64AudioJson =
                JSONObject()
                    .put("format", "MASHWARI_AUDIO")
                    .put("version", 2)
                    .put(
                        "tracks",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", "lec-1")
                                    .put("uri", "content://audio/lec1")
                                    .put("title", "مراجعة الخوارزميات")
                                    .put("artist", "د. أحمد")
                                    .put("album", "UQU")
                                    .put("duration", 1800000L)
                                    .put("favorite", true)
                                    .put("position", 450000L)
                                    .put("played", 1761100000000L)
                                    .put("added", 1761000000000L)
                            ),
                    )
                    .put("playlists", JSONArray())
                    .put("members", JSONArray())
                    .put("bookmarks", JSONArray())
                    .put("folders", JSONArray())
                    .put("filings", JSONArray())
                    .put("tuning", JSONArray())
                    .toString()

            val restoredTracks = AudioBackup(aDb).restore(v64AudioJson, RestoreMode.SMART_MERGE)
            assertEquals(1, restoredTracks)
            assertEquals("مراجعة الخوارزميات", aDb.dao().tracks().first().single().title)
        } finally {
            jDb.close()
            aDb.close()
        }
    }
}
