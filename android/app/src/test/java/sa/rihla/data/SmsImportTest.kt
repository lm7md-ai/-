package sa.rihla.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SmsImportTest {
    @Test
    fun oldDeletedReceiptStillBlocksReimportAfterBankTimestampUpgrade() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), JourneyDatabase::class.java).build()
        try {
            val body = "شراء PoS\nبـSR 32\nلـEXPRESS FOOD\n26/10/14 16:51"
            val arrival = java.time.Instant.parse("2026-10-14T13:51:20Z").toEpochMilli()
            val normalized = sa.rihla.core.normalizeSms(body).replace(Regex("\\s+"), " ").trim()
            val legacyKey = java.security.MessageDigest.getInstance("SHA-256")
                .digest("test.messages\n$arrival\n$normalized".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            db.dao().claimSms(ImportedSms(legacyKey, arrival))
            assertFalse(SmsExpenseImporter(db).import("test.messages", body, arrival + 20_000))
            assertTrue(db.dao().expenseRecords().isEmpty())
        } finally { db.close() }
    }
    @Test
    fun rajhiSmsBroadcastAndNotificationSaveOneExpenseAtBankTime() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), JourneyDatabase::class.java).build()
        try {
            val body = "شراء PoS\nعبر5231;مدى-جوجل باي\nبـSR 32\nلـEXPRESS FOOD\n26/10/14 16:51"
            val arrival = java.time.Instant.parse("2026-10-14T13:51:20Z").toEpochMilli()
            val importer = SmsExpenseImporter(db)
            assertTrue(importer.import("test.messages", body, arrival))
            assertFalse(importer.import("test.messages", body, arrival + 20_000))
            val saved = db.dao().expenseRecords().single()
            assertEquals(3200L, saved.amountHalala)
            assertEquals("EXPRESS FOOD", saved.merchant)
            assertEquals(arrival - 20_000, saved.createdAt)
        } finally { db.close() }
    }
    @Test
    fun enablingAndDisablingPreventsOldAndDisabledMessagesFromBeingImported() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java).build()
        val settings = Settings(context)
        try {
            settings.clear()
            val repo = JourneyRepository(db, settings)
            val body = "شراء مبلغ 25 SAR"
            assertFalse(repo.importSms("test.messages", body, System.currentTimeMillis()))
            settings.smsImport(true)
            val since = settings.flow.first().smsImportSince
            assertFalse(repo.importSms("test.messages", body, since - 1))
            assertTrue(repo.importSms("test.messages", body, since))
            settings.smsImport(false)
            assertFalse(repo.importSms("test.messages", body, since + 1))
            assertEquals(1, db.dao().expenseRecords().size)
        } finally {
            settings.clear()
            db.close()
        }
    }

    @Test
    fun deduplicationSurvivesDatabaseReopening() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "sms-reopen.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
        try {
            assertTrue(SmsExpenseImporter(db).import("test.messages", "شراء مبلغ 25 SAR", 2000))
            db.close()
            db = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
            assertFalse(SmsExpenseImporter(db).import("test.messages", "شراء مبلغ 25 SAR", 2000))
            assertEquals(1, db.dao().expenseRecords().size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun duplicateConcurrentAndDeletedNotificationsNeverRecreateExpenses() = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    JourneyDatabase::class.java,
                )
                .build()
        try {
            val importer = SmsExpenseImporter(db)
            val body = "شراء\nبمبلغ: 24.50 SAR\nلدى: TEST STORE\nالرصيد: 999 SAR"
            val results = coroutineScope {
                (1..5)
                    .map {
                        async(Dispatchers.IO) {
                            importer.import("test.messages", body, 2000)
                        }
                    }
                    .awaitAll()
            }
            assertEquals(1, results.count { it })
            val expense = db.dao().expenseRecords().single()
            assertEquals(2450L, expense.amountHalala)
            assertEquals("SMS_NOTIFICATION", expense.source)
            assertEquals("TEST STORE", expense.merchant)
            assertFalse(expense.importKey!!.contains(body))
            db.dao().deleteImportedExpense(expense.id)
            assertFalse(importer.import("test.messages", body, 2000))
            assertTrue(db.dao().expenseRecords().isEmpty())
            assertTrue(
                importer.import("test.messages", body, 3000)
            ) // Same amount, distinct SMS time.
            assertEquals(1, db.dao().expenseRecords().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun purchaseLinksByArrivalTimeAndNeverInventsFuelLiters() = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    JourneyDatabase::class.java,
                )
                .build()
        try {
            val id = db.dao().insert(Outing(startedAt = 1000, endedAt = 5000))
            val importer = SmsExpenseImporter(db)
            assertTrue(importer.import("test.messages", "شراء مبلغ 50 SAR", 2000))
            assertTrue(importer.import("test.messages", "شراء مبلغ 50 SAR", 6000))
            val expenses = db.dao().expenseRecords().sortedBy { it.createdAt }
            assertEquals(id, expenses[0].outingId)
            assertNull(expenses[1].outingId)
            db.dao().categorizeImportedExpense(expenses[0].id, "FUEL")
            assertTrue(db.dao().fuelRecords().isEmpty())
            assertFalse(importer.import("test.messages", "OTP for Purchase SAR 50: 123456", 7000))
            assertEquals(2, db.dao().expenseRecords().size)
        } finally {
            db.close()
        }
    }
}
