package sa.rihla.design

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sa.rihla.R
import sa.rihla.RihlaApp
import sa.rihla.audio.*
import sa.rihla.audio.studio.AssistantNavigation
import sa.rihla.data.*
import sa.rihla.ui.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, qualifiers = "ar-rSA-w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExperienceTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val testName = org.junit.rules.TestName()
    private lateinit var vm: JourneyViewModel
    private lateinit var service:
        org.robolectric.android.controller.ServiceController<AudioPlaybackService>
    private val app
        get() = RuntimeEnvironment.getApplication() as RihlaApp

    @Before
    fun prepare() {
        if (testName.methodName.contains("largeFont")) RuntimeEnvironment.setFontScale(2f)
        service =
            org.robolectric.Robolectric.buildService(AudioPlaybackService::class.java).create()
        org.robolectric.Shadows.shadowOf(app)
            .setComponentNameAndServiceForBindService(
                android.content.ComponentName(app, AudioPlaybackService::class.java),
                service
                    .get()
                    .onBind(android.content.Intent("androidx.media3.session.MediaSessionService")),
            )
        runBlocking {
            app.settings.onboard()
            app.settings.theme(if (testName.methodName.contains("dark", true)) "DARK" else "LIGHT")
            val now = System.currentTimeMillis()
            val id = app.repository.start(false, now - 7200000)
            app.repository.dao.details(TripDetails(id, "مشوار تجريبي", favorite = true))
            app.repository.end()
            app.repository.addExpense("FOOD", 27136, null, null, false, now, id)
            app.audio.add(
                AudioTrack(
                    "design-one",
                    "content://design.test/one",
                    "رحلة في عالم المعرفة",
                    "مصدر تجريبي",
                    "محاضرات تجريبية",
                    3600000,
                    true,
                    720000,
                    now,
                )
            )
            app.audio.add(
                AudioTrack(
                    "design-two",
                    "content://design.test/two",
                    "كيف نصنع عادة جديدة؟",
                    "مصدر تجريبي",
                    "محاضرات تجريبية",
                    1800000,
                )
            )
        }
        vm = JourneyViewModel(app)
        compose.setContent { FreshTestApplication { RihlaRoot(vm) } }
        compose.waitUntil(20000) {
            compose
                .onAllNodesWithText(app.getString(R.string.more))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @After
    fun cleanup() {
        vm.viewModelScope.cancel()
        service.destroy()
        AudioLive.state.value = AudioPlayback()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = System.getenv("RIHLA_DESIGN_CAPTURE_DIR") ?: return
        val file = File(directory, "$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun open(page: String) {
        compose.runOnIdle { AssistantNavigation.requests.tryEmit(page) }
        compose.waitForIdle()
    }

    private fun mapView(): sa.rihla.map.RouteMapView? {
        val activity =
            (compose as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>).activity
        fun find(view: android.view.View): sa.rihla.map.RouteMapView? {
            if (view is sa.rihla.map.RouteMapView) return view
            if (view is android.view.ViewGroup)
                for (i in 0 until view.childCount) find(view.getChildAt(i))?.let {
                    return it
                }
            return null
        }
        return find(activity.window.decorView)
    }

    private fun waitForMap() {
        compose.onRoot().captureToImage()
        compose.waitUntil(30000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            mapView()?.isCountryReady == true
        }
    }

    @Test
    fun mainJourneysRemainReachableAndRender() {
        capture("dashboard")
        compose.onNodeWithText(app.getString(R.string.history)).performClick()
        compose.onNodeWithText("مشوار تجريبي").assertExists()
        capture("history")
        compose.onNodeWithText("مشوار تجريبي").performClick()
        capture("detail")
        compose.onNodeWithText(app.getString(R.string.atlas_trip_times)).performClick()
        compose.onNodeWithText(app.getString(R.string.started)).performScrollTo().assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText(app.getString(R.string.atlas_trip_stops)).performClick()
        compose.onNodeWithText(app.getString(R.string.atlas_trip_no_stops)).assertExists()
        open("audio")
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("رحلة في عالم المعرفة").fetchSemanticsNodes().isNotEmpty()
        }
        capture("audio")
        compose.onNodeWithText("المفضلة").performClick()
        compose.onNodeWithText("كيف نصنع عادة جديدة؟").assertDoesNotExist()
        open("settings")
        capture("more")
        open("expenses")
        capture("wallet")
        open("map")
        waitForMap()
        capture("map")
        open("models")
        capture("models")
        open("audio")
        compose.runOnIdle {
            AudioLive.state.value =
                AudioPlayback(
                    id = "design-one",
                    title = "رحلة في عالم المعرفة",
                    position = 720000,
                    duration = 3600000,
                    mediaVolume = 5,
                    mediaMax = 10,
                )
        }
        open("player")
        capture("player")
        compose.onNodeWithText("رحلة في عالم المعرفة").assertExists()
    }

    @Test
    fun settingsGroupsSearchAndDirectModelAccessWork() {
        open("settings")
        compose.onNodeWithText(app.getString(R.string.atlas_models)).performClick()
        compose.onNodeWithText("مركز النماذج").assertExists()
        open("settings")
        compose.onNodeWithText(app.getString(R.string.atlas_finance)).performClick()
        compose.onNodeWithText("حوالات الوالد").performScrollTo().performClick()
        compose.onAllNodesWithText("حوالات الوالد").onFirst().assertExists()
        open("settings")
        compose
            .onNodeWithText(app.getString(R.string.atlas_search_tools))
            .performTextInput("سيارتي")
        compose.onNode(hasText("سيارتي") and !hasSetTextAction()).performClick()
        compose.onNodeWithText("المواصفات").assertExists()
    }

    @Test
    fun mapToolsStayAvailableAndRememberSelectionsBetweenSheetOpenings() {
        open("map")
        compose.onNodeWithText(app.getString(R.string.atlas_map_layers)).performClick()
        compose.onNodeWithText("قياس المسافات").performScrollTo().performClick()
        compose.onNodeWithText("قياس المسافات").assertIsSelected()
        compose
            .onNodeWithContentDescription(app.getString(R.string.atlas_close_options))
            .performClick()
        compose.onNodeWithText(app.getString(R.string.atlas_map_layers)).performClick()
        compose.onNodeWithText("قياس المسافات").performScrollTo().assertIsSelected()
        compose
            .onNodeWithContentDescription(app.getString(R.string.atlas_close_options))
            .performClick()
        compose.onNodeWithText(app.getString(R.string.atlas_map_trip)).performClick()
        compose.onNodeWithText("موقعي الحالي").performScrollTo().assertExists()
        compose.onNodeWithText("بدون مسار").performScrollTo().performClick()
        compose
            .onNodeWithContentDescription(app.getString(R.string.atlas_close_options))
            .performClick()
        compose.onNodeWithText(app.getString(R.string.atlas_map_country)).assertExists()
    }

    @Test
    fun darkThemeRendersDashboardAudioAndTools() {
        capture("dark-dashboard")
        open("audio")
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("رحلة في عالم المعرفة").fetchSemanticsNodes().isNotEmpty()
        }
        capture("dark-audio")
        open("settings")
        capture("dark-more")
        compose.onNodeWithText(app.getString(R.string.atlas_models)).assertIsDisplayed()
    }

    @Test
    fun largeFontKeepsPrimaryActionsAndToolsReachable() {
        compose
            .onNodeWithContentDescription(app.getString(R.string.start_journey))
            .assertIsDisplayed()
        capture("large-font-dashboard")
        open("settings")
        compose
            .onNodeWithText(app.getString(R.string.atlas_models))
            .performScrollTo()
            .assertIsDisplayed()
        capture("large-font-more")
        open("map")
        compose.onNodeWithText(app.getString(R.string.atlas_map_layers)).performClick()
        compose.onNodeWithText("قياس المسافات").performScrollTo().assertExists()
    }

    @Test
    @Config(qualifiers = "ar-rSA-w900dp-h1000dp-xhdpi")
    fun wideNavigationUsesARailAndKeepsLibraryReachable() {
        val home =
            compose
                .onNode(hasText(app.getString(R.string.dashboard)) and hasClickAction())
                .fetchSemanticsNode()
                .boundsInRoot
        val history =
            compose
                .onNodeWithText(app.getString(R.string.history))
                .fetchSemanticsNode()
                .boundsInRoot
        Assert.assertTrue("Wide layouts use vertical destination ordering", history.top > home.top)
        capture("wide-dashboard")
        compose.onNodeWithText(app.getString(R.string.audio_title)).performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("رحلة في عالم المعرفة").fetchSemanticsNodes().isNotEmpty()
        }
        capture("wide-audio")
    }

    @Test
    fun textAndActionColorsMeetNormalTextContrastInBothThemes() {
        for (dark in listOf(false, true)) {
            val colors = journeyColors(dark)
            val pairs =
                listOf(
                    colors.onSurface to colors.surface,
                    colors.onBackground to colors.background,
                    colors.onPrimary to colors.primary,
                    colors.onPrimaryContainer to colors.primaryContainer,
                    colors.onSecondaryContainer to colors.secondaryContainer,
                    colors.onSurfaceVariant to colors.background,
                )
            pairs.forEach { (foreground, background) ->
                Assert.assertTrue(
                    "Text contrast must be at least 4.5:1, dark=$dark",
                    androidx.core.graphics.ColorUtils.calculateContrast(
                        foreground.toArgb(),
                        background.toArgb(),
                    ) >= 4.5,
                )
            }
        }
    }

    @Test
    fun selectingARoutePointShowsItsInspectorBeforeGeneralMapTools() {
        val id = vm.state.value.outings.first().id
        val now = vm.state.value.outings.first().startedAt
        val points =
            (0..1).map { index ->
                RoutePoint(
                    outingId = id,
                    latitude = 21.5,
                    longitude = 39.2 + index * .003,
                    accuracy = 5.0,
                    speed = 10.0,
                    altitude = 12.0,
                    bearing = 90.0,
                    recordedAt = now + index * 60000L,
                    distanceDelta = 300.0,
                )
            }
        runBlocking { points.forEach { app.repository.dao.point(it) } }
        open("map")
        compose.waitUntil(20000) { mapView()?.points?.size == 2 }
        compose.runOnIdle {
            mapView()!!
                .onPointPick!!
                .invoke(
                    sa.rihla.map.RoutePointSelection(
                        points[1],
                        1,
                        sa.rihla.map.DisplayPoint(1, points[1].latitude, points[1].longitude),
                        1,
                    )
                )
        }
        compose.onNodeWithText("الجزء بين التوقفات").assertIsDisplayed()
        compose
            .onNodeWithContentDescription(app.getString(R.string.atlas_close_options))
            .performClick()
        Assert.assertEquals(2, mapView()!!.points.size)
    }
}
