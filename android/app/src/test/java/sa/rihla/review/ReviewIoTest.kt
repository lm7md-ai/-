package sa.rihla.review

import android.Manifest
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import sa.rihla.RihlaApp
import sa.rihla.sms.TransferArchive
import sa.rihla.ui.deleteMapCaches

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class)
class ReviewIoTest {
    @Test
    fun archiveQueriesUseIoAndProgressIsDeliveredOnMain() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        var queriedOnMain = true
        var progressOnMain = false
        val provider =
            object : ContentProvider() {
                override fun onCreate() = true

                override fun query(
                    u: Uri,
                    p: Array<out String>?,
                    s: String?,
                    a: Array<out String>?,
                    sort: String?,
                ): Cursor {
                    queriedOnMain = Looper.myLooper() == Looper.getMainLooper()
                    return MatrixCursor(p ?: arrayOf("_id", "body", "date", "date_sent"))
                }

                override fun getType(u: Uri): String? = null

                override fun insert(u: Uri, v: ContentValues?): Uri? = null

                override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0

                override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) =
                    0
            }
        provider.attachInfo(app, ProviderInfo().apply { authority = "sms" })
        ShadowContentResolver.registerProviderInternal("sms", provider)
        val task =
            async(Dispatchers.Default) {
                TransferArchive.scan(app, app.transfers) { _, _ ->
                    progressOnMain = Looper.myLooper() == Looper.getMainLooper()
                }
            }
        withTimeout(10000) {
            while (!task.isCompleted) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        assertEquals(0 to 0, task.await())
        assertFalse(queriedOnMain)
        assertTrue(progressOnMain)
    }

    @Test
    fun explicitMapCacheDeletionRunsOffMainAndDeletesOnlyThoseCaches() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.cacheDir, "review-map-cache").apply { mkdirs() }
        File(root, "offline.mbtiles").writeText("synthetic")
        File(root, "tiles/one/two").apply {
            parentFile!!.mkdirs()
            writeText("synthetic")
        }
        val keep = File(root, "keep").apply { writeText("retain") }
        var onMain = false
        var operations = 0
        deleteMapCaches(root) { file ->
            onMain = onMain || Looper.myLooper() == Looper.getMainLooper()
            operations++
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
        assertEquals(2, operations)
        assertFalse(onMain)
        assertFalse(File(root, "offline.mbtiles").exists())
        assertFalse(File(root, "tiles").exists())
        assertTrue(keep.exists())
    }
}
