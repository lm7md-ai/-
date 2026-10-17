package sa.rihla.audio

import android.content.SharedPreferences

enum class AudioScene(val key:String,val label:String,val width:Float,val reflections:Float) {
    NATURAL("natural","طبيعي · مباشر",0f,0f),
    SPACE_3D("3d","3D · افتراضي",.45f,.12f),
    SURROUND_71("7.1","7.1 · محاكاة",.8f,.32f),
    CUSTOM("custom","مخصص",0f,0f);
    companion object {
        fun read(prefs:SharedPreferences)=entries.find { it.key==prefs.getString("scene","natural") } ?: NATURAL
    }
}

fun selectAudioScene(prefs:SharedPreferences,scene:AudioScene) {
    prefs.edit().putString("scene",scene.key).putFloat("width",scene.width)
        .putInt("bass",0).putInt("surround",0).putBoolean("eq_enabled",false)
        .remove("eq_levels").putString("eq_preset","flat").apply()
}
