package sa.rihla.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sa.rihla.RihlaApp
import sa.rihla.core.*
import sa.rihla.data.*
import sa.rihla.map.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StopRouteUiTest {
    @get:Rule val compose = createComposeRule()
    private val time = 1792652400000L

    private fun points(count: Int) =
        (0 until count).map { i ->
            RoutePoint(
                outingId = 1,
                latitude = 21.0,
                longitude = 39.0 + i * .001,
                accuracy = 5.0,
                speed = 10.0,
                altitude = 0.0,
                bearing = 90.0,
                recordedAt = time + i * 10000,
                distanceDelta = 100.0,
            )
        }

    @Test
    fun stopOffersLiveRecordedRangeAndSeparatelyLabelledCurrentDirectDistance() {
        val stop = StopDetails(1, time + 10000, time + 30000, 21.0, 39.001)
        var analysis by mutableStateOf(RouteAnalysis(points(8), listOf(stop)))
        var highlighted: IntRange? = null
        var closed = false
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = journeyColors(false),
                    typography = NewTypography,
                    shapes = NewShapes,
                ) {
                    StopRouteDialog(
                        stop,
                        listOf(stop),
                        analysis,
                        Outing(1, time),
                        time + 90000,
                        onHighlight = { highlighted = it },
                        onDismiss = { closed = true },
                        locate = { Fix(21.0, 39.01, time + 90000, 6.0) },
                    )
                }
            }
        }
        compose.onNodeWithText("من هذا التوقف حتى آخر موقع مسجّل").performScrollTo().performClick()
        compose.onNodeWithText("المسافة المسجّلة").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(num(0.4, 3) + " كم").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { analysis = RouteAnalysis(points(9), listOf(stop)) }
        compose.onNodeWithText(num(0.5, 3) + " كم").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("المسافة إلى موقعي الآن").performScrollTo().performClick()
        compose
            .onNodeWithText("المسافة المباشرة إلى موقعك:", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText("خط مستقيم فقط", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("إظهار الجزء كاملًا على الخريطة").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3..8, highlighted) }
        compose.onNodeWithText("إغلاق تفاصيل التوقف").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun ongoingStopDisablesDepartureAndLocationFailureIsVisibleWithoutLosingStopDetails() {
        val stop = StopDetails(1, time + 10000, null, 21.0, 39.001)
        compose.setContent {
            MaterialTheme {
                StopRouteDialog(
                    stop,
                    listOf(stop),
                    RouteAnalysis(points(8), listOf(stop)),
                    Outing(1, time),
                    time + 90000,
                    {},
                    {},
                    locate = { null },
                )
            }
        }
        compose.onNodeWithText("الجزء التالي لهذا التوقف").assertIsNotEnabled()
        compose.onNodeWithText("من هذا التوقف حتى آخر موقع مسجّل").assertIsNotEnabled()
        compose.onNodeWithText("المسافة إلى موقعي الآن").performScrollTo().performClick()
        compose
            .onNodeWithText("تعذر تحديد موقع حديث.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText("لم تُسجّل مغادرة هذا التوقف بعد.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
