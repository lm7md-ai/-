package sa.rihla.data

import android.app.Application
import androidx.room.Room
import java.time.LocalDate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WalletProTest {
    @Test
    fun rulesClassifyNewPurchasesAndBackupPreservesRulesSchedulesAndOdometer() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java).build()
        try {
            db.dao().rule(MerchantRule(pattern = "test store", category = "FOOD"))
            assertTrue(
                SmsExpenseImporter(db)
                    .import("messages", "شراء\nبمبلغ: 24.50 SAR\nلدى: TEST STORE", 2000)
            )
            assertEquals("FOOD", db.dao().expenseRecords().single().category)
            db.dao()
                .recurring(
                    RecurringExpense(
                        title = "اشتراك",
                        category = "OTHER",
                        amountHalala = 3000,
                        dayOfMonth = 31,
                    )
                )
            val expense =
                db.dao().expense(Expense(category = "FUEL", amountHalala = 2180, createdAt = 3000))
            db.dao()
                .fuel(
                    FuelFillUp(
                        expenseId = expense,
                        createdAt = 3000,
                        litersMl = 10000,
                        priceHalala = 218,
                        full = true,
                        trackedMeters = 0.0,
                        reliable = false,
                        odometerMeters = 100_000_000.0,
                    )
                )
            val backup = LocalBackup(db).export()
            LocalBackup(target).restore(backup)
            assertEquals(db.dao().rules(), target.dao().rules())
            assertEquals(db.dao().fuelRecords(), target.dao().fuelRecords())
            assertEquals(db.dao().recurring(1), target.dao().recurring(1))
        } finally {
            target.close()
            db.close()
        }
    }

    @Test
    fun paymentIsConfirmedOncePerMonthAndInvalidOdometersRollBack() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java).build()
        try {
            val repo = JourneyRepository(db, Settings(context))
            db.dao()
                .recurring(
                    RecurringExpense(
                        title = "اشتراك",
                        category = "OTHER",
                        amountHalala = 3000,
                        dayOfMonth = 1,
                    )
                )
            val results = coroutineScope {
                (1..5)
                    .map {
                        async(Dispatchers.IO) { repo.payRecurring(1, LocalDate.of(2026, 10, 13)) }
                    }
                    .awaitAll()
            }
            assertEquals(1, results.count { it })
            assertEquals(1, db.dao().expenseRecords().size)
            assertTrue(repo.payRecurring(1, LocalDate.of(2026, 11, 13)))
            repo.addExpense("FUEL", 2180, 10000, 218, true, 1000, null, 100_000_000.0)
            try {
                repo.addExpense("FUEL", 2180, 10000, 218, true, 2000, null, 99_000_000.0)
                fail("Decreasing odometer accepted")
            } catch (_: IllegalArgumentException) {}
            assertEquals(1, db.dao().fuelRecords().size)
            assertEquals(3, db.dao().expenseRecords().size)
        } finally {
            db.close()
        }
    }
}
