package sa.rihla.core

import android.graphics.Bitmap
import androidx.room.Room
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sa.rihla.RihlaApp
import sa.rihla.assistant.*
import sa.rihla.audio.*
import sa.rihla.audio.studio.TranscriptEngine
import sa.rihla.data.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class)
class MashwariV7FeaturesTest {

    @Test
    fun indoorHomeFixAutoEndsTripViaWatchdogWithoutCorruptingRoutePolyline() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java).build()
        val settings = Settings(context)
        try {
            val repo = JourneyRepository(db, settings)
            db.dao().place(SavedPlace("HOME", 21.5000, 39.2000, 150.0))
            val t0 = 1_000_000L
            val tripId = repo.start(true, t0)
            // Start at home, drive 1 km away
            repo.record(Fix(21.5000, 39.2000, t0 + 1_000, 6.0, 0.5))
            repo.record(Fix(21.5090, 39.2000, t0 + 60_000, 6.0, 15.0))
            val metersAway = db.dao().active()!!.state.meters
            assertTrue(metersAway > 800.0)
            val pointsAway = db.dao().points(tripId).first().size

            // Return inside indoor parking at home with degraded Wi-Fi/Cell fix (accuracy = 95m)
            val returnTime = t0 + 180_000L
            val res = repo.recordDetailed(Fix(21.5001, 39.2001, returnTime, 95.0, 0.2))
            assertTrue(res.accepted)
            assertFalse(res.ended)
            val pendingTrip = db.dao().active()!!
            assertEquals("AT_HOME_PENDING_END", pendingTrip.state.phase)
            // Degraded indoor fix NEVER pollutes RoutePoints or total distance!
            assertEquals(pointsAway, db.dao().points(tripId).first().size)
            assertEquals(metersAway, pendingTrip.state.meters, 0.001)

            // Watchdog completes auto-end after 5 minutes even with zero new LocationCallbacks!
            assertFalse(repo.evaluateWatchdogAutoEnd(returnTime + 120_000L, 0L))
            assertTrue(repo.evaluateWatchdogAutoEnd(returnTime + 305_000L, 0L))
            assertNull(db.dao().active())
            val finished = db.dao().outings().single()
            assertTrue(finished.automaticEnd)
            assertEquals("COMPLETED", finished.state.phase)
        } finally {
            settings.clear()
            db.close()
        }
    }

    @Test
    fun adaptiveIntervalSupportsInstantWakeDeepSleepAndStraightRoadCruising() {
        assertEquals(
            1000L,
            adaptiveTrackingInterval(
                stopped = false,
                precise = false,
                speed = 0.0,
                boost = true,
                instantWakeup = true,
            ),
        )
        assertEquals(
            300_000L,
            adaptiveTrackingInterval(
                stopped = true,
                precise = false,
                speed = 0.0,
                boost = false,
                deepSleep = true,
            ),
        )
        assertEquals(
            4500L,
            adaptiveTrackingInterval(
                stopped = false,
                precise = false,
                speed = 25.0,
                boost = false,
                turn = false,
                straightRoad = true,
                accuracy = 6.0,
            ),
        )
    }

    @Test
    fun uquDepartureAdvisorBottleneckHeatmapAndUniversityFinanceCalculateAccurately() {
        // Sunday 07:30 AM Asia/Riyadh
        val sundayMorning =
            ZonedDateTime.of(2026, 11, 8, 7, 30, 0, 0, SaudiZone).toInstant().toEpochMilli()
        val schedule =
            listOf(
                UniversityScheduleEntry(
                    id = 1,
                    dayOfWeek = 1, // Sunday
                    courseTitle = "هندسة البرمجيات",
                    buildingOrGate = "كلية الحاسب · بوابة ٣",
                    startMinuteOfDay = 8 * 60 + 30, // 08:30 AM
                    endMinuteOfDay = 10 * 60,
                )
            )
        val weekdayOuting =
            Outing(
                id = 1,
                startedAt = sundayMorning - 7 * 86_400_000L,
                endedAt = sundayMorning - 7 * 86_400_000L + 1800_000L,
                classification = "HOME_TO_UNIVERSITY",
                state =
                    EngineState(
                        meters = 22_000.0,
                        homeToUniversityMs = 30 * 60_000L,
                        visits = 1,
                    ),
            )
        val advice =
            UniversityDepartureAdvisor.nextLectureAdvice(
                schedule = schedule,
                outings = listOf(weekdayOuting),
                nowMs = sundayMorning,
            )!!
        assertTrue(advice.isToday)
        assertEquals(30, advice.estimatedTravelMinutes)
        assertEquals(10, advice.bufferMinutes)
        // 08:30 - (30 + 10) = 07:50 -> 20 minutes from 07:30!
        assertEquals(20, advice.minutesUntilDeparture)

        // Bottleneck detection across 3 outings in the same ~50m grid cell (< 20 km/h)
        val outings =
            (1L..3L).map { id ->
                Outing(id = id, startedAt = id * 100_000L, endedAt = id * 100_000L + 50_000L)
            }
        val ptsByOuting =
            outings.associate { o ->
                o.id to
                    listOf(
                        RoutePoint(0, o.id, 21.4200, 39.8200, 6.0, 3.0, 0.0, 90.0, o.startedAt + 1000, false, 15.0, false),
                        RoutePoint(0, o.id, 21.4201, 39.8201, 6.0, 2.5, 0.0, 90.0, o.startedAt + 8000, false, 18.0, false),
                    )
            }
        val bottlenecks =
            BottleneckAnalyzer.computeBottlenecks(
                outings = outings,
                pointsByOuting = ptsByOuting,
                eventsByOuting = emptyMap(),
                places = listOf(SavedPlace("HOME", 21.55, 39.20, 200.0)),
            )
        assertEquals(1, bottlenecks.size)
        assertEquals(3, bottlenecks.single().outingCount)

        // University Day Cost + Remaining Father Transfer Balance
        val uniSummary =
            UniversityFinanceCalculator.computeUniversityDayCosts(
                outings = listOf(weekdayOuting),
                events = emptyList(),
                expenses =
                    listOf(
                        Expense(
                            id = 1,
                            outingId = 1,
                            category = "FOOD",
                            amountHalala = 1800L,
                            createdAt = weekdayOuting.startedAt + 600_000L,
                            merchant = "دانكن",
                            latitude = 21.33,
                            longitude = 39.95,
                        )
                    ),
                defaultProfile = ConsumptionProfile(6500, 6500, "REFERENCE"),
                defaultFuelPriceHalala = 233L,
                nowMs = sundayMorning,
            )
        assertEquals(1, uniSummary.universityDaysCount)
        assertTrue(uniSummary.averageUniversityDayHalala > 1800L)

        val bal =
            UniversityFinanceCalculator.computeRemainingTransferBalance(
                transferRows =
                    listOf(
                        TransferRow(
                            id = "t1",
                            amount = 150_000L, // 1500 SAR
                            sender = "محمد عبدالله",
                            time = sundayMorning - 86_400_000L,
                            bodyHash = "h1",
                            decision = 1,
                            duplicate = false,
                            origin = "sms",
                            receivedAt = sundayMorning - 86_400_000L,
                        )
                    ),
                aliases = setOf("محمد عبدالله"),
                cutoffMs = sundayMorning - 2 * 86_400_000L,
                expenses =
                    listOf(
                        Expense(
                            id = 2,
                            category = "FOOD",
                            amountHalala = 25_000L, // 250 SAR
                            createdAt = sundayMorning - 3_600_000L,
                        )
                    ),
                nowMs = sundayMorning,
            )
        assertTrue(bal.configured)
        assertEquals(125_000L, bal.remainingHalala)
    }

    @Test
    fun receiptVaultUnifiedZipBackupSmartMergeAndRollingPruneWorkEndToEnd(): Unit = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        app.repository.clear()
        app.audio.dao.clearTracks()
        app.settings.clear()

        // 1. Save a compressed receipt photo & warranty expense
        val bmp = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
        val receiptPath = ReceiptStore.saveBitmap(app, bmp, 1_700_000_000_000L)
        assertTrue(File(receiptPath).exists())
        val now = System.currentTimeMillis()
        val expId =
            app.repository.addExpense(
                category = "OTHER",
                amount = 19900L,
                liters = null,
                price = null,
                full = false,
                time = now - 10_000L,
                outingId = null,
                merchant = "سماعة بلوتوث",
                receiptImageUri = receiptPath,
                warrantyExpiryAt = now + 10 * 86_400_000L,
            )
        assertEquals(1, ReceiptStore.expiringWarranties(app.repository.dao.expenseRecords(), now).size)

        // 2. Add audio track + transcript + personal assistant example
        val tr = AudioTrack("tr-v7", "content://test/v7", "محاضرة الخوارزميات", "د. أحمد", "أم القرى", 1_800_000L)
        app.audio.add(tr)
        TranscriptEngine.importExternalTranscript(
            app.audio,
            tr.id,
            "1\n00:01:00,000 --> 00:01:20,000\nشرح خوارزمية البحث الثنائي بالتفصيل\n",
        )
        assertEquals(1, app.audio.dao.searchTranscripts("الثنائي").size)
        PersonalAssistantStore(app).train(listOf(CommandExample("ورني كوكبيت اللانسر", "cockpit")))

        // 3. Export Unified v7.0 .zip archive
        val unified = UnifiedBackupManager(app)
        val zipBytes = ByteArrayOutputStream().also { unified.exportToStream(it) }.toByteArray()
        assertTrue(zipBytes.size > 200)

        // 4. Add a new local expense, then SMART_MERGE the zip -> BOTH expenses & receipt survive!
        File(receiptPath).delete()
        app.repository.addExpense(
            category = "FOOD",
            amount = 1500L,
            liters = null,
            price = null,
            full = false,
            time = now - 2_000L,
            outingId = null,
            merchant = "قهوة الصباح",
        )
        val restoredSummary =
            unified.restoreFromStream(ByteArrayInputStream(zipBytes), RestoreMode.SMART_MERGE)
        assertEquals(1, restoredSummary.expenses)
        assertEquals(1, restoredSummary.audioTracks)
        assertEquals(2, app.repository.dao.expenseRecords().size)
        val restoredReceipt =
            app.repository.dao.expenseRecords().first { it.id == expId || it.amountHalala == 19900L }.receiptImageUri
        assertNotNull(restoredReceipt)
        assertTrue(File(restoredReceipt!!).exists())

        // 5. Rolling backup retention keeps strictly the newest 7 auto-backups
        val tmpDir = File(app.cacheDir, "rolling_test").apply { deleteRecursively(); mkdirs() }
        for (day in 1..10) {
            File(tmpDir, "mashwari-auto-2026-11-%02d.zip".format(day)).writeText("zip-$day")
        }
        unified.pruneRollingBackups(tmpDir, 7)
        val remaining = tmpDir.listFiles()!!.map { it.name }.sorted()
        assertEquals(7, remaining.size)
        assertEquals("mashwari-auto-2026-11-04.zip", remaining.first())
        assertEquals("mashwari-auto-2026-11-10.zip", remaining.last())
        tmpDir.deleteRecursively()
        Unit
    }

    @Test
    fun speedAdaptiveProcessorSmartQueueAutoStopBookmarkAndZeroConfirmVoiceUndoWork(): Unit =
        runBlocking {
            val app = RuntimeEnvironment.getApplication() as RihlaApp
            app.repository.clear()
            app.audio.dao.clearTracks()
            app.settings.clear()

            // 1. SpeedAdaptiveAudioProcessor is 100% bit-exact at 0 km/h and boosts presence at 100 km/h
            SpeedAdaptiveAudio.reset()
            val proc = SpeedAdaptiveAudioProcessor()
            proc.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(44100, 2, androidx.media3.common.C.ENCODING_PCM_16BIT))
            proc.flush(androidx.media3.common.audio.AudioProcessor.StreamMetadata.DEFAULT)
            val samples = ShortArray(2048) { idx -> (kotlin.math.sin(idx * 0.34) * 8000).toInt().toShort() }
            val inputBuf =
                ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                    samples.forEach { putShort(it) }
                    flip()
                }
            proc.queueInput(inputBuf.duplicate())
            val passthrough = proc.output
            val outPassthrough = ShortArray(samples.size) { passthrough.short }
            assertArrayEquals(samples, outPassthrough)

            // Highway 100 km/h (~27.8 m/s)
            repeat(8) { SpeedAdaptiveAudio.updateSpeed(28.0, outingActive = true, enabled = true) }
            assertTrue(SpeedAdaptiveAudio.presenceBoostFactor > 0.5f)
            proc.queueInput(inputBuf.duplicate())
            val boosted = proc.output
            val outBoosted = ShortArray(samples.size) { boosted.short }
            val origEnergy = samples.drop(200).sumOf { it.toLong() * it.toLong() }
            val boostEnergy = outBoosted.drop(200).sumOf { it.toLong() * it.toLong() }
            assertTrue("Expected speech presence energy boost on highway", boostEnergy > origEnergy)
            SpeedAdaptiveAudio.reset()

            // 2. SmartTripQueueBuilder builds queue matching 25-min commute & resumes unfinished lecture
            val t1 = AudioTrack("q1", "u1", "محاضرة الشبكات", "د. خالد", "UQU", 20 * 60_000L, positionMs = 5 * 60_000L)
            val t2 = AudioTrack("q2", "u2", "ملخص أمن المعلومات", "د. سعد", "UQU", 10 * 60_000L)
            app.audio.add(t1)
            app.audio.add(t2)
            val queuePlan = SmartTripQueueBuilder.buildQueue(listOf(t1, t2), 25 * 60_000L)!!
            assertEquals("q1", queuePlan.tracks.first().id)
            assertEquals(5 * 60_000L, queuePlan.startPositionMs)
            assertEquals(2, queuePlan.tracks.size)

            // 3. AutoStopBookmarkManager saves -10s bookmark and debounces within 90s
            AudioLive.state.value = AudioPlayback(id = "q1", title = t1.title, playing = true, position = 120_000L, duration = t1.durationMs)
            val mark1 = AutoStopBookmarkManager.onStopOrArrival(app, "توقف إشارة", 5_000_000L)
            assertNotNull(mark1)
            assertEquals(110_000L, mark1!!.positionMs)
            assertNull(AutoStopBookmarkManager.onStopOrArrival(app, "توقف ثاني", 5_030_000L))
            AudioLive.state.value = AudioPlayback()

            // 4. Built-in 44 commands + Zero-Confirmation Voice Expense & Undo + Daily Briefing
            assertTrue(AppCommands.all.size >= 42)
            val coffeeCmd = ParameterizedVoiceParser.parse("يا مساعدي سجل قهوة بـ 18 ريال")!!
            assertEquals("expense", coffeeCmd.kind)
            assertEquals("FOOD", coffeeCmd.category)
            assertEquals(1800L, coffeeCmd.amountHalala)
            val execReply = ZeroConfirmExecutor.executeParameterized(app, coffeeCmd, speakReply = true)
            assertEquals("executed", execReply.action)
            assertEquals(1, app.repository.dao.expenseRecords().size)
            assertNotNull(VoiceUndoBus.latest.value)

            // Instant Undo removes the voice-inserted expense!
            val undoMsg = ZeroConfirmExecutor.undo(app, null)
            assertNotNull(undoMsg)
            assertTrue(app.repository.dao.expenseRecords().isEmpty())

            // Daily Briefing synthesizes real-time summary
            val briefing = DailyBriefingBuilder.buildBriefing(app, System.currentTimeMillis())
            assertTrue(briefing.contains("صباح الخير"))
            assertTrue(briefing.contains("محاضرة"))
        }
}
