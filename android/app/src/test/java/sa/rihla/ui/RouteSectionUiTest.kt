package sa.rihla.ui

import android.content.Intent
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
import androidx.compose.ui.unit.dp
import java.io.File
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
import sa.rihla.core.StopDetails
import sa.rihla.data.*
import sa.rihla.map.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteSectionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun segmentDetailsCustomRangeComparisonSharingAndOriginalFieldsWorkTogether() {
        val raw =
            (0..5).map { i ->
                RoutePoint(
                    outingId = 1,
                    latitude = 21.5,
                    longitude = 39.2 + i * .003,
                    accuracy = 5.0 + i,
                    speed = 10.0,
                    altitude = 12.0,
                    bearing = 90.0,
                    recordedAt = 1792652400000 + i * 60000L,
                    distanceDelta = 300.0,
                )
            }
        val analysis =
            RouteAnalysis(
                raw,
                listOf(StopDetails(1, raw[1].recordedAt, raw[1].recordedAt + 30000, 21.5, 39.203)),
            )
        val trip =
            Outing(
                1,
                raw.first().recordedAt,
                raw.last().recordedAt,
                consumptionMinMl = 8000,
                consumptionMaxMl = 8000,
                fuelPriceHalala = 218,
            )
        val state = SectionSelectionState()
        var selection by
            mutableStateOf(
                RoutePointSelection(
                    raw[1],
                    1,
                    DisplayPoint(1, raw[1].latitude, raw[1].longitude),
                    1,
                )
            )
        var closed = false
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = journeyColors(false),
                    typography = NewTypography,
                    shapes = NewShapes,
                ) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RouteSectionInspector(analysis, selection, trip, state, MapCamera()) {
                            closed = true
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("تفاصيل المقطع بين النقطتين").assertIsDisplayed()
        File("build/outputs/ui-previews/route-section-6.2.png")
            .apply { parentFile!!.mkdirs() }
            .outputStream()
            .use {
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        compose.onNodeWithText("حفظ الجزء للمقارنة").performScrollTo().performClick()
        compose.onNodeWithText("اجعل النقطة بداية الجزء").performScrollTo().performClick()
        compose.runOnIdle {
            selection =
                RoutePointSelection(
                    raw[4],
                    4,
                    DisplayPoint(4, raw[4].latitude, raw[4].longitude),
                    4,
                )
        }
        compose.onNodeWithText("تثبيت نهاية الجزء").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1..4, state.range(selection))
            assertEquals(0..1, state.comparison!!.range)
        }
        compose
            .onNodeWithText("مقارنة مع الجزء المحفوظ (1–2)")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("مشاركة ملخص دون إحداثيات").performScrollTo().performClick()
        val chooser = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val message =
            chooser
                .getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
                .getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(message.contains("00:03:00"))
        assertFalse(message.contains("21.5"))
        assertFalse(message.contains("39.2"))
        compose
            .onNodeWithText("بيانات النقطة الأصلية والارتفاع والاتجاه")
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("النقطة المسجلة 5 من 6").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("إلغاء تحديد الجزء").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3..4, state.range(selection)) }
        compose.onNodeWithContentDescription("إغلاق تفاصيل المقطع").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(closed) }
    }
}
