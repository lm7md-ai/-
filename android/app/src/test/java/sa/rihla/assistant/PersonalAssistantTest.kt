package sa.rihla.assistant

import android.app.Application
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PersonalAssistantTest {
    @Test
    fun offlineVoiceNeverFallsBackToNetworkWhenDeviceRecognitionIsUnavailable() {
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        org.robolectric.shadows.ShadowSpeechRecognizer.reset()
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val messages = mutableListOf<String>()
        val voice = VoiceCommands(context, { messages += it }, {})
        voice.start(false)
        assertNull(org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertTrue(messages.any { it.contains("خدمة التعرف على الجهاز غير متاحة") })
        voice.close()
    }

    @Test
    fun voiceReturnsEditableTextAndReleasesTheRecognizerAfterAResult() {
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        var answer = ""
        val voice = VoiceCommands(context, {}, { answer = it })
        voice.start(false)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val latest = org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        val shadow = Shadows.shadowOf(latest)
        assertTrue(
            shadow.lastRecognizerIntent.getBooleanExtra(
                RecognizerIntent.EXTRA_PREFER_OFFLINE,
                false,
            )
        )
        shadow.triggerOnResults(
            android.os.Bundle().apply {
                putStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION,
                    arrayListOf("افتح الخريطة"),
                )
            }
        )
        assertEquals("افتح الخريطة", answer)
        assertTrue(shadow.isDestroyed)
        voice.close()
    }

    @Test
    fun allBuiltInCommandsResolveAndNaturalArabicVariantsWork() {
        val model = PersonalCommandModel(emptyList())
        for (intent in AppCommands.all) for (text in intent.examples) assertEquals(
            text,
            intent.id,
            model.predict(text)?.intent?.id,
        )
        assertEquals("map", model.predict("يا مساعدي ممكن تفتح لي الخَرِيطَة")?.intent?.id)
        assertEquals("pause", model.predict("من فضلك وقف الصوت")?.intent?.id)
        assertNull(model.predict("استراتيجية الاستثمار العالمية في الأسهم"))
        assertNull(model.predict("ممكن افتح"))
    }

    @Test
    fun customPhrasesTrainWithoutDownloadingAnLlmAndSurviveExportRestore() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("personal_assistant", 0).edit().clear().commit()
        val store = PersonalAssistantStore(context)
        store.save(
            AssistantProfile(
                name = "صاحبي",
                instructions = "اختصر الرد",
                notes = "محاضراتي المفضلة في الفقه",
            )
        )
        assertNull(store.model().predict("ورني الديرة"))
        store.train(listOf(CommandExample("ورني الديرة", "map")))
        assertEquals("map", store.model().predict("ورني الديرة")!!.intent.id)
        assertEquals("map",store.model().predict("افتح الديرة")?.intent?.id)
        assertTrue(store.trainedAt() > 0)
        val data = store.export()
        context.getSharedPreferences("personal_assistant", 0).edit().clear().commit()
        store.restore(data)
        assertEquals("صاحبي", store.profile().name)
        assertEquals("map", store.model().predict("ورني الديرة")!!.intent.id)
        assertFalse(store.profile().networkVoice)
        assertTrue(JSONObject(data).getJSONObject("weights").getJSONObject("counts").length() > 0)
    }

    @Test
    fun importedExamplesCannotAddExecutableActionsOrEnableNetworkVoice() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("personal_assistant", 0).edit().clear().commit()
        val store = PersonalAssistantStore(context)
        store.train(listOf(CommandExample("يا ديرتي", "map")))
        val data = JSONObject(store.export())
        data.getJSONArray("examples").getJSONObject(0).put("intent", "delete_files")
        assertThrows(IllegalArgumentException::class.java) { store.restore(data.toString()) }
        assertEquals("map", store.model().predict("يا ديرتي")!!.intent.id)
        assertFalse(store.profile().networkVoice)
    }

    @Test
    fun unknownAndOverlongTrainingExamplesAreRejectedBeforeChangingModel() {
        val store = PersonalAssistantStore(RuntimeEnvironment.getApplication())
        assertThrows(IllegalArgumentException::class.java) {
            store.train(listOf(CommandExample("احذف", "delete")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.train(listOf(CommandExample("x".repeat(161), "map")))
        }
    }

    @Test
    fun voiceIntentUsesArabicAndOfflinePreferenceAndMissingPermissionDoesNotListen() {
        val context = RuntimeEnvironment.getApplication()
        val messages = mutableListOf<String>()
        var transcript = ""
        val voice = VoiceCommands(context, messages::add, { transcript = it })
        val intent = voice.intent(false)
        assertEquals("ar-SA", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertFalse(voice.intent(true).getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
        Shadows.shadowOf(context).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        voice.start(false)
        assertTrue(messages.any { it.contains("إذن الميكروفون") })
        assertEquals("", transcript)
        voice.close()
    }
}
