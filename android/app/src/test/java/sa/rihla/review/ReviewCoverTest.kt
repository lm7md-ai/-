package sa.rihla.review

import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import sa.rihla.RihlaApp
import sa.rihla.audio.*
import sa.rihla.ui.FreshTestApplication

@Implements(MediaMetadataRetriever::class)
class EmbeddedCoverRetriever : ShadowMediaMetadataRetriever() {
    @Implementation
    protected fun getEmbeddedPicture(): ByteArray {
        val bitmap =
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE)
            }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, shadows = [EmbeddedCoverRetriever::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewCoverTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(): Pair<RihlaApp, AudioTrack> = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        val uri = Uri.parse("content://review.cover/fixture")
        val provider =
            ReviewDocumentProvider(
                File(app.cacheDir, "metadata-fixture").apply { writeText("synthetic") }
            )
        provider.attachInfo(app, ProviderInfo().apply { authority = "review.cover" })
        ShadowContentResolver.registerProviderInternal("review.cover", provider)
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(app, uri),
            MediaMetadataRetriever.METADATA_KEY_DURATION,
            "120000",
        )
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(app, uri),
            MediaMetadataRetriever.METADATA_KEY_TITLE,
            "غلاف تجريبي",
        )
        assertEquals(1, app.audio.importFiles(listOf(uri)).added)
        app to app.audio.dao.tracks().first().single()
    }

    @Test
    fun extractedCoverLivesInPersistentPrivateStorage() {
        val (app, track) = fixture()
        assertTrue(File(track.artwork).isFile)
        assertTrue(track.artwork.startsWith(app.noBackupFilesDir.path + File.separator))
    }

    @Test
    fun missingCoverIsRegeneratedOnDisplayWithoutChangingTheAudioOrListeningState() {
        val (app, track) = fixture()
        runBlocking {
            app.audio.dao.favorite(track.id)
            app.audio.dao.progress(track.id, 15000, 1000)
        }
        assertTrue(File(track.artwork).delete())
        compose.setContent { FreshTestApplication { AudioCover(track, 64) } }
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithContentDescription("غلاف المقطع")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val saved = runBlocking { app.audio.dao.track(track.id)!! }
        assertTrue(File(saved.artwork).isFile)
        assertEquals(track.uri, saved.uri)
        assertEquals(15000L, saved.positionMs)
        assertTrue(saved.favorite)
    }

    @Test
    fun existingCachedCoverIsCopiedLazilyWithoutMovingTheOriginalAudio() = runBlocking {
        val (app, track) = fixture()
        val legacy = File(app.cacheDir, "audio_art/legacy.jpg").apply { parentFile!!.mkdirs() }
        File(track.artwork).copyTo(legacy, overwrite = true)
        File(track.artwork).delete()
        app.audio.dao.artwork(track.id, legacy.path)
        val copy = app.audio.ensureArtwork(track.copy(artwork = legacy.path))!!
        assertTrue(copy.path.startsWith(app.noBackupFilesDir.path + File.separator))
        assertArrayEquals(legacy.readBytes(), copy.readBytes())
        assertTrue(legacy.isFile)
        assertEquals(track.uri, app.audio.dao.track(track.id)!!.uri)
    }

    @Test
    fun unavailableOriginalKeepsTheTrackAndShowsNoCoverWithoutThrowing() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        val track =
            AudioTrack(
                "unavailable-cover",
                "content://review.unavailable/fixture",
                "مقطع تجريبي",
                durationMs = 120000,
                artwork = File(app.cacheDir, "missing.jpg").path,
            )
        app.audio.add(track)
        assertNull(app.audio.ensureArtwork(track))
        assertEquals(track, app.audio.dao.track(track.id))
    }
}
