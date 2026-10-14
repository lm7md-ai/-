package sa.rihla.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sa.rihla.core.EngineState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupTest {
    @Test
    fun wrongTableTypeAndNegativeFuelRollbackRatherThanSilentlyLosingData() = runBlocking {
        val source = database()
        val target = database()
        try {
            seed(source)
            val original = LocalBackup(source).export()
            val malformed = JSONObject(original).apply {
                getJSONObject("tables").put("fuel", JSONObject())
            }
            assertThrows(Exception::class.java) { runBlocking { LocalBackup(target).restore(malformed.toString()) } }
            assertTrue(target.dao().outings().isEmpty())
            val invalidFuel = JSONObject(original).apply {
                getJSONObject("tables").getJSONArray("fuel").getJSONObject(0).put("litersMl", -1)
            }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { LocalBackup(target).restore(invalidFuel.toString()) } }
            assertTrue(target.dao().expenseRecords().isEmpty())
        } finally { source.close(); target.close() }
    }

    @Test
    fun activeSlotCannotReferenceEndedTripAndActiveTripCannotLoseItsSlot() = runBlocking {
        val source = database()
        val target = database()
        try {
            seed(source)
            val text = LocalBackup(source).export()
            val endedActive = JSONObject(text).apply {
                getJSONObject("tables").getJSONArray("active_slot")
                    .put(JSONObject().put("singleton", 1).put("outingId", 1))
            }
            assertThrows(Exception::class.java) { runBlocking { LocalBackup(target).restore(endedActive.toString()) } }
            assertTrue(target.dao().outings().isEmpty())
            val orphanActive = JSONObject(text).apply {
                getJSONObject("tables").getJSONArray("outings").getJSONObject(0).put("endedAt", JSONObject.NULL)
            }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { LocalBackup(target).restore(orphanActive.toString()) } }
            assertTrue(target.dao().outings().isEmpty())
        } finally { source.close(); target.close() }
    }
    private fun database() =
        Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                JourneyDatabase::class.java,
            )
            .build()

    private suspend fun seed(db: JourneyDatabase) {
        db.dao()
            .insert(
                Outing(
                    id = 1,
                    startedAt = 1000,
                    endedAt = 5000,
                    state = EngineState(meters = 10000.0),
                )
            )
        db.dao().point(RoutePoint(1, 1, 21.5, 39.2, 5.0, 10.0, 20.0, 30.0, 2000, true, 100.0, true))
        db.dao().event(JourneyEvent(1, 1, "STOP", 3000, 4000, 21.5, 39.2, true))
        db.dao().place(SavedPlace("HOME", 21.5, 39.2, 200.0))
        db.dao()
            .expense(
                Expense(
                    id = 1,
                    outingId = 1,
                    category = "FUEL",
                    amountHalala = 2180,
                    createdAt = 2000,
                )
            )
        db.dao().fuel(FuelFillUp(1, 1, 2000, 10000, 218, true, 10000.0, true))
        db.dao().details(TripDetails(1, "مشوار", "ملاحظة", true))
        db.dao().claimSms(ImportedSms("hash", 2000))
    }

    @Test
    fun localBackupRestoresEveryTableAndKeepsIdsAndFuelLinks() = runBlocking {
        val source = database()
        val target = database()
        try {
            seed(source)
            val text = LocalBackup(source).export()
            assertEquals(BackupPreview(1, 1, 1), LocalBackup(target).restore(text))
            val original = JSONObject(text).getJSONObject("tables")
            val restored = JSONObject(LocalBackup(target).export()).getJSONObject("tables")
            LocalBackup.tables.forEach { table ->
                assertEquals(
                    table,
                    original.getJSONArray(table).toString(),
                    restored.getJSONArray(table).toString(),
                )
            }
            assertEquals(TripDetails(1, "مشوار", "ملاحظة", true), target.dao().details(1))
            assertEquals(1L, target.dao().fuelRecords().single().expenseId)
        } finally {
            source.close()
            target.close()
        }
    }

    @Test
    fun firstVersionFieldsReceiveMigrationDefaultsWithoutLosingHistory() = runBlocking {
        val source = database()
        val target = database()
        try {
            seed(source)
            val json = JSONObject(LocalBackup(source).export()).put("schemaVersion", 1)
            val tables = json.getJSONObject("tables")
            val outing = tables.getJSONArray("outings").getJSONObject(0)
            listOf(
                    "stillDrivingMs",
                    "fuelPriceHalala",
                    "consumptionMinMl",
                    "consumptionMaxMl",
                    "estimateSource",
                    "fuelGrade",
                )
                .forEach { outing.remove(it) }
            tables.getJSONArray("points").getJSONObject(0).remove("uncertainGap")
            val expense = tables.getJSONArray("expenses").getJSONObject(0)
            listOf("source", "merchant", "importKey").forEach { expense.remove(it) }
            tables.remove("trip_details")
            tables.remove("imported_sms")
            LocalBackup(target).restore(json.toString())
            assertEquals(10000.0, target.dao().outings().single().state.meters, 0.0)
            assertEquals("MANUAL", target.dao().expenseRecords().single().source)
            assertTrue(target.dao().points(1).first().single().uncertainGap)
            assertEquals(2180L, target.dao().expenseRecords().single().amountHalala)
        } finally {
            source.close()
            target.close()
        }
    }

    @Test
    fun malformedForeignKeysRollBackAndNonemptyTargetIsNeverOverwritten() = runBlocking {
        val source = database()
        val target = database()
        try {
            seed(source)
            val json = JSONObject(LocalBackup(source).export())
            json
                .getJSONObject("tables")
                .getJSONArray("points")
                .getJSONObject(0)
                .put("outingId", 999)
            try {
                LocalBackup(target).restore(json.toString())
                fail("Invalid reference accepted")
            } catch (_: Exception) {}
            assertTrue(target.dao().outings().isEmpty())
            target.dao().insert(Outing(id = 5, startedAt = 500))
            try {
                LocalBackup(target).restore(LocalBackup(source).export())
                fail("Existing ledger replaced")
            } catch (error: Exception) {
                assertTrue(error.message!!.contains("BACKUP_NOT_EMPTY"))
            }
            assertEquals(5L, target.dao().outings().single().id)
        } finally {
            source.close()
            target.close()
        }
    }

    @Test
    fun futureSchemaAndNegativeSpendingAreRejected() = runBlocking {
        val source = database()
        val target = database()
        try {
            seed(source)
            val json = JSONObject(LocalBackup(source).export())
            try {
                LocalBackup.preview(json.put("schemaVersion", 99).toString())
                fail("Future schema accepted")
            } catch (_: IllegalArgumentException) {}
            json
                .put("schemaVersion", 5)
                .getJSONObject("tables")
                .getJSONArray("expenses")
                .getJSONObject(0)
                .put("amountHalala", -1)
            try {
                LocalBackup(target).restore(json.toString())
                fail("Negative expense accepted")
            } catch (_: IllegalArgumentException) {}
            assertTrue(target.dao().outings().isEmpty())
        } finally {
            source.close()
            target.close()
        }
    }
}
