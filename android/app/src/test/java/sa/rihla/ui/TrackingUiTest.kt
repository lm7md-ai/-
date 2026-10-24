package sa.rihla.ui

import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sa.rihla.RihlaApp
import sa.rihla.data.SavedPlace

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrackingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun diagnosticEntryIsVisibleAndSharingCannotIncludeSavedCoordinates() {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        runBlocking {
            app.settings.clear()
            app.settings.onboard()
            app.settings.theme("LIGHT")
            app.repository.dao.place(SavedPlace("HOME", 21.54321, 39.23456, 200.0))
        }
        compose.setContent { FreshTestApplication { RihlaRoot() } }
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("المزيد").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("المزيد").performClick()
        compose.onNodeWithText("الطريق والأماكن").performClick()
        compose.onNodeWithText("مركز التتبع الذكي").performScrollTo().performClick()
        compose.onNodeWithText("جاهزية التتبع").assertIsDisplayed()
        compose.onNodeWithText("الموقع الدقيق").assertIsDisplayed()
        File("build/outputs/ui-previews/tracking-6-light.png")
            .apply { parentFile!!.mkdirs() }
            .outputStream()
            .use {
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        compose.onNodeWithText("اختبار إشارة الموقع").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("إعادة تهيئة التتبع والاستئناف").performScrollTo().performClick()
        compose
            .onNodeWithText("أُرسل طلب إعادة تسجيل محفزات التلقائي وفق إعداداتك؛ لم يُنشأ مشوار.")
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(runBlocking { app.repository.dao.outings().isEmpty() })
        compose.onNodeWithText("مشاركة تقرير تشخيص دون إحداثيات").performScrollTo().performClick()
        val chooser = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        val report = send.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(report.contains("Fine="))
        assertFalse(report.contains("21.54321"))
        assertFalse(report.contains("39.23456"))
    }
}
