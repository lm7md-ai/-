package sa.rihla.audio

import android.content.SharedPreferences

enum class AudioScene(val key:String,val label:String,val width:Float,val reflections:Float) {
    NATURAL("natural","طبيعي · مباشر",0f,0f),
    SPACE_3D("3d","3D · افتراضي",.45f,.12f),
    SURROUND_71("7.1","7.1 · محاكاة",.8f,.32f),
    LECTURE_SOFT("lecture_soft","محاضرة · تخفيف الحدة",0f,0f),
    LECTURE_CLEAR("lecture_clear","محاضرة · وضوح الكلام",0f,0f),
    CUSTOM("custom","مخصص",0f,0f);
    val lecture get()=key.startsWith("lecture_")
    companion object {
        fun read(prefs:SharedPreferences)=entries.find { it.key==prefs.getString("scene","natural") } ?: NATURAL
    }
}

fun selectAudioScene(prefs:SharedPreferences,scene:AudioScene) {
    // Keep the established key and every manual effect value. Software presets use
    // their AudioScene profile; returning to CUSTOM restores the untouched controls.
    prefs.edit().putString("scene",scene.key).apply()
}
