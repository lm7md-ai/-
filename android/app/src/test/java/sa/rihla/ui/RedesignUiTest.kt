package sa.rihla.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sa.rihla.RihlaApp
import sa.rihla.data.*
import sa.rihla.feature.dashboard.Dashboard
import sa.rihla.feature.history.History

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedesignUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun mapNavigationExposesZoomLayersMeasurementAndOfflineSearch() {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        runBlocking {
            app.settings.clear()
            app.settings.onboard()
        }
        compose.setContent { sa.rihla.ui.FreshTestApplication { RihlaRoot() } }
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("رحلتك تبدأ هنا").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("الخريطة").performClick()
        compose.onNodeWithText("خريطة السعودية دون إنترنت").assertIsDisplayed()
        compose.onNodeWithText("تكبير +").performClick()
        compose.onNodeWithText("تصغير −").performClick()
        compose.onNodeWithText("قياس المسافات").performScrollTo().performClick()
        compose.onNodeWithText("قياس المسافات").assertIsSelected()
        compose.onNodeWithText("السعودية كاملة").performScrollTo().performClick()
        compose.onNodeWithText("ابحث عن مدينة أو حي بالعربية").performScrollTo()
        val activity =
            (compose as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>).activity
        fun map(view: android.view.View): sa.rihla.map.RouteMapView? {
            if (view is sa.rihla.map.RouteMapView) return view
            if (view is android.view.ViewGroup)
                for (index in 0 until view.childCount) map(view.getChildAt(index))?.let {
                    return it
                }
            return null
        }
        // Native AndroidView drawing is explicit in Robolectric; start the viewport request,
        // then deliver its main-looper completion before capturing the loaded map.
        compose.onRoot().captureToImage()
        compose.waitUntil(30000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            map(activity.window.decorView)?.isCountryReady == true
        }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File("build/outputs/ui-previews").apply { mkdirs() }
        File(directory, "map-3.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun dashboardHasWorkingExpenseActionAndRendersArabicLightTheme() {
        val vm = JourneyViewModel(RuntimeEnvironment.getApplication())
        var clicked = false
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = journeyColors(false),
                    typography = NewTypography,
                    shapes = NewShapes,
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Dashboard(vm, ScreenData(loaded = true), {}, {}, { clicked = true })
                    }
                }
            }
        }
        compose.onNodeWithText("رحلتك تبدأ هنا").assertIsDisplayed()
        compose.onNodeWithText("إضافة مصروف").performClick()
        assertTrue(clicked)
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File("build/outputs/ui-previews").apply { mkdirs() }
        File(directory, "dashboard-light.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun fullAppNavigationAndDarkThemeRenderWithBackupControls() {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        runBlocking {
            app.settings.clear()
            app.settings.onboard()
            app.settings.theme("LIGHT")
        }
        compose.setContent { sa.rihla.ui.FreshTestApplication { RihlaRoot() } }
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("رحلتك تبدأ هنا").fetchSemanticsNodes().isNotEmpty()
        }
        fun capture(name: String) {
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val directory = File("build/outputs/ui-previews").apply { mkdirs() }
            File(directory, name).outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithText("رحلتك تبدأ هنا").assertIsDisplayed()
        capture("app-light.png")
        runBlocking { app.settings.theme("DARK") }
        compose.waitForIdle()
        capture("app-dark.png")
        compose.onNodeWithText("المزيد").performClick()
        compose.onNodeWithText("نسخ بياناتك محليًا").performScrollTo().assertIsDisplayed()
        capture("more-dark.png")
        compose.onNodeWithText("نسخ بياناتك محليًا").performClick()
        compose.onNodeWithText("حفظ نسخة محلية").performScrollTo().assertExists()
    }

    @Test
    fun expenseDraftSurvivesSavedStateRestoration() {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        runBlocking {
            app.settings.clear()
            app.settings.onboard()
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { sa.rihla.ui.FreshTestApplication { RihlaRoot() } }
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("رحلتك تبدأ هنا").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("إضافة مصروف").performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("12.34")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("12.34").assertIsDisplayed()
    }

    @Test
    fun journeySearchAndFavoritesChangeVisibleCards() {
        val data =
            ScreenData(
                loaded = true,
                outings =
                    listOf(
                        Outing(id = 1, startedAt = 1000, endedAt = 2000),
                        Outing(id = 2, startedAt = 3000, endedAt = 4000),
                    ),
                details =
                    listOf(TripDetails(1, "الجامعة", favorite = true), TripDetails(2, "زيارة")),
            )
        compose.setContent {
            MaterialTheme(
                colorScheme = journeyColors(true),
                typography = NewTypography,
                shapes = NewShapes,
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface { History(data) {} }
                }
            }
        }
        compose.onNodeWithText("المفضلة").performClick()
        compose.onNodeWithText("الجامعة").assertIsDisplayed()
        compose.onNodeWithText("زيارة").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("زيارة")
        compose.onNodeWithText("لا توجد نتائج").assertIsDisplayed()
    }
}
