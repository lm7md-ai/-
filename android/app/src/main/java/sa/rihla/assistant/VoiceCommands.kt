package sa.rihla.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.*
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat

/** Tap-to-talk; on-device mode never falls back to a network recognizer. Main-thread only. */
class VoiceCommands(
    private val context: Context,
    private val status: (String) -> Unit,
    private val text: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var speakingReady = false
    private var speechGeneration = 0
    private var pendingSpeech = ""

    fun availableOffline() =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun intent(network: Boolean) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-SA")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !network)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

    private fun create(network: Boolean): SpeechRecognizer {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            "امنح إذن الميكروفون لبدء أمر صوتي"
        }
        if (!network && availableOffline())
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        check(network) {
            "خدمة التعرف على الجهاز غير متاحة. راجع إعدادات صوت الهاتف، أو فعّل التعرف عبر الشبكة باختيارك."
        }
        check(SpeechRecognizer.isRecognitionAvailable(context)) {
            "لم تُعثر على خدمة تعرف على الكلام. فعّل خدمة صوت متوافقة من إعدادات الهاتف."
        }
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    fun start(network: Boolean) {
        stop()
        tts?.stop()
        try {
            val active = create(network)
            recognizer = active
            active.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        status("أستمع الآن… اضغط إيقاف عند الانتهاء")
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        status("جارٍ تحويل الكلام إلى نص")
                    }

                    override fun onError(error: Int) {
                        status(
                            when (error) {
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                    "إذن الميكروفون غير متاح"
                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                    "لم ألتقط أمرًا واضحًا؛ حاول مجددًا"
                                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                                    "خدمة الهاتف لا تدعم العربية المطلوبة"
                                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                    "لغة التعرف العربية تحتاج تنزيلًا من خدمة الهاتف"
                                SpeechRecognizer.ERROR_NETWORK,
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                    "تعذر الاتصال بخدمة التعرف"
                                else -> "تعذر التعرف على الكلام (رمز $error)"
                            }
                        )
                        stop()
                    }

                    override fun onResults(results: Bundle?) {
                        val answer =
                            results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                        if (answer.isNotBlank()) {
                            text(answer.take(1200))
                            status("راجع نص الأمر والاقتراح قبل التنفيذ")
                        } else status("لم يُلتقط نص واضح")
                        stop()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                }
            )
            status(
                if (!network) "تجهيز الاستماع على الجهاز" else "تجهيز خدمة التعرف؛ قد تستخدم الشبكة"
            )
            active.startListening(intent(network))
        } catch (e: Exception) {
            stop()
            status(e.message ?: "تعذر بدء الميكروفون")
        }
    }

    fun downloadLanguage() {
        try {
            if (Build.VERSION.SDK_INT < 33 || !availableOffline()) {
                error(
                    "تنزيل اللغة من داخل التطبيق يحتاج خدمة تعرف على الجهاز وAndroid 13 أو أحدث؛ افتح إعدادات صوت الهاتف"
                )
            }
            stop()
            val active = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = active
            active.triggerModelDownload(intent(false))
            status("أُرسل طلب تنزيل العربية لخدمة الهاتف؛ التوفر والتقدم تديرهما الخدمة")
        } catch (e: Exception) {
            status(e.message ?: "تعذر طلب تنزيل اللغة")
        }
    }

    fun speak(value: String) {
        stop()
        pendingSpeech = value
        if (tts == null) {
            val generation = ++speechGeneration
            tts =
                TextToSpeech(context) { result ->
                    if (generation != speechGeneration) return@TextToSpeech
                    if (result == TextToSpeech.SUCCESS) {
                        speakingReady = true
                        say(pendingSpeech)
                    } else status("محرك قراءة النص غير متاح")
                }
        } else if (speakingReady) say(value)
    }

    private fun say(value: String) {
        val voice =
            tts?.voices?.firstOrNull {
                it.locale.language == "ar" && !it.isNetworkConnectionRequired
            }
        if (voice == null) {
            status("لا يوجد صوت عربي محلي مثبت لقراءة الرد؛ راجع إعدادات تحويل النص إلى كلام")
            return
        }
        tts?.voice = voice
        tts?.speak(value.take(2000), TextToSpeech.QUEUE_FLUSH, null, "assistant-response")
    }

    fun stop() {
        val active = recognizer
        recognizer = null
        runCatching { active?.cancel() }
        runCatching { active?.destroy() }
    }

    fun close() {
        speechGeneration++
        pendingSpeech = ""
        stop()
        tts?.stop()
        tts?.shutdown()
        tts = null
        speakingReady = false
    }
}
