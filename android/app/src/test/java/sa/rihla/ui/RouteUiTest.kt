package sa.rihla.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
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
import sa.rihla.core.EngineState
import sa.rihla.data.*
import sa.rihla.map.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun routeToolsPersistDisplayChoicesAndOriginalPresetLeavesAllEnhancementsOff() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("route_display", Context.MODE_PRIVATE).edit().clear().commit()
        val state = RouteStyleState(context)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = journeyColors(false),
                    typography = NewTypography,
                    shapes = NewShapes,
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        RouteStyleControls(
                            state.value,
                            RouteDisplayStats(1000, 120, 40, 55, "مطابقة تقديرية محلية"),
                            state::set,
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("أدوات خط المسار").performClick()
        compose.onNodeWithText("دقة الموقع").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(RouteColorMode.ACCURACY, RouteStyleState(context).value.color)
        }
        compose.onNodeWithText("فحص النقاط").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(state.value.inspect)
            assertFalse(RouteStyleState(context).value.inspect)
        }
        compose.onNodeWithText("مطابقة تقديرية مع الطرق").performScrollTo()
        File("build/outputs/ui-previews/route-tools-6.1.png")
            .apply { parentFile!!.mkdirs() }
            .outputStream()
            .use {
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        compose.onNodeWithText("عرض GPS الأصلي").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                RouteDisplayOptions(confidence = false, cleanStops = false, simplify = false),
                state.value,
            )
        }
    }

    @Test
    fun repeatedRoutesAreDiscoverableAndOpenTheRecordedTrip() {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        runBlocking {
            app.settings.clear()
            app.settings.onboard()
            app.settings.theme("LIGHT")
            for (day in 1..3) {
                val start =
                    java.time.ZonedDateTime.of(2026, 10, day, 8, 0, 0, 0, sa.rihla.core.SaudiZone)
                        .toInstant()
                        .toEpochMilli()
                val id =
                    app.repository.dao.insert(
                        Outing(
                            startedAt = start,
                            endedAt = start + 600000,
                            state = EngineState(meters = 2100.0, stoppedMs = 60000),
                        )
                    )
                for (i in 0..20) app.repository.dao.point(
                    RoutePoint(
                        outingId = id,
                        latitude = 21.5,
                        longitude = 39.2 + i * .001,
                        accuracy = 5.0,
                        speed = 10.0,
                        altitude = 0.0,
                        bearing = 90.0,
                        recordedAt = start + i * 30000L,
                    )
                )
            }
        }
        compose.setContent { FreshTestApplication { RihlaRoot() } }
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("المزيد").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("المزيد").performClick()
        compose.onNodeWithText("المسارات المتكررة").performScrollTo().performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("عرض مشوار من هذا المسار").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("عرض مشوار من هذا المسار").performScrollTo().assertIsDisplayed()
        File("build/outputs/ui-previews/repeated-routes-6.1.png")
            .apply { parentFile!!.mkdirs() }
            .outputStream()
            .use {
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        compose.onNodeWithText("عرض مشوار من هذا المسار").performClick()
        compose.onNodeWithText("أدوات خط المسار").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("فحص النقاط").performScrollTo().assertIsDisplayed()
    }
}
