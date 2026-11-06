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
import sa.rihla.core.EngineState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SmsImportTest {
    @Test
    fun oldDeletedReceiptStillBlocksReimportAfterBankTimestampUpgrade() = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    JourneyDatabase::class.java,
                )
                .build()
        try {
            val body = "شراء PoS\nبـSR 32\nلـEXPRESS FOOD\n26/10/14 16:51"
            val arrival = java.time.Instant.parse("2026-10-14T13:51:20Z").toEpochMilli()
            val normalized = sa.rihla.core.normalizeSms(body).replace(Regex("\\s+"), " ").trim()
            val legacyKey =
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest("test.messages\n$arrival\n$normalized".toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
            db.dao().claimSms(ImportedSms(legacyKey, arrival))
            assertFalse(SmsExpenseImporter(db).import("test.messages", body, arrival + 20_000))
            assertTrue(db.dao().expenseRecords().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun rajhiSmsBroadcastAndNotificationSaveOneExpenseAtBankTime() = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    JourneyDatabase::class.java,
                )
                .build()
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
        } finally {
            db.close()
        }
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
            SmsExpenseImporter.clearRecentFingerprintsForTest()
            assertTrue(SmsExpenseImporter(db).import("test.messages", "شراء مبلغ 25 SAR", 2000))
            db.close()
            SmsExpenseImporter.clearRecentFingerprintsForTest()
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
        SmsExpenseImporter.clearRecentFingerprintsForTest()
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
            // Distinct purchase > 10 minutes later (> 600_000 ms) is allowed!
            assertTrue(importer.import("test.messages", body, 2000 + 15 * 60_000L))
            assertEquals(1, db.dao().expenseRecords().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun purchaseLinksByArrivalTimeAndNeverInventsFuelLiters() = runBlocking {
        SmsExpenseImporter.clearRecentFingerprintsForTest()
        val db =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    JourneyDatabase::class.java,
                )
                .build()
        try {
            val id = db.dao().insert(Outing(startedAt = 1_000_000, endedAt = 2_000_000))
            val importer = SmsExpenseImporter(db)
            assertTrue(importer.import("test.messages", "شراء مبلغ 50 SAR", 1_500_000))
            assertTrue(importer.import("test.messages", "شراء مبلغ 50 SAR", 2_500_000))
            val expenses = db.dao().expenseRecords().sortedBy { it.createdAt }
            assertEquals(id, expenses[0].outingId)
            assertNull(expenses[1].outingId)
            db.dao().categorizeImportedExpense(expenses[0].id, "FUEL")
            assertTrue(db.dao().fuelRecords().isEmpty())
            assertFalse(importer.import("test.messages", "OTP for Purchase SAR 50: 123456", 3_500_000))
            assertEquals(2, db.dao().expenseRecords().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun fuzzyTenMinuteWindowDeduplicatesSmsAndNotificationAndSmartMergesManualExpense() =
        runBlocking {
            SmsExpenseImporter.clearRecentFingerprintsForTest()
            val db =
                Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(),
                        JourneyDatabase::class.java,
                    )
                    .build()
            try {
                // Active outing near UQU captures Geo-Merchant coordinates & placeLabel!
                db.dao().place(SavedPlace("UNIVERSITY", 21.3288, 39.9488, 500.0))
                val outingId =
                    db.dao()
                        .insert(
                            Outing(
                                startedAt = 10_000_000L,
                                endedAt = null,
                                state = EngineState(lastLat = 21.3290, lastLon = 39.9490),
                            )
                        )
                db.dao().activate(ActiveSlot(outingId = outingId))

                val importer = SmsExpenseImporter(db)
                // 1) SMS arrives from Telephony without bank timestamp, then Notification arrives 45s later with slightly different formatting
                val smsRaw = "شراء PoS بمبلغ 18.00 SAR لدى DUNKIN COFFEE"
                val notifRaw = "اشعار الراجحي: شراء مبلغ 18 SAR لدى DUNKIN COFFEE CO"
                assertTrue(importer.import("sms", smsRaw, 10_100_000L))
                assertFalse(importer.import("com.alrajhiretailapp", notifRaw, 10_145_000L))
                val first = db.dao().expenseRecords().single()
                assertEquals(1800L, first.amountHalala)
                assertEquals("الجامعة", first.placeLabel)
                assertEquals(21.3290, first.latitude!!, 0.001)

                // 2) Manual expense recorded first, followed 2 minutes later by delayed bank SMS -> Smart Merge (no duplicate!)
                db.dao()
                    .expense(
                        Expense(
                            outingId = outingId,
                            category = "FOOD",
                            amountHalala = 3500L,
                            createdAt = 12_000_000L,
                            source = "MANUAL",
                            merchant = "",
                        )
                    )
                assertFalse(
                    importer.import(
                        "sms",
                        "شراء بمبلغ 35.00 SAR لدى ALBAIK EXPRESS",
                        12_120_000L,
                    )
                )
                assertEquals(2, db.dao().expenseRecords().size)
                val merged = db.dao().expenseRecords().first { it.amountHalala == 3500L }
                assertEquals("ALBAIK EXPRESS", merged.merchant)
                assertEquals("MANUAL", merged.source)
                assertNotNull(merged.importKey)
            } finally {
                db.close()
            }
        }
}
