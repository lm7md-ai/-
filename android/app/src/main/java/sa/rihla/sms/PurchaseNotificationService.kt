package sa.rihla.sms

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.os.BundleCompat
import kotlinx.coroutines.*
import sa.rihla.RihlaApp

data class SmsNotificationText(val body: String, val receivedAt: Long)

fun smsNotificationTexts(notification: Notification, postedAt: Long): List<SmsNotificationText> {
    if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return emptyList()
    val messages =
        BundleCompat.getParcelableArray(
            notification.extras,
            Notification.EXTRA_MESSAGES,
            Bundle::class.java,
        )
    if (messages != null) {
        val extracted = messages
            .filterIsInstance<Bundle>()
            .mapNotNull {
                val body = it.getCharSequence("text")?.toString() ?: return@mapNotNull null
                val time = it.getLong("time")
                if (time !in 1..postedAt) return@mapNotNull null
                SmsNotificationText(body, time)
            }
            .sortedBy { it.receivedAt }
        if (extracted.isNotEmpty()) return extracted
    }
    val body =
        notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")?.takeIf { it.isNotBlank() }
            ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: return emptyList()
    val time = notification.`when`.takeIf { it in 1..postedAt } ?: postedAt
    return listOf(SmsNotificationText(body, time))
}

class PurchaseNotificationService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        SmsHealth.write(this, "listener", "connected")
    }

    override fun onListenerDisconnected() {
        SmsHealth.write(this, "listener", "disconnected")
        requestRebind(ComponentName(this, PurchaseNotificationService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != Telephony.Sms.getDefaultSmsPackage(this)) return
        val messages = smsNotificationTexts(sbn.notification, sbn.postTime)
        if (messages.isEmpty()) return
        val app = application as RihlaApp
        scope.launch {
            try {
                messages.forEach { message ->
                    SmsHealth.import(app, sbn.packageName, message.body, message.receivedAt)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SmsHealth.write(this@PurchaseNotificationService, "result", "error")
                // A failed import must not crash tracking or log a private message.
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun allowed(context: Context): Boolean {
            val component = ComponentName(context, PurchaseNotificationService::class.java)
            if (Build.VERSION.SDK_INT >= 27)
                return context
                    .getSystemService(android.app.NotificationManager::class.java)
                    .isNotificationListenerAccessGranted(component)
            return android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners",
                )
                ?.split(':')
                ?.any { ComponentName.unflattenFromString(it) == component } == true
        }
    }
}
