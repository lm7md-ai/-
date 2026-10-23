package sa.rihla.sms

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.provider.Telephony
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import sa.rihla.RihlaApp
import sa.rihla.core.PurchaseSmsParser

/** Stores only health codes/times, never SMS text, sender or card details. */
object SmsHealth {
    fun write(c: Context, key: String, value: String) {
        c.getSharedPreferences("sms_health", Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .putLong("updated", System.currentTimeMillis())
            .apply()
    }

    suspend fun import(app: RihlaApp, source: String, body: String, time: Long) {
        if (!app.settings.flow.first().smsImport) return
        val result =
            if (PurchaseSmsParser.parse(body) == null) "unrecognized"
            else if (app.repository.importSms(source, body, time)) "imported"
            else "duplicate_or_disabled"
        write(app, "result", result)
    }
}

class PurchaseSmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!allowed(c)) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return
        // The broadcast contains the parts of one SMS. Join before parsing its amount.
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val time =
            messages.first().timestampMillis.takeIf { it > 0 && it <= System.currentTimeMillis() }
                ?: System.currentTimeMillis()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = c.applicationContext as RihlaApp
                if (app.transfers.enabled) app.transfers.import(body, time, "sms:$time")
                SmsHealth.import(
                    app,
                    Telephony.Sms.getDefaultSmsPackage(c) ?: "sms",
                    body,
                    time,
                )
            } catch (_: Exception) {
                SmsHealth.write(c, "result", "error")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun allowed(c: Context) =
            c.checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED
    }
}
