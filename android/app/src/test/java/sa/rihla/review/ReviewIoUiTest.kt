package sa.rihla.review

import android.app.Activity
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import sa.rihla.R
import sa.rihla.RihlaApp
import sa.rihla.data.*
import sa.rihla.ui.*

class ReviewDocumentProvider(private val file: File) : ContentProvider() {
    var openedOnMain = false

    override fun onCreate() = true

    override fun query(
        u: Uri,
        p: Array<out String>?,
        s: String?,
        a: Array<out String>?,
        sort: String?,
    ): Cursor? = null

    override fun getType(u: Uri) = "application/json"

    override fun insert(u: Uri, v: ContentValues?): Uri? = null

    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0

    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    override fun openFile(u: Uri, mode: String): ParcelFileDescriptor {
        openedOnMain = Looper.myLooper() == Looper.getMainLooper()
        return ParcelFileDescriptor.open(
            file,
            if (mode.contains('w'))
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_WRITE_ONLY
            else ParcelFileDescriptor.MODE_READ_ONLY,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewIoUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<JourneyViewModel>()

    private fun model(app: RihlaApp) = JourneyViewModel(app).also { models += it }

    @After
    fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
    }

    private class Registry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry
            get() = this

        var request: Int? = null

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            request = requestCode
        }

        fun deliver(uri: Uri) {
            dispatchResult(request!!, Activity.RESULT_OK, Intent().setData(uri))
        }
    }

    private fun app() = RuntimeEnvironment.getApplication() as RihlaApp

    private fun document(file: File): Pair<Uri, ReviewDocumentProvider> {
        val provider = ReviewDocumentProvider(file)
        provider.attachInfo(app(), ProviderInfo().apply { authority = "review.document" })
        ShadowContentResolver.registerProviderInternal("review.document", provider)
        return Uri.parse("content://review.document/fixture") to provider
    }

    private fun notice(vm: JourneyViewModel): String? = runCatching {
        (vm.javaClass.getMethod("getNotice").invoke(vm) as kotlinx.coroutines.flow.StateFlow<*>)
            .value as? String
    }
        .getOrNull()

    private fun restore(complete: Boolean) {
        val app = app()
        runBlocking {
            app.settings.clear()
            app.settings.onboard()
        }
        val source = Room.inMemoryDatabaseBuilder(app, JourneyDatabase::class.java).build()
        val text = runBlocking {
            source.dao().insert(Outing(startedAt = 1000000, endedAt = 1100000))
            if (complete) {
                source.dao().place(SavedPlace("HOME", 21.1, 39.1, 200.0))
                source.dao().place(SavedPlace("UNIVERSITY", 21.2, 39.2, 500.0))
            }
            LocalBackup(source).export()
        }
        source.close()
        val file = File(app.cacheDir, "restore-fixture.json").apply { writeText(text) }
        val (uri, _) = document(file)
        val registry = Registry()
        val vm = model(app)
        compose.setContent {
            FreshTestApplication {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                    DataTools(vm, ScreenData(loaded = true))
                }
            }
        }
        val label = app.getString(R.string.restore_backup)
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle { registry.deliver(uri) }
        compose.waitUntil(20000) {
            compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNode(hasText(label) and hasAnyAncestor(isDialog()) and hasClickAction())
            .performClick()
        compose.waitUntil(20000) { vm.error.value != null || notice(vm) != null }
        assertEquals(complete, runBlocking { app.settings.flow.first().onboarded })
        assertEquals(1, runBlocking { app.repository.dao.outings().size })
        assertEquals(if (complete) 2 else 0, runBlocking { app.repository.dao.places().size })
        if (!complete) {
            assertNull(vm.error.value)
            assertEquals("restore_places_required", notice(vm))
        }
    }

    @Test fun restoreWithoutPlacesKeepsSetupIncompleteAndPreservesImportedTrips() = restore(false)

    @Test fun restoreWithBothPlacesCompletesSetupAndPreservesImportedTrips() = restore(true)

    @Test
    fun successfulExportUsesTheNoticeChannelWithoutAnError() {
        val app = app()
        val vm = model(app)
        val registry = Registry()
        val file = File(app.cacheDir, "export-fixture.json")
        val (uri, _) = document(file)
        compose.setContent {
            FreshTestApplication {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                    DataTools(vm, ScreenData(loaded = true))
                }
            }
        }
        compose.onNodeWithText(app.getString(R.string.export_backup)).performClick()
        compose.runOnIdle { registry.deliver(uri) }
        compose.waitUntil(20000) { vm.error.value != null || notice(vm) != null }
        assertTrue(file.readText().contains("RIHLA_BACKUP"))
        assertNull(vm.error.value)
        assertEquals("file_saved", notice(vm))
    }

    @Test
    fun openingTransferXmlAndParsingItHappenOffMain() {
        val app = app()
        val registry = Registry()
        app.transfers.configure(setOf("مرسل تجريبي"), 1580703000000, true)
        val file = File(app.cacheDir, "sms-fixture.xml").apply { writeText("<smses/>") }
        val (uri, provider) = document(file)
        compose.setContent {
            FreshTestApplication {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                    FatherTransfersScreen()
                }
            }
        }
        compose.onNodeWithText("استيراد نسخة رسائل XML").performScrollTo().performClick()
        compose.runOnIdle { registry.deliver(uri) }
        compose.waitUntil(20000) {
            compose
                .onAllNodesWithText(
                    "فُحصت 0 رسالة واردة وأضيفت 0 حوالة. راجع غير المؤكد والمكرر المحتمل."
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertFalse(provider.openedOnMain)
    }
}
