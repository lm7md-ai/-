package sa.rihla

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sa.rihla.ui.RihlaRoot

class MainActivity : ComponentActivity() {
    private val audioRequest = androidx.compose.runtime.mutableIntStateOf(0)
    override fun attachBaseContext(newBase: android.content.Context) {
        val locale = java.util.Locale.forLanguageTag("ar-SA")
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.getBooleanExtra("open_audio", false)) audioRequest.intValue++
        setContent { RihlaRoot(audioRequest = audioRequest.intValue) }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_audio", false)) audioRequest.intValue++
    }
}
