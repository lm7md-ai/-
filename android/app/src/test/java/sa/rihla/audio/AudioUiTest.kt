package sa.rihla.audio

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sa.rihla.RihlaApp
import sa.rihla.ui.RihlaRoot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun libraryNavigationSearchFavoritesAndPlaylistCreationWork() {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        val service = Robolectric.buildService(AudioPlaybackService::class.java).create()
        Shadows.shadowOf(app)
            .setComponentNameAndServiceForBindService(
                ComponentName(app, AudioPlaybackService::class.java),
                service.get().onBind(Intent("androidx.media3.session.MediaSessionService")),
            )
        try {
            runBlocking {
                app.settings.clear()
                app.settings.onboard()
                app.audio.dao.insert(
                    AudioTrack(
                        "fixture",
                        "content://test/audio",
                        "مقطع اختبار",
                        "فنان",
                        "ألبوم",
                        60000,
                    )
                )
            }
            compose.setContent { sa.rihla.ui.FreshTestApplication { RihlaRoot() } }
            compose.waitUntil(20000) {
                compose.onAllNodesWithText("الصوتيات").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("الصوتيات").performClick()
            compose.onNodeWithText("مساحتك للاستماع").assertIsDisplayed()
            compose.onNodeWithText("أوضاع الصوت الجاهزة").assertDoesNotExist()
            compose.onNodeWithContentDescription("إضافة صوتيات").performClick()
            compose.onNodeWithText("إضافة ملفات").assertIsDisplayed()
            compose.onNodeWithText("إضافة مجلد").assertIsDisplayed()
            compose.onNodeWithText("فحص الهاتف").assertIsDisplayed()
            compose.onNodeWithContentDescription("إغلاق إضافة الصوتيات").performClick()
            compose
                .onNodeWithText("ابحث بالعنوان أو الفنان أو الألبوم")
                .performScrollTo()
                .performTextInput("غير موجود")
            compose.onNodeWithText("لا توجد مقاطع مطابقة").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("مسح البحث").performScrollTo().performClick()
            compose.onNodeWithText("مقطع اختبار").performScrollTo().assertIsDisplayed()
            java.io
                .File("build/outputs/ui-previews")
                .apply { mkdirs() }
                .let { directory ->
                    java.io.File(directory, "audio-4.png").outputStream().use {
                        compose
                            .onRoot()
                            .captureToImage()
                            .asAndroidBitmap()
                            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            compose.onNodeWithContentDescription("خيارات المقطع").performClick()
            compose.onNodeWithText("إضافة للمفضلة").performClick()
            compose.onNodeWithText("المفضلة").performScrollTo().performClick()
            compose.onNodeWithText("مقطع اختبار").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("إدارة المكتبة").performScrollTo().performClick()
            compose.onNodeWithText("قائمة جديدة +").performClick()
            compose.onNodeWithText("اسم القائمة").performTextInput("المشوار")
            compose.onNodeWithText("إنشاء").performClick()
            compose.waitUntil(10000) {
                runBlocking { app.audio.dao.playlists().first().isNotEmpty() }
            }
            compose.onNodeWithContentDescription("إدارة المكتبة").performScrollTo().performClick()
            compose.onNodeWithText("استوديو المحاضرات والمساعد المحلي").performClick()
            compose.onNodeWithText("استوديو المحاضرات").assertIsDisplayed()
            java.io.File("build/outputs/ui-previews/audio-studio-5.png").outputStream().use {
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithText("محاضرة · تخفيف الحدة").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("تنظيم").performScrollTo().performClick()
            compose.onNodeWithText("خطة أولية دون نموذج").performScrollTo().performClick()
            compose.onNodeWithText("تطبيق التصنيف (1)").performScrollTo().performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("حُفظ التصنيف داخل المكتبة").fetchSemanticsNodes()
                runBlocking { app.audio.dao.filings().first().size == 1 }
            }
            compose.onNodeWithText("نقل الملفات في الهاتف").performScrollTo().assertExists()
            compose.onNodeWithText("المجلدات").performScrollTo().performClick()
            compose.onNodeWithText("غير مصنف / فنان").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("النماذج").performScrollTo().performClick()
            compose
                .onNodeWithText("Qwen · مساعد الأسئلة والتصنيف")
                .performScrollTo()
                .assertIsDisplayed()
        } finally {
            service.destroy()
            AudioLive.state.value = AudioPlayback()
        }
    }
}
