package sa.rihla.tracking

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import sa.rihla.RihlaApp

object Detection {
    fun available(c: Context) =
        Build.VERSION.SDK_INT < 29 ||
            c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED

    private fun pending(c: Context) =
        PendingIntent.getBroadcast(
            c,
            41,
            Intent(c, DetectionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
        )

    @Suppress("MissingPermission")
    fun register(c: Context, enabled: Boolean, onFailure: (Exception) -> Unit = {}) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val app = c.applicationContext as? RihlaApp ?: return@launch
            HomeGeofences.register(c, enabled, app.repository.dao.places().find { it.type == "HOME" }, onFailure)
        }
        val client = ActivityRecognition.getClient(c)
        if (!enabled) {
            client.removeActivityTransitionUpdates(pending(c))
            client.removeActivityUpdates(pending(c))
            return
        }
        if (!available(c)) return
        client.requestActivityUpdates(60000, pending(c)).addOnFailureListener(onFailure)
        client
            .requestActivityTransitionUpdates(
                ActivityTransitionRequest(
                    listOf(
                        ActivityTransition.Builder()
                            .setActivityType(DetectedActivity.IN_VEHICLE)
                            .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                            .build()
                    )
                ),
                pending(c),
            )
            .addOnFailureListener(onFailure)
    }
}

class DetectionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val transition =
            ActivityTransitionResult.extractResult(i)?.transitionEvents?.any {
                it.activityType == DetectedActivity.IN_VEHICLE &&
                    it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
            } == true
        val activity =
            ActivityRecognitionResult.extractResult(i)
                ?.getActivityConfidence(DetectedActivity.IN_VEHICLE)
                ?.let { it >= 75 } == true
        if (!transition && !activity) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = c.applicationContext as RihlaApp
                if (
                    app.settings.flow.first().auto &&
                        TrackingService.backgroundAllowed(c) &&
                        app.repository.dao.active() == null
                )
                    TrackingService.launch(c, true)
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = c.applicationContext as RihlaApp
                Detection.register(c, app.settings.flow.first().auto)
                HomeGeofences.restore(c)
                if (app.repository.dao.active() != null && TrackingService.backgroundAllowed(c))
                    TrackingService.launch(c)
                if (sa.rihla.sms.PurchaseNotificationService.allowed(c))
                    android.service.notification.NotificationListenerService.requestRebind(
                        ComponentName(c, sa.rihla.sms.PurchaseNotificationService::class.java))
            } finally {
                pending.finish()
            }
        }
    }
}
