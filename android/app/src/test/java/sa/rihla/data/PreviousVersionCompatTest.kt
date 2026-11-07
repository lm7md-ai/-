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
    fun v64AndV65LegacyJsonBackupsRestoreCleanlyIntoEmptyAndPopulatedV70(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val sourceDb = Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java).build()
        val emptyTargetDb = Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java).build()
        val mergeTargetDb = Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java).build()
        val aDb = Room.inMemoryDatabaseBuilder(context, AudioDatabase::class.java).build()
        try {
            val outingId =
                sourceDb
                    .dao()
                    .insert(
                        Outing(
                            startedAt = 1761100000000L,
                            endedAt = 1761104200000L,
                            automatic = true,
                            automaticEnd = true,
                            classification = "UNIVERSITY",
                            phase = "ENDED",
                            lastLat = 21.3289,
                            lastLon = 39.9482,
                            lastTime = 1761104200000L,
                            lastAccuracy = 8.0,
                            meters = 74500.0,
                            drivingMs = 3600000L,
                            stoppedMs = 600000L,
                            segmentStart = 1761100000000L,
                            stillSince = 0L,
                            stillLat = 0.0,
                            stillLon = 0.0,
                            stopStart = 0L,
                            homeSince = 0L,
                            universityArrival = 1761103600000L,
                            universityExit = 0L,
                            visitConfirmed = true,
                            startedHome = true,
                            originKnown = true,
                            leftHome = true,
                            universityCounted = true,
                            homeToUniversityMs = 3600000L,
                            universityMs = 600000L,
                            visits = 1,
                            gapMs = 0L,
                            startedUniversity = false,
                        )
                    )
            sourceDb
                .dao()
                .point(
                    RoutePoint(
                        outingId = outingId,
                        latitude = 21.5433,
                        longitude = 39.1728,
                        accuracy = 6.0,
                        speed = 22.0,
                        altitude = 15.0,
                        bearing = 130.0,
                        recordedAt = 1761100060000L,
                        breakBefore = false,
                        distanceDelta = 120.0,
                    )
                )
            sourceDb.dao().details(TripDetails(outingId, "جدة إلى جامعة أم القرى", "", true))
            sourceDb
                .dao()
                .importExpense(
                    Expense(
                        outingId = outingId,
                        category = "FUEL",
                        amountHalala = 8500L,
                        note = "محطة ساسكو",
                        createdAt = 1761101000000L,
                        source = "SMS_NOTIFICATION",
                        merchant = "SASCO",
                        importKey = "sms:v64:1",
                    )
                )
            sourceDb.dao().rule(MerchantRule(pattern = "SASCO", category = "FUEL"))

            // Export and strip all v7.0-only tables & columns so this is an authentic v6.4/v6.5 schemaVersion=7 JSON
            val exportedV8 = JSONObject(LocalBackup(sourceDb).export())
            exportedV8.put("schemaVersion", 7)
            val tables = exportedV8.getJSONObject("tables")
            tables.remove("university_schedule")
            tables.remove("campus_parking")
            val exps = tables.getJSONArray("expenses")
            for (i in 0 until exps.length()) {
                val e = exps.getJSONObject(i)
                e.remove("latitude")
                e.remove("longitude")
                e.remove("placeLabel")
                e.remove("receiptImageUri")
                e.remove("warrantyExpiryAt")
            }
            val rules = tables.getJSONArray("merchant_rules")
            for (i in 0 until rules.length()) {
                val r = rules.getJSONObject(i)
                r.remove("customName")
                r.remove("latitude")
                r.remove("longitude")
                r.remove("placeLabel")
            }
            val v64JourneyJson = exportedV8.toString()

            // 1. Restore into a brand-new empty v7.0 installation
            val previewEmpty = LocalBackup(emptyTargetDb).restore(v64JourneyJson, RestoreMode.SMART_MERGE)
            assertEquals(1, previewEmpty.outings)
            assertEquals(1, previewEmpty.expenses)
            assertEquals(1, previewEmpty.points)
            assertEquals(74500.0, emptyTargetDb.dao().outings().single().meters, 0.01)
            assertEquals(8500L, emptyTargetDb.dao().expenseRecords().single().amountHalala)
            assertEquals("جدة إلى جامعة أم القرى", emptyTargetDb.dao().details(outingId)?.title)

            // 2. Restore into an already-populated v7.0 installation (SMART_MERGE idempotence)
            LocalBackup(mergeTargetDb).restore(v64JourneyJson, RestoreMode.FULL_OVERWRITE)
            LocalBackup(mergeTargetDb).restore(v64JourneyJson, RestoreMode.SMART_MERGE)
            assertEquals(1, mergeTargetDb.dao().outings().size)
            assertEquals(1, mergeTargetDb.dao().expenseRecords().size)

            // 3. Restore authentic v6.4/v6.5 MASHWARI_AUDIO version=2 JSON
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
            sourceDb.close()
            emptyTargetDb.close()
            mergeTargetDb.close()
            aDb.close()
        }
    }
}
