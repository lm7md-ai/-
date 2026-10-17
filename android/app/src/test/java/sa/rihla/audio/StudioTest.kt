package sa.rihla.audio

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.room.Room
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sa.rihla.audio.studio.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StudioTest {
    @Test
    fun assistantNeverTreatsUnsupportedMutationAsAnExecutedActionAndAcceptsCheckedClassificationArrays() {
        val blocked = AssistantProtocol.unsupported("احذف جميع المشاوير")!!
        assertEquals("none", blocked.action)
        assertNull(AssistantProtocol.unsupported("افتح الخريطة"))
        val proposal =
            AssistantProtocol.proposal(
                AssistantReply("تم حذف جميع المشاوير", "navigate", "history")
            )
        assertFalse(proposal.reply.contains("حذف"))
        assertTrue(proposal.reply.contains("اقتراح"))
        val tracks = listOf(AudioTrack("a", "content://a", "فقه", "محاضر"))
        val array = """[{"id":"a","topic":"فقه","source":"محاضر"}]"""
        assertEquals("a", AudioOrganizer.parse(array, tracks).single().trackId)
        assertThrows(Exception::class.java) { AssistantProtocol.reply(array) }
    }

    @Test
    fun classificationKeepsKnownArtistAndGroupsSpecificTitlesIntoBroadTopics() {
        val track = AudioTrack("a", "content://a", "شرح فقه الصلاة", "عثمان الخميس")
        val answer =
            """{"items":[{"id":"a","topic":"شرح فقه الصلاة","source":"المحاضر أو المصدر"}]}"""
        val row = AudioOrganizer.parse(answer, listOf(track)).single()
        assertEquals("عثمان الخميس", row.source)
        assertEquals("فقه", row.topic)
    }

    @Test
    fun extractorPartialFrameNeverBecomesCodecEndOfStreamAndEncryptedAudioIsRejected() {
        val flags = studioInputFlags(android.media.MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)
        assertEquals(android.media.MediaCodec.BUFFER_FLAG_PARTIAL_FRAME, flags)
        assertEquals(0, flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        assertThrows(IllegalArgumentException::class.java) {
            studioInputFlags(android.media.MediaExtractor.SAMPLE_FLAG_ENCRYPTED)
        }
    }

    private fun process(
        scene: AudioScene,
        samples: ShortArray,
        chunks: Int = samples.size,
    ): ShortArray {
        val processor = LectureAudioProcessor(scene)
        processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val result = mutableListOf<Short>()
        for (chunk in samples.toList().chunked(chunks)) {
            val input = ByteBuffer.allocateDirect(chunk.size * 2).order(ByteOrder.nativeOrder())
            chunk.forEach { input.putShort(it) }
            input.flip()
            processor.queueInput(input)
            val output = processor.output
            while (output.hasRemaining()) result += output.short
        }
        return result.toShortArray()
    }

    private fun tone(hz: Int) =
        ShortArray(44100 * 2) { (sin(2 * PI * hz * (it / 2) / 44100) * 7000).toInt().toShort() }

    private fun rms(samples: ShortArray) =
        sqrt(samples.drop(4000).map { it.toDouble() * it }.average())

    @Test
    fun softProfileReducesHarshHighFrequencyAndKeepsSpeechBand() {
        val high = tone(10000)
        val speech = tone(1000)
        assertTrue(rms(process(AudioScene.LECTURE_SOFT, high)) < rms(high) * .12)
        assertTrue(rms(process(AudioScene.LECTURE_SOFT, speech)) > rms(speech) * .8)
    }

    @Test
    fun lectureProfilesPreserveSilenceAndStereoFramingAcrossSplitBuffers() {
        for (scene in listOf(AudioScene.LECTURE_SOFT, AudioScene.LECTURE_CLEAR)) {
            assertTrue(process(scene, ShortArray(1000), 3).all { it == 0.toShort() })
            val signal = tone(1700).take(8000).toShortArray()
            assertArrayEquals(process(scene, signal), process(scene, signal, 7))
        }
    }

    @Test
    fun windowsAndCrossfadesPreserveDurationAndConstantSignalAcrossManyBoundaries() {
        for (frames in listOf(1, 20, 100, 101, 111, 190, 191, 200, 500)) {
            val result = mutableListOf<Float>()
            val joiner = StereoJoiner(10) { result.addAll(it.toList()) }
            val windows = StereoWindows(100, 10, joiner::add)
            repeat(frames) { windows.add(.2f, -.3f) }
            windows.finish()
            joiner.finish()
            assertEquals(frames * 2, result.size)
            result.forEachIndexed { i, sample ->
                assertEquals(if (i % 2 == 0) .2f else -.3f, sample, .00001f)
            }
        }
    }

    @Test
    fun resamplerKeepsDurationAndSanitizesInvalidSamples() {
        for (rate in listOf(8000, 16000, 44100, 48000, 96000)) {
            var count = 0
            val resampler =
                StereoResampler(rate) { l, r ->
                    assertTrue(l.isFinite() && r.isFinite())
                    count++
                }
            repeat(rate) { resampler.add(if (it == 7) Float.NaN else .2f, Float.POSITIVE_INFINITY) }
            resampler.finish()
            assertTrue("rate=$rate count=$count", abs(count - 44100) <= 1)
        }
    }

    @Test
    fun waveRoundtripRejectsTruncationAndPreservesStereoSamples() {
        val file = java.io.File.createTempFile("studio-wave", ".wav")
        try {
            val samples = floatArrayOf(.2f, -.3f, 1f, -1f)
            StudioWave.write(file, samples)
            val decoded = StudioWave.read(file)
            assertEquals(samples.size, decoded.size)
            samples.indices.forEach { assertEquals(samples[it], decoded[it], .0001f) }
            java.io.RandomAccessFile(file, "rw").use { it.setLength(46) }
            assertThrows(Exception::class.java) { StudioWave.read(file) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun assistantRejectsUnsafeActionsUnknownPagesAndInjectedTrailingOutput() {
        for (text in
            listOf(
                "{\"reply\":\"ok\",\"action\":\"delete_files\"}",
                "{\"reply\":\"ok\",\"action\":\"navigate\",\"value\":\"shell\"}",
                "{\"reply\":\"ok\",\"action\":\"none\"} {}",
            )) assertThrows(Exception::class.java) { AssistantProtocol.reply(text) }
        assertEquals(
            "audio",
            AssistantProtocol.reply(
                    "```json\n{\"reply\":\"فتح المكتبة\",\"action\":\"navigate\",\"value\":\"audio\"}\n```"
                )
                .value,
        )
    }

    @Test
    fun classificationRejectsUnknownOrDuplicateIdsAndSanitizesFolderNames() {
        val tracks =
            listOf(AudioTrack("a", "content://a", "تفسير"), AudioTrack("b", "content://b", "حديث"))
        val invalid =
            "{\"items\":[{\"id\":\"a\",\"topic\":\"فقه\",\"source\":\"مصدر\"},{\"id\":\"a\",\"topic\":\"فقه\",\"source\":\"مصدر\"}]}"
        assertThrows(Exception::class.java) { AudioOrganizer.parse(invalid, tracks) }
        val safe = folderLabel("../../مسار\\file:\u0000")
        assertFalse(safe.contains('/'))
        assertFalse(safe.contains('\\'))
        assertFalse(safe.contains(".."))
        assertFalse(safe.contains('\u0000'))
    }

    @Test
    fun sharedFolderUpsertRetainsEarlierFilingsAndBackupRetainsTuning() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AudioDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, AudioDatabase::class.java).build()
        val legacy = Room.inMemoryDatabaseBuilder(context, AudioDatabase::class.java).build()
        try {
            val repo = AudioRepository(context, db)
            listOf("a", "b").forEach {
                db.dao()
                    .insert(
                        AudioTrack(
                            it,
                            "content://$it",
                            it,
                            durationMs = 60000,
                            favorite = true,
                            positionMs = 40000,
                        )
                    )
            }
            AudioOrganizer.applyVirtual(repo, listOf(FilingSuggestion("a", "فقه", "محاضر")))
            AudioOrganizer.applyVirtual(repo, listOf(FilingSuggestion("b", "فقه", "محاضر")))
            db.dao().tuning(AudioTuning("a", "lecture_soft"))
            assertEquals(2, db.dao().filings().first().size)
            val backup = AudioBackup(db).export()
            AudioBackup(target).restore(backup)
            assertEquals(db.dao().filings().first().toSet(), target.dao().filings().first().toSet())
            assertEquals("lecture_soft", target.dao().tuning("a")!!.scene)
            val old =
                JSONObject(backup).put("version", 1).apply {
                    remove("folders")
                    remove("filings")
                    remove("tuning")
                }
            AudioBackup(legacy).restore(old.toString())
            assertEquals(2, legacy.dao().tracks().first().size)
        } finally {
            db.close()
            target.close()
            legacy.close()
        }
    }

    @Test
    fun migrationPreservesOldTracksPlaylistsFavoritesPositionsAndBookmarks() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "studio-migrate.db"
        context.deleteDatabase(name)
        // Build a real schema-1 database using the checked-in Room schema, including its identity
        // hash.
        val schema =
            JSONObject(java.io.File("schemas/sa.rihla.audio.AudioDatabase/1.json").readText())
                .getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(
                    entity
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName"))
                )
                val indexes = entity.getJSONArray("indices")
                for (j in 0 until indexes.length()) old.execSQL(
                    indexes
                        .getJSONObject(j)
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName"))
                )
            }
            old.execSQL(
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
            )
            old.execSQL(
                "INSERT INTO room_master_table VALUES(42,?)",
                arrayOf(schema.getString("identityHash")),
            )
            old.execSQL(
                "INSERT INTO audio_tracks VALUES('a','content://a','قديم','محاضر','ألبوم',90000,1,42000,100,1,'')"
            )
            old.execSQL("INSERT INTO audio_playlists VALUES(1,'قائمة')")
            old.execSQL("INSERT INTO audio_playlist_items VALUES(1,'a',0)")
            old.execSQL("INSERT INTO audio_bookmarks VALUES(1,'a',12000,'علامة')")
            old.version = 1
        }
        val db =
            Room.databaseBuilder(context, AudioDatabase::class.java, name)
                .addMigrations(AudioDatabase.MIGRATION_1_2)
                .build()
        try {
            val old = db.dao().track("a")!!
            assertTrue(old.favorite)
            assertEquals(42000L, old.positionMs)
            assertEquals(1, db.dao().members().first().size)
            assertEquals(1, db.dao().bookmarks().first().size)
            db.dao().tuning(AudioTuning("a", "lecture_clear"))
            assertEquals("lecture_clear", db.dao().tuning("a")!!.scene)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
