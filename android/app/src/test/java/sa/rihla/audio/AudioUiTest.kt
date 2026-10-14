package sa.rihla.audio

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows
import android.content.ComponentName
import android.content.Intent
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sa.rihla.RihlaApp
import sa.rihla.ui.RihlaRoot

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=RihlaApp::class,qualifiers="w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioUiTest {
    @get:Rule val compose=createComposeRule()
    @Test
    fun libraryNavigationSearchFavoritesAndPlaylistCreationWork() {
        val app=RuntimeEnvironment.getApplication() as RihlaApp
        val service = Robolectric.buildService(AudioPlaybackService::class.java).create()
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, AudioPlaybackService::class.java),
            service.get().onBind(Intent("androidx.media3.session.MediaSessionService")))
        try {
        runBlocking {
            app.settings.clear();app.settings.onboard()
            app.audio.dao.insert(AudioTrack("fixture","content://test/audio","مقطع اختبار","فنان","ألبوم",60000))
        }
        compose.setContent { RihlaRoot() }
        compose.waitUntil(20000){compose.onAllNodesWithContentDescription("الصوتيات").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("الصوتيات").performClick()
        compose.onNodeWithText("مكتبتك الصوتية").assertIsDisplayed()
        compose.onNodeWithText("إضافة ملفات").assertIsDisplayed()
        compose.onNodeWithText("ابحث بالعنوان أو الفنان أو الألبوم").performScrollTo().performTextInput("غير موجود")
        compose.onNodeWithText("لا توجد مقاطع مطابقة").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ابحث بالعنوان أو الفنان أو الألبوم").performScrollTo().performTextClearance()
        compose.onNodeWithText("مقطع اختبار").performScrollTo().assertIsDisplayed()
        java.io.File("build/outputs/ui-previews").apply { mkdirs() }.let { directory ->
            java.io.File(directory,"audio-4.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        }
        compose.onNodeWithContentDescription("تبديل المفضلة").performClick()
        compose.onNodeWithText("المفضلة").performScrollTo().performClick()
        compose.onNodeWithText("مقطع اختبار").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("قائمة جديدة +").performScrollTo().performClick()
        compose.onNodeWithText("اسم القائمة").performTextInput("المشوار")
        compose.onNodeWithText("إنشاء").performClick()
        compose.waitUntil(10000){runBlocking{app.audio.dao.playlists().first().isNotEmpty()}}
        } finally {
            service.destroy()
            AudioLive.state.value = AudioPlayback()
        }
    }
}
