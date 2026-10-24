package sa.rihla.review

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sa.rihla.audio.*
import sa.rihla.map.RouteMapView
import sa.rihla.tracking.TrackingService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "xxhdpi")
class ReviewBasicsTest {
    private fun attributionSize(fontScale: Float): Pair<Float, Float> {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply { this.fontScale = fontScale }
        val context = app.createConfigurationContext(config)
        val view = RouteMapView(context)
        val bitmap = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888)
        try {
            view.layout(0, 0, 1200, 1800)
            view.draw(Canvas(bitmap))
            val paint =
                RouteMapView::class
                    .java
                    .getDeclaredField("paint")
                    .apply { isAccessible = true }
                    .get(view) as Paint
            return paint.textSize to
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    22f,
                    context.resources.displayMetrics,
                )
        } finally {
            RouteMapView::class
                .java
                .getDeclaredMethod("onDetachedFromWindow")
                .apply { isAccessible = true }
                .invoke(view)
            bitmap.recycle()
        }
    }

    @Test
    fun mapAttributionRespectsDisplayDensity() {
        val (actual, expected) = attributionSize(1f)
        assertEquals(66f, expected, .1f)
        assertEquals(expected, actual, .1f)
    }

    @Test
    fun mapAttributionRespectsEverySystemFontScale() {
        for (scale in listOf(1f, 1.5f, 2f)) {
            val (actual, expected) = attributionSize(scale)
            assertEquals("fontScale=$scale", expected, actual, .1f)
        }
    }

    @Test
    fun trackingFieldsReadAcrossDispatchersAreVolatile() {
        for (name in listOf("lastSpeed", "turnDetails", "turnBoostUntil", "boostUntil")) assertTrue(
            name,
            Modifier.isVolatile(TrackingService::class.java.getDeclaredField(name).modifiers),
        )
    }

    @Test
    fun automaticSceneSelectionPreservesEveryManualEffectAndQueueSetting() {
        val prefs =
            RuntimeEnvironment.getApplication()
                .getSharedPreferences("audio_player", Context.MODE_PRIVATE)
        prefs
            .edit()
            .putInt("bass", 450)
            .putInt("surround", 350)
            .putBoolean("eq_enabled", true)
            .putString("eq_levels", "[1,2,3]")
            .putString("eq_preset", "voice")
            .putFloat("width", .6f)
            .putString("queue", "fixture")
            .putLong("queue_position", 12345)
            .apply()
        for (scene in AudioScene.entries) {
            selectAudioScene(prefs, scene)
            assertEquals(scene, AudioScene.read(prefs))
            assertEquals(450, prefs.getInt("bass", 0))
            assertEquals(350, prefs.getInt("surround", 0))
            assertTrue(prefs.getBoolean("eq_enabled", false))
            assertEquals("[1,2,3]", prefs.getString("eq_levels", null))
            assertEquals("voice", prefs.getString("eq_preset", null))
            assertEquals(.6f, prefs.getFloat("width", 0f), 0f)
            assertEquals("fixture", prefs.getString("queue", null))
            assertEquals(12345L, prefs.getLong("queue_position", 0))
        }
    }
}
