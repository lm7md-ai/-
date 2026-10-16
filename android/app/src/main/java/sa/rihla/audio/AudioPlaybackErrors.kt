package sa.rihla.audio

import androidx.media3.common.PlaybackException

/** Show the failing stage without exposing provider URIs, paths or exception messages. */
fun audioPlaybackError(error: PlaybackException): String {
    val explanation = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "لم يعد الملف موجودًا في موقعه السابق. استخدم إعادة ربط ملف من قائمة المقطع."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "تعذر الوصول للملف بإذن النظام الحالي. أعد ربطه من قائمة المقطع لمنح الوصول."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "مزود الملف يحتاج اتصالًا لتنزيله. احفظ المقطع على الهاتف ثم أضفه للتشغيل دون إنترنت."
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
            "تعذرت قراءة بيانات المقطع. تحقق من اكتمال الملف وإمكانية فتحه في تطبيق الملفات."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "تعذر فك ترميز المقطع على الجهاز. جرّب نسخة بترميز آخر أو مقطعًا آخر."
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
            "أوقف النظام مفكك الصوت مؤقتًا. أعد تشغيل المقطع."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED ->
            "تعذر إخراج الصوت إلى السماعات الحالية. جرّب مخرج صوت آخر ثم أعد التشغيل."
        else -> "حدث خطأ داخلي في المشغل. أعد المحاولة، وأرسل رمز الخطأ إذا تكرر."
    }
    return "$explanation (رمز ${error.errorCode})"
}
