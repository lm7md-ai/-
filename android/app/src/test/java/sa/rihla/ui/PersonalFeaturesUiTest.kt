package sa.rihla.ui

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import sa.rihla.RihlaApp
import sa.rihla.core.*
import sa.rihla.data.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonalFeaturesUiTest {
    @get:Rule val compose = createComposeRule()

    private fun app(): RihlaApp {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        runBlocking {
            app.settings.clear()
            app.settings.onboard()
            app.settings.theme("LIGHT")
        }
        return app
    }

    private fun open(label: String) {
        compose.setContent { FreshTestApplication { RihlaRoot() } }
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("المزيد").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("المزيد").performClick()
        compose.onNodeWithText(label).performScrollTo().performClick()
    }

    private fun capture(name: String) {
        File("build/outputs/ui-previews/$name")
            .apply { parentFile!!.mkdirs() }
            .outputStream()
            .use {
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
    }

    @Test
    fun fatherTransferScreenActivatesOnceAndShowsOldNewLastAndReviewWithoutTouchingSpending() {
        val app = app()
        open("حوالات الوالد")
        compose
            .onNodeWithText("اسم الوالد كما يظهر بعد الفاصلة المنقوطة")
            .performScrollTo()
            .performTextInput("سالم عبدالله مثال")
        compose.onNodeWithText("تفعيل الميزة وتثبيت وقت الفصل").performScrollTo().performClick()
        compose.waitUntil(20000) { app.transfers.cutoff > 0 }
        val cutoff = app.transfers.cutoff
        val current =
            java.time.LocalDateTime.now(SaudiZone)
                .format(java.time.format.DateTimeFormatter.ofPattern("uu/MM/dd HH:mm"))
        app.transfers.import(
            "حوالة داخلية واردة بـSR 130\nلـ1111\nمن2222; سالم عبدالله مثال\n20/10/22 10:18",
            1603351080000,
            "inbox:1",
        )
        app.transfers.import(
            "حوالة داخلية واردة بـSR 50\nلـ1111\nمن2222; سالم عبدالله مثال\n$current",
            System.currentTimeMillis() + 1000,
            "inbox:2",
        )
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("آخر حوالة من الوالد").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("إخفاء إعداد الميزة").performScrollTo().performClick()
        compose.onNodeWithText("مجموع حوالات الوالد").performScrollTo().assertIsDisplayed()
        capture("father-transfers-6.3.png")
        val totals =
            transferTotals(app.transfers.rows(), app.transfers.aliases, app.transfers.cutoff)
        assertEquals(18000L, totals.all)
        assertEquals(13000L, totals.old)
        assertEquals(5000L, totals.fresh)
        assertEquals(5000L, totals.last!!.amount)
        assertEquals(cutoff, app.transfers.cutoff)
        assertTrue(runBlocking { app.repository.dao.expenses().first().isEmpty() })
    }

    @Test
    fun temporaryCampusLegIsDiscoverableAndOriginalTripAndHomeRemainIntact() {
        val app = app()
        val start = System.currentTimeMillis() - 3600000
        var id = 0L
        var type = ""
        runBlocking {
            app.repository.dao.place(SavedPlace("HOME", 21.0, 39.0, 200.0))
            type = temporaryPlaceType(false, start - 60000, start + 86400000, "استراحة اختبار")
            app.repository.dao.place(SavedPlace(type, 21.5, 39.2, 100.0))
            id =
                app.repository.dao.insert(
                    Outing(
                        startedAt = start,
                        endedAt = start + 400000,
                        state = EngineState(meters = 400.0),
                    )
                )
            for (i in 0..6) app.repository.dao.point(
                RoutePoint(
                    outingId = id,
                    latitude = if (i < 3) 21.5 else 21.51 + i * .001,
                    longitude = 39.2,
                    accuracy = 5.0,
                    speed = if (i < 3) 0.0 else 10.0,
                    altitude = 0.0,
                    bearing = 0.0,
                    recordedAt = start + i * 60000L,
                    distanceDelta = if (i > 2) 100.0 else 0.0,
                )
            )
            app.repository.dao.event(
                JourneyEvent(
                    outingId = id,
                    type = "VISIT",
                    startedAt = start + 300000,
                    endedAt = start + 360000,
                    latitude = 21.52,
                    longitude = 39.2,
                    confirmed = true,
                )
            )
        }
        val original = runBlocking { app.repository.dao.outings().single() }
        open("الأماكن المؤقتة والجامعة")
        compose.waitUntil(20000) {
            compose
                .onAllNodesWithText("فتح المشوار الأصلي كاملًا")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("فتح المشوار الأصلي كاملًا").performScrollTo().assertIsDisplayed()
        capture("temporary-campus-6.3.png")
        compose.onNodeWithText("فتح المشوار الأصلي كاملًا").performClick()
        compose.onNodeWithText("أدوات خط المسار").performScrollTo().assertIsDisplayed()
        assertEquals(original, runBlocking { app.repository.dao.outings().single() })
        assertTrue(runBlocking { app.repository.dao.places().any { it.type == "HOME" } })
    }
}
