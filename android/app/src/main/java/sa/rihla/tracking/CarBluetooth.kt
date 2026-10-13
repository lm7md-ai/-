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
import sa.rihla.RihlaApp

class CarBluetoothReceiver : BroadcastReceiver() {
    @android.annotation.SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED || !allowed(context)) return
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
                    preferences.auto &&
                        preferences.bluetoothAddress == address &&
                        TrackingService.backgroundAllowed(context) &&
                        app.repository.dao.active() == null
                )
                    TrackingService.launch(context, true)
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

data class TrackingHealth(
    val receivedAt: Long = 0,
    val accuracy: Double = 0.0,
    val provider: String = "",
    val accepted: Boolean = false,
)

object LiveTrackingHealth {
    val state = kotlinx.coroutines.flow.MutableStateFlow(TrackingHealth())
}
