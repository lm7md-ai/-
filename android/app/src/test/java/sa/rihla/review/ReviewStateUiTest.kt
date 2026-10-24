package sa.rihla.review

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sa.rihla.R
import sa.rihla.RihlaApp
import sa.rihla.data.*
import sa.rihla.ui.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewStateUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<JourneyViewModel>()

    @After
    fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
    }

    private fun start(onboarded: Boolean = true): Pair<RihlaApp, JourneyViewModel> {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        runBlocking { app.settings.onboard(onboarded) }
        val vm = JourneyViewModel(app).also { models += it }
        compose.setContent { FreshTestApplication { RihlaRoot(vm) } }
        compose.waitUntil(20000) {
            compose
                .onAllNodesWithText(
                    app.getString(if (onboarded) R.string.more else R.string.welcome)
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        return app to vm
    }

    @Test
    fun successAppearsInASnackbarRatherThanAnErrorDialog() {
        val (app, vm) = start()
        compose.runOnIdle { vm.notice.value = "file_saved" }
        compose.onNodeWithText(app.getString(R.string.file_saved)).assertIsDisplayed()
        assertNull(vm.error.value)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test
    fun restoreGuidanceIsVisibleEvenWhileOnboardingIsIncomplete() {
        val (app, vm) = start(false)
        compose.runOnIdle { vm.notice.value = "restore_places_required" }
        compose.onNodeWithText(app.getString(R.string.restore_places_required)).assertIsDisplayed()
        assertNull(vm.error.value)
    }

    @Test
    fun explicitClearShowsProgressAndCascadesEveryJourneyTableWithoutDeletingOtherFiles() {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        runBlocking {
            val id = app.repository.start(false, 1000000)
            val dao = app.repository.dao
            dao.point(
                RoutePoint(
                    outingId = id,
                    latitude = 21.1,
                    longitude = 39.1,
                    accuracy = 5.0,
                    speed = 0.0,
                    altitude = 0.0,
                    bearing = 0.0,
                    recordedAt = 1001000,
                )
            )
            dao.event(
                JourneyEvent(
                    outingId = id,
                    type = "STOP",
                    startedAt = 1001000,
                    endedAt = 1002000,
                    latitude = 21.1,
                    longitude = 39.1,
                    confirmed = true,
                )
            )
            dao.place(SavedPlace("HOME", 21.1, 39.1, 200.0))
            dao.details(TripDetails(id, "عنوان تجريبي"))
            app.repository.addExpense("FUEL", 27136, 1000, 200, true, 1003000, id)
            dao.claimSms(ImportedSms("synthetic-receipt", 1003000))
            dao.rule(MerchantRule(pattern = "متجر تجريبي", category = "OTHER"))
            dao.recurring(
                RecurringExpense(
                    title = "جدول تجريبي",
                    category = "OTHER",
                    amountHalala = 27136,
                    dayOfMonth = 7,
                )
            )
        }
        val db = app.database.openHelper.readableDatabase
        db.query("PRAGMA foreign_keys").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        for (table in LocalBackup.tables) db.query("SELECT COUNT(*) FROM `$table`").use {
            assertTrue(it.moveToFirst())
            assertTrue(table, it.getInt(0) > 0)
        }
        File(app.filesDir, "offline.mbtiles").writeText("synthetic cache")
        File(app.filesDir, "tiles/fixture").apply {
            parentFile!!.mkdirs()
            writeText("synthetic cache")
        }
        val original =
            File(app.filesDir, "original-fixture.wav").apply { writeText("synthetic original") }
        val (_, vm) = start()
        val lock =
            JourneyRepository::class
                .java
                .getDeclaredField("lock")
                .apply { isAccessible = true }
                .get(app.repository) as Mutex
        assertTrue(lock.tryLock())
        try {
            compose.runOnIdle { vm.clear() }
            compose.onNodeWithText(app.getString(R.string.deleting_data)).assertIsDisplayed()
            assertTrue(vm.clearing.value)
        } finally {
            lock.unlock()
        }
        compose.waitUntil(20000) {
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            !vm.clearing.value
        }
        assertNull(vm.error.value)
        for (table in LocalBackup.tables) db.query("SELECT COUNT(*) FROM `$table`").use {
            assertTrue(it.moveToFirst())
            assertEquals(table, 0, it.getInt(0))
        }
        assertFalse(File(app.filesDir, "offline.mbtiles").exists())
        assertFalse(File(app.filesDir, "tiles").exists())
        assertEquals("synthetic original", original.readText())
    }
}
