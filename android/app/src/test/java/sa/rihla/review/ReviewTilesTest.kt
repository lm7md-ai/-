package sa.rihla.review

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sa.rihla.map.HybridTiles
import sa.rihla.map.RouteMapView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReviewTilesTest {
    private fun dispose(value: Any) {
        if (value is AutoCloseable) value.close()
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun database(file: File, bytes: ByteArray) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE tiles (zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB)"
            )
            for (x in 0 until 50) db.insertOrThrow(
                "tiles",
                null,
                ContentValues().apply {
                    put("zoom_level", 7)
                    put("tile_column", x)
                    put("tile_row", 127)
                    put("tile_data", bytes)
                },
            )
        }
    }

    @Test
    fun fiftyTilesReuseOneOpenConnectionAndCloseItWhenDisposed() {
        val app = RuntimeEnvironment.getApplication()
        val bytes = png(android.graphics.Color.GREEN)
        database(File(app.filesDir, "offline.mbtiles"), bytes)
        val opened = mutableListOf<SQLiteDatabase>()
        val provider =
            HybridTiles(
                app,
                { file ->
                    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                        .also { opened += it }
                },
            )
        try {
            for (x in 0 until 50) assertArrayEquals(bytes, provider.load(7, x, 0))
            assertEquals(1, opened.size)
            assertTrue(opened.single().isOpen)
        } finally {
            dispose(provider)
        }
        assertFalse(opened.single().isOpen)
    }

    @Test
    fun missingTilesAreQueriedOnceUntilTheOfflineSourceChanges() {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.filesDir, "offline.mbtiles")
        val first = png(android.graphics.Color.GREEN)
        val second = png(android.graphics.Color.BLUE)
        database(file, first)
        var opens = 0
        var queries = 0
        val provider =
            HybridTiles(
                app,
                { source ->
                    opens++
                    SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY)
                },
                { queries++ },
            )
        try {
            repeat(50) { assertNull(provider.load(7, 90, 1)) }
            assertEquals(1, opens)
            assertEquals(1, queries)
            assertArrayEquals(first, provider.load(7, 0, 0))
            val replacement = File(app.filesDir, "replacement.db")
            database(replacement, second)
            replacement.setLastModified(file.lastModified() + 2000)
            assertTrue(replacement.renameTo(file))
            assertArrayEquals(second, provider.load(7, 0, 0))
            assertEquals(2, opens)
        } finally {
            dispose(provider)
        }
    }

    @Test
    fun twoBlockedAnalysisWorkersCannotStarveTileLoading() = runBlocking {
        val view = RouteMapView(RuntimeEnvironment.getApplication())
        val analysis =
            RouteMapView::class
                .java
                .getDeclaredField("scope")
                .apply { isAccessible = true }
                .get(view) as CoroutineScope
        val tileScope = runCatching {
            RouteMapView::class
                .java
                .getDeclaredField("tileScope")
                .apply { isAccessible = true }
                .get(view) as CoroutineScope
        }.getOrDefault(analysis)
        val occupied = CountDownLatch(2)
        val release = CountDownLatch(1)
        try {
            repeat(2) {
                analysis.launch {
                    occupied.countDown()
                    release.await()
                }
            }
            assertTrue(occupied.await(5, TimeUnit.SECONDS))
            val loaded = CompletableDeferred<Unit>()
            tileScope.launch { loaded.complete(Unit) }
            withTimeout(1000) { loaded.await() }
        } finally {
            release.countDown()
            RouteMapView::class
                .java
                .getDeclaredMethod("onDetachedFromWindow")
                .apply { isAccessible = true }
                .invoke(view)
        }
    }

    @Test
    fun ordinaryAssetsArePackagedAlongsideTheCountryPack() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(
            app.assets
                .open("maps/NOTICE.txt")
                .bufferedReader()
                .use { it.readText() }
                .contains("OpenStreetMap")
        )
        assertTrue(app.assets.open("maps/saudi-roads.pack").use { it.read() } >= 0)
        assertThrows(java.io.FileNotFoundException::class.java) {
            app.assets.open("maps/jeddah-makkah.mbtiles")
        }
        assertThrows(java.io.FileNotFoundException::class.java) {
            app.assets.open("maps/saudi-roads.db")
        }
    }
}
