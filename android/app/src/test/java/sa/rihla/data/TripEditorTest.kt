package sa.rihla.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sa.rihla.core.EngineState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TripEditorTest {
    @Test
    fun splittingPreservesDistanceExpensesAndStopTimeAndMergingRetainsAllRecords() = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    JourneyDatabase::class.java,
                )
                .build()
        try {
            val id =
                db.dao()
                    .insert(
                        Outing(
                            startedAt = 1000,
                            endedAt = 31000,
                            state =
                                EngineState(
                                    phase = "ENDED",
                                    meters = 200.0,
                                    stoppedMs = 10000,
                                    drivingMs = 20000,
                                    visits = 1,
                                    universityMs = 20000,
                                ),
                        )
                    )
            listOf(1000L, 11000L, 21000L, 31000L).forEachIndexed { index, time ->
                db.dao()
                    .point(
                        RoutePoint(
                            outingId = id,
                            latitude = 21.5 + index * .0005,
                            longitude = 39.2,
                            accuracy = 5.0,
                            speed = 5.0,
                            altitude = 0.0,
                            bearing = 0.0,
                            recordedAt = time,
                            distanceDelta = if (index == 0 || index == 2) 0.0 else 100.0,
                        )
                    )
            }
            db.dao()
                .event(
                    JourneyEvent(
                        outingId = id,
                        type = "DRIVE",
                        startedAt = 1000,
                        endedAt = 11000,
                        latitude = 21.5,
                        longitude = 39.2,
                        confirmed = true,
                    )
                )
            db.dao()
                .event(
                    JourneyEvent(
                        outingId = id,
                        type = "STOP",
                        startedAt = 11000,
                        endedAt = 21000,
                        latitude = 21.5,
                        longitude = 39.2,
                        confirmed = true,
                    )
                )
            db.dao()
                .event(
                    JourneyEvent(
                        outingId = id,
                        type = "DRIVE",
                        startedAt = 21000,
                        endedAt = 31000,
                        latitude = 21.5,
                        longitude = 39.2,
                        confirmed = true,
                    )
                )
            db.dao()
                .event(
                    JourneyEvent(
                        outingId = id,
                        type = "VISIT",
                        startedAt = 5000,
                        endedAt = 25000,
                        latitude = 21.5,
                        longitude = 39.2,
                        confirmed = true,
                    )
                )
            db.dao().details(TripDetails(id, "رحلة", "ملاحظة", true))
            db.dao()
                .expense(
                    Expense(outingId = id, category = "FOOD", amountHalala = 1000, createdAt = 5000)
                )
            db.dao()
                .expense(
                    Expense(
                        outingId = id,
                        category = "OTHER",
                        amountHalala = 2000,
                        createdAt = 22000,
                    )
                )
            val editor = TripEditor(db)
            val second = editor.split(id, 11000)
            assertEquals(200.0, db.dao().outings().sumOf { it.state.meters }, .001)
            assertEquals(10000L, db.dao().outings().sumOf { it.state.stoppedMs })
            assertEquals(20000L, db.dao().outings().sumOf { it.state.drivingMs })
            assertEquals(setOf(id, second), db.dao().expenseRecords().map { it.outingId }.toSet())
            assertEquals(5, db.dao().points(id).first().size + db.dao().points(second).first().size)
            assertTrue(db.dao().details(second)!!.favorite)
            val third = editor.split(second, 21000)
            assertEquals(1, db.dao().outings().sumOf { it.state.visits })
            assertEquals(20000L, db.dao().outings().sumOf { it.state.universityMs })
            assertEquals(id, editor.merge(id, second))
            assertEquals(id, editor.merge(id, third))
            assertEquals(1, db.dao().outings().size)
            assertEquals(200.0, db.dao().outings().single().state.meters, .001)
            assertEquals(3000L, db.dao().expenseRecords().sumOf { it.amountHalala })
            assertTrue(db.dao().expenseRecords().all { it.outingId == id })
            assertEquals(1, db.dao().outings().single().state.visits)
            assertEquals(setOf(1L, 2L, 3L, 4L), db.dao().points(id).first().map { it.id }.toSet())
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                assertFalse(it.moveToFirst())
            }
            val old = LocalBackup(db).export()
            try {
                editor.split(id, 1000)
                fail("Endpoint split accepted")
            } catch (_: IllegalArgumentException) {}
            // Export times vary, compare retained records rather than serialized timestamps.
            assertEquals(1, db.dao().outings().size)
            assertEquals(4, db.dao().points(id).first().size)
            assertTrue(old.contains("ملاحظة"))
        } finally {
            db.close()
        }
    }
}
