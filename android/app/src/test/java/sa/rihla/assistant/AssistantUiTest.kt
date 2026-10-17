package sa.rihla.assistant

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import sa.rihla.audio.AudioLive
import sa.rihla.audio.AudioPlayback
import sa.rihla.audio.AudioPlaybackService
import sa.rihla.ui.RihlaRoot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantUiTest {
    @get:Rule val compose = createComposeRule()

    private var service:
        org.robolectric.android.controller.ServiceController<AudioPlaybackService>? =
        null

    @After
    fun cleanup() {
        service?.destroy()
        AudioLive.state.value = AudioPlayback()
    }

    private fun prepare() {
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        runBlocking {
            app.settings.clear()
            app.settings.onboard()
        }
        val owned = Robolectric.buildService(AudioPlaybackService::class.java).create()
        service = owned
        Shadows.shadowOf(app)
            .setComponentNameAndServiceForBindService(
                ComponentName(app, AudioPlaybackService::class.java),
                owned.get().onBind(Intent("androidx.media3.session.MediaSessionService")),
            )
        compose.setContent { sa.rihla.ui.FreshTestApplication { RihlaRoot() } }
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("المساعد").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        val file = java.io.File("build/outputs/ui-previews/$name")
        file.parentFile!!.mkdirs()
        file.outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun localSpendingAnswerUsesSaudiTodayAndExcludesOtherDaysWithoutQwen() {
        prepare()
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.of("Asia/Riyadh")
        val date = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        runBlocking {
            app.repository.dao.expense(
                sa.rihla.data.Expense(
                    category = "OTHER",
                    amountHalala = 1200,
                    createdAt = start + (now - start) / 2,
                )
            )
            app.repository.dao.expense(
                sa.rihla.data.Expense(
                    category = "OTHER",
                    amountHalala = 9000,
                    createdAt = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
            )
            app.repository.dao.expense(
                sa.rihla.data.Expense(
                    category = "OTHER",
                    amountHalala = 7000,
                    createdAt = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
            )
        }
        compose.onNodeWithText("المساعد").performClick()
        compose.onNodeWithText("أمرك للمساعد").performScrollTo().performTextInput("كم صرفت اليوم")
        compose.onNodeWithText("فهم الأمر").performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithText("المصروفات المسجلة اليوم:", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose
            .onNodeWithText("المصروفات المسجلة اليوم:", substring = true)
            .performScrollTo()
            .assertTextContains("١٢٫٠٠ ر.س · 1 عملية.", substring = true)
    }

    @Test
    fun modelsAreDirectlyDiscoverableAndSettingsAreGrouped() {
        prepare()
        compose.onNodeWithText("المزيد").performClick()
        compose.onNodeWithText("نماذج الذكاء الاصطناعي").assertIsDisplayed()
        capture("settings-5.1.png")
        compose.onNodeWithText("نماذج الذكاء الاصطناعي").performClick()
        compose.onNodeWithText("مشواري · نموذج أوامري").assertIsDisplayed()
        capture("models-5.1.png")
        compose
            .onNodeWithText("Qwen · مساعد الأسئلة والتصنيف")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.onNodeWithText("ابحث في الإعدادات والأدوات").performTextInput("المظهر")
        compose.onNode(hasText("المظهر") and !hasSetTextAction()).performClick()
        compose.onNodeWithText("المظهر").assertExists()
        compose.onNodeWithText("العودة إلى الإعدادات والأدوات").performClick()
        compose.onNodeWithText("ابحث في الإعدادات والأدوات").performTextClearance()
        compose.onNodeWithText("ابحث في الإعدادات والأدوات").performTextInput("الأذونات")
        compose.onNode(hasText("الأذونات والبطارية") and !hasSetTextAction()).performClick()
        compose
            .onNodeWithText("منح إذن الميكروفون للأوامر الصوتية")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun personalPhraseCanBeTrainedAndVoiceEntryIsVisibleWithoutDownloadingQwen() {
        prepare()
        compose.onNodeWithText("المساعد").performClick()
        compose.onNodeWithText("تحدث بأمر صوتي").assertIsDisplayed()
        capture("assistant-5.1.png")
        compose.onNodeWithText("تخصيص مساعدي وتدريب نموذجي").performScrollTo().performClick()
        compose
            .onNodeWithText("عبارة جديدة للتدريب")
            .performScrollTo()
            .performTextInput("ورني الديرة")
        compose.onNodeWithText("إضافة المثال وتدريب نموذجي").performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("ورني الديرة").fetchSemanticsNodes()
            PersonalAssistantStore(RuntimeEnvironment.getApplication()).examples().size == 1
        }
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.onNodeWithText("أمرك للمساعد").performScrollTo().performTextInput("ورني الديرة")
        compose.onNodeWithText("فهم الأمر").performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("تنفيذ الاقتراح").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("تنفيذ الاقتراح").performScrollTo().assertIsDisplayed()
        // Suggestions do not navigate until explicitly approved.
        compose.onNodeWithText("مراجعة الاقتراح").assertIsDisplayed()
        compose.onNodeWithText("تنفيذ الاقتراح").performClick()
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithText("خريطة السعودية دون إنترنت")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
