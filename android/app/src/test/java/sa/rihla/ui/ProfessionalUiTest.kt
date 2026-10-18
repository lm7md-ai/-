package sa.rihla.ui

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
import sa.rihla.audio.*
import sa.rihla.data.Expense

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RihlaApp::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfessionalUiTest {
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
            app.settings.theme("LIGHT")
        }
        app.getSystemService(AudioManager::class.java)
            .setStreamVolume(AudioManager.STREAM_MUSIC, 6, 0)
        val owned = Robolectric.buildService(AudioPlaybackService::class.java).create()
        service = owned
        Shadows.shadowOf(app)
            .setComponentNameAndServiceForBindService(
                ComponentName(app, AudioPlaybackService::class.java),
                owned.get().onBind(Intent("androidx.media3.session.MediaSessionService")),
            )
    }

    private fun start() {
        compose.setContent { FreshTestApplication { RihlaRoot() } }
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("المزيد").fetchSemanticsNodes().isNotEmpty()
        }
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
    fun playerIsSeparatePresetsRemainAccessibleAndLibraryDraftSurvivesBack() {
        prepare()
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        val wave = File(app.cacheDir, "design-fixture.wav")
        // Real local WAV, used only by this test. No audio decoding or playback code is replaced.
        val count = 16000 * 10
        wave.writeBytes(
            ByteBuffer.allocate(44 + count * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray())
                    putInt(36 + count * 2)
                    put("WAVEfmt ".toByteArray())
                    putInt(16)
                    putShort(1)
                    putShort(1)
                    putInt(16000)
                    putInt(32000)
                    putShort(2)
                    putShort(16)
                    put("data".toByteArray())
                    putInt(count * 2)
                    repeat(count) { putShort(1000) }
                }
                .array()
        )
        runBlocking {
            listOf(
                    "كيف نفهم أنفسنا",
                    "معاني تدبر القرآن",
                    "قصة من التاريخ",
                    "بناء العادات",
                    "مدخل إلى السيرة",
                    "مساحة للتأمل",
                )
                .forEachIndexed { index, title ->
                    app.audio.dao.insert(
                        AudioTrack(
                            "design-$index",
                            if (index == 0) wave.toURI().toString() else "content://design/$index",
                            title,
                            if (index % 2 == 0) "محاضرات مختارة" else "بودكاست المعرفة",
                            "مكتبتي",
                            10000,
                            favorite = index == 1,
                        )
                    )
                }
        }
        start()
        capture("home-5.2-light.png")
        compose.onNodeWithText("الصوتيات").performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("كيف نفهم أنفسنا").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("كيف نفهم أنفسنا").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("أوضاع الصوت الجاهزة").assertDoesNotExist()
        compose.onNodeWithText("قيد الاستماع").assertDoesNotExist()
        capture("audio-5.2-light.png")
        runBlocking { app.settings.theme("DARK") }
        compose.waitForIdle()
        capture("audio-5.2-dark.png")
        compose
            .onNodeWithText("ابحث بالعنوان أو الفنان أو الألبوم")
            .performScrollTo()
            .performTextInput("كيف")
        compose.onNodeWithText("كيف نفهم أنفسنا").performScrollTo().performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("قيد الاستماع").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("أوضاع الصوت الجاهزة").assertDoesNotExist()
        compose.onNodeWithContentDescription("رجوع 15 ثانية").assertIsDisplayed()
        compose
            .onNodeWithText("الصوتيات")
            .assertDoesNotExist() // Player has room of its own, without bottom tabs.
        capture("player-5.2-dark.png")
        compose.onNodeWithText("الصوت والمؤثرات").performScrollTo().performClick()
        listOf("3D · افتراضي" to "3d", "7.1 · محاكاة" to "7.1", "طبيعي · مباشر" to "natural")
            .forEach { (label, key) ->
                compose.onNodeWithText(label).performScrollTo().performClick()
                compose.waitUntil(10000) {
                    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    AudioLive.state.value.scene == key
                }
            }
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.onNodeWithText("كيف").assertExists()
        compose.onNodeWithText("قيد الاستماع").assertDoesNotExist()
        compose.onNodeWithText("محاضرات مختارة · 0:10").assertExists()
        capture("mini-player-5.2-dark.png")
        compose.onNodeWithContentDescription("مسح البحث").performClick()
        compose.onNodeWithText("قوائم التشغيل").performClick()
        compose.onNodeWithText("قائمة جديدة +").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("الألبومات").performClick()
        compose.onNodeWithText("مكتبتي").assertIsDisplayed()
        compose.onNodeWithText("مكتبتي").performClick()
        compose.onNodeWithText("عرض المكتبة كاملة").assertIsDisplayed()
        compose.onNodeWithText("عرض المكتبة كاملة").performClick()
        wave.delete()
    }

    @Test
    fun expensesStartWithRecordsAndToolsRemainReachableInTheirOwnTab() {
        prepare()
        val app = RuntimeEnvironment.getApplication() as RihlaApp
        runBlocking {
            app.repository.dao.expense(
                Expense(
                    category = "OTHER",
                    amountHalala = 3250,
                    createdAt = System.currentTimeMillis(),
                    merchant = "متجر الاختبار",
                    source = "SMS_NOTIFICATION",
                )
            )
        }
        start()
        compose.onNodeWithText("محفظتي").performScrollTo().performClick()
        compose.onNodeWithText("متجر الاختبار").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("التحليل والميزانية").performScrollTo().performClick()
        compose.onNodeWithText("متجر الاختبار").assertDoesNotExist()
        compose
            .onNodeWithText("إدارة الميزانيات والقواعد والمصاريف المتكررة")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("سجل المصروفات").performScrollTo().performClick()
        compose.onNodeWithText("متجر الاختبار").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("تفاصيل المصروف").performClick()
        compose.onNodeWithText("حذف").performScrollTo().assertIsDisplayed()
        capture("expenses-5.2-light.png")
    }

    @Test
    fun vehicleAndStatisticsTabsKeepDetailedToolsReachable() {
        prepare()
        start()
        compose.onNodeWithText("المزيد").performClick()
        compose.onNodeWithText("ابحث في الإعدادات والأدوات").performTextInput("سيارتي")
        compose.onNode(hasText("سيارتي") and !hasSetTextAction()).performClick()
        compose.onNodeWithText("المواصفات").performClick()
        compose.onNodeWithText("4A92", substring = true).assertIsDisplayed()
        capture("vehicle-5.2-light.png")
        compose.onNodeWithText("سجل الوقود").performClick()
        compose.onNodeWithText("4A92", substring = true).assertDoesNotExist()
        compose.onNodeWithText("سجل الوقود").assertIsSelected()
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.onNodeWithText("ابحث في الإعدادات والأدوات").performTextClearance()
        compose.onNodeWithText("ابحث في الإعدادات والأدوات").performTextInput("الإحصاءات")
        compose.onNode(hasText("الإحصاءات") and !hasSetTextAction()).performClick()
        compose.onNodeWithText("صورة أوضح لرحلاتك").assertIsDisplayed()
        capture("statistics-5.2-light.png")
        compose.onNodeWithText("التفاصيل والمقارنة").performClick()
        compose.onNodeWithText("التفاصيل والمقارنة").assertIsSelected()
        compose.onNodeWithText("وقت التوقف").performScrollTo().assertIsDisplayed()
    }
}
