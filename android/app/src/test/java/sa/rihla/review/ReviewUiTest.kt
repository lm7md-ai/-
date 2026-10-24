package sa.rihla.review

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.viewModelScope
import java.time.Duration
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import sa.rihla.feature.settings.SmsImportSettings
import sa.rihla.ui.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<JourneyViewModel>()

    private fun model(app: RihlaApp) = JourneyViewModel(app).also { models += it }

    @After
    fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
    }

    private fun prepare(): Pair<RihlaApp, JourneyViewModel> {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        runBlocking {
            app.settings.clear()
            app.settings.onboard()
        }
        return app to model(app)
    }

    @Test
    fun moreNavigationDescriptionMatchesItsVisibleLabel() {
        val (_, vm) = prepare()
        compose.setContent { FreshTestApplication { RihlaRoot(vm) } }
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("المزيد").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("المزيد", useUnmergedTree = true).assertExists()
    }

    @Test
    fun adoptingAnAliasUpdatesTheDraftAndTheNextSaveKeepsIt() {
        val (app, _) = prepare()
        val first = "مرسل تجريبي ألف"
        val second = "مرسل تجريبي باء"
        app.transfers.configure(setOf(first), 1580703000000, true)
        app.transfers.import(
            "حوالة داخلية واردة بـSR 271.36\nلـحساب-اختبار\nمنحساب-تجربة; $second\n20/02/03 04:05",
            1580703000000,
            "fixture:alias",
        )
        compose.setContent { FreshTestApplication { FatherTransfersScreen() } }
        compose.onNodeWithText("إعداد اسم المحوّل والأذونات").performScrollTo().performClick()
        compose.onNodeWithText("للمراجعة").performScrollTo().performClick()
        compose.onNodeWithText("اعتماد هذه الصيغة لاسم الوالد").performScrollTo().performClick()
        compose.waitUntil(20000) { second in app.transfers.aliases }
        compose.onNodeWithText("حفظ الاسم وتفعيل المتابعة").performScrollTo().performClick()
        compose.waitUntil(20000) {
            compose
                .onAllNodesWithText(
                    "فُعّلت المتابعة. يظل وقت التقسيم ثابتًا عند إعادة الفحص أو تعديل الاسم."
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals(setOf(first, second), app.transfers.aliases)
    }

    private class HealthPreferences(private val base: SharedPreferences) :
        SharedPreferences by base {
        var reads = 0
        val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getString(key: String?, defValue: String?): String? {
            reads++
            return base.getString(key, defValue)
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            listeners += listener
            base.registerOnSharedPreferenceChangeListener(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            listeners -= listener
            base.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private fun healthContext(app: Context, health: HealthPreferences) =
        object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                if (name == "sms_health") health else super.getSharedPreferences(name, mode)
        }

    @Test
    fun smsStatusDoesNotPollPreferencesDuringTwentyIdleSeconds() {
        val (app, vm) = prepare()
        val health = HealthPreferences(app.getSharedPreferences("sms_health", Context.MODE_PRIVATE))
        compose.setContent {
            FreshTestApplication {
                CompositionLocalProvider(
                    LocalContext provides healthContext(LocalContext.current, health)
                ) {
                    SmsImportSettings(vm, ScreenData())
                }
            }
        }
        compose.waitForIdle()
        val reads = health.reads
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(20000)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        compose.waitForIdle()
        assertEquals(reads, health.reads)
    }

    @Test
    fun smsPreferenceListenerUpdatesStatusAndIsRemovedWhenScreenLeaves() {
        val (app, vm) = prepare()
        val health = HealthPreferences(app.getSharedPreferences("sms_health", Context.MODE_PRIVATE))
        var visible by mutableStateOf(true)
        compose.setContent {
            FreshTestApplication {
                CompositionLocalProvider(
                    LocalContext provides healthContext(LocalContext.current, health)
                ) {
                    if (visible) SmsImportSettings(vm, ScreenData())
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, health.listeners.size)
        compose.runOnIdle { health.edit().putString("result", "imported").apply() }
        compose.onNodeWithText("حالة الاستقبال: تم تسجيل عملية شراء").assertExists()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        assertTrue(health.listeners.isEmpty())
    }
}
