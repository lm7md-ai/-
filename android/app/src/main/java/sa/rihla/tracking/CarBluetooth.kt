package sa.rihla.tracking

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import sa.rihla.MainActivity
import sa.rihla.RihlaApp
import sa.rihla.audio.AutoStopBookmarkManager
import sa.rihla.audio.studio.AssistantNavigation

class CarBluetoothReceiver : BroadcastReceiver() {
    @android.annotation.SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (
            action != BluetoothDevice.ACTION_ACL_CONNECTED &&
                action != BluetoothDevice.ACTION_ACL_DISCONNECTED
        )
            return
        if (!allowed(context)) return
        val device =
            if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice)
        val address = runCatching { device?.address }.getOrNull() ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as RihlaApp
                val preferences = app.settings.flow.first()
                if (
                    preferences.bluetoothAddress.isBlank() ||
                        !preferences.bluetoothAddress.equals(address, ignoreCase = true)
                )
                    return@launch

                if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                    if (preferences.auto && TrackingService.backgroundAllowed(context)) {
                        TrackingService.launch(
                            context,
                            automatic = app.repository.dao.active() == null,
                            motionResume = true,
                            bluetoothWakeup = true,
                        )
                    } else if (app.repository.dao.active() != null && TrackingService.fineAllowed(context)) {
                        TrackingService.wakeFromDeepSleep(context, "bluetooth_connected")
                    }
                    if (preferences.autoOpenCockpitOnBluetooth) {
                        AssistantNavigation.requests.tryEmit("cockpit")
                        runCatching {
                            context.startActivity(
                                Intent(context, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    .putExtra("open_route", "cockpit")
                            )
                        }
                    }
                    if (preferences.autoMorningBriefing) {
                        MorningBriefingTrigger.onBluetoothConnected(app)
                    }
                } else if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                    val now = System.currentTimeMillis()
                    val active = app.repository.dao.active()
                    if (active != null) {
                        val st = active.state
                        val lat = if (st.stopStart > 0 && st.stillLat != 0.0) st.stillLat else st.lastLat
                        val lon = if (st.stopStart > 0 && st.stillLon != 0.0) st.stillLon else st.lastLon
                        if (lat != null && lon != null) {
                            app.repository.saveCampusParkingIfNearUniversity(
                                lat = lat,
                                lon = lon,
                                accuracy = st.lastAccuracy.coerceAtLeast(5.0),
                                savedAt = now,
                                outingId = active.id,
                            )
                        }
                        TrackingService.notifyBluetoothDisconnected(context, now)
                    }
                    AutoStopBookmarkManager.onStopOrArrival(app, "إيقاف محرك اللانسر", now)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun allowed(context: Context) =
            Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
    }
}

object MorningBriefingTrigger {
    @Volatile var handler: (suspend (RihlaApp) -> Unit)? = null

    suspend fun onBluetoothConnected(app: RihlaApp) {
        val nowZdt = java.time.Instant.ofEpochMilli(System.currentTimeMillis()).atZone(sa.rihla.core.SaudiZone)
        if (nowZdt.hour !in 5..11) return
        val prefs = app.getSharedPreferences("briefing_state", Context.MODE_PRIVATE)
        val todayKey = nowZdt.toLocalDate().toString()
        if (prefs.getString("last_morning_date", null) == todayKey) return
        prefs.edit().putString("last_morning_date", todayKey).apply()
        handler?.invoke(app)
    }
}

data class TrackingHealth(
    val receivedAt: Long = 0,
    val accuracy: Double = 0.0,
    val provider: String = "",
    val accepted: Boolean = false,
    val service: String = "idle",
    val reason: String = "",
    val acceptedCount: Long = 0,
    val rejectedCount: Long = 0,
    val satellites: Int = 0,
    val usedSatellites: Int = 0,
    val averageCn0: Double = 0.0,
    val dualFrequency: Int = 0,
    val requestedInterval: Long = 0,
    val updatedElapsed: Long = 0,
    val deepSleep: Boolean = false,
    val liveSpeedKmh: Float = 0f,
)

object LiveTrackingHealth {
    val state = kotlinx.coroutines.flow.MutableStateFlow(TrackingHealth())
}
