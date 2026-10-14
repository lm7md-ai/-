package sa.rihla.data

import androidx.room.withTransaction
import java.security.MessageDigest
import sa.rihla.core.PurchaseSmsParser
import sa.rihla.core.normalizeSms

class SmsExpenseImporter(private val db: JourneyDatabase) {
    suspend fun import(packageName: String, body: String, receivedAt: Long): Boolean {
        if (receivedAt <= 0) return false
        val purchase = PurchaseSmsParser.parse(body) ?: return false
        val messageTime = sa.rihla.core.purchaseMessageTime(body, receivedAt)
        fun fingerprint(time: Long) =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    "$packageName\n$time\n${normalizeSms(body).replace(Regex("\\s+"), " ").trim()}"
                        .toByteArray(Charsets.UTF_8)
                )
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        val key = fingerprint(messageTime)
        return db.withTransaction {
            // Retain 1.3–3.0 receipt identities, including deliberately deleted expenses.
            if (messageTime != receivedAt && db.dao().smsReceipts(minOf(messageTime, receivedAt) - 120_000, receivedAt)
                    .any { it.key == fingerprint(it.receivedAt) }) {
                db.dao().claimSms(ImportedSms(key, messageTime))
                return@withTransaction false
            }
            if (db.dao().claimSms(ImportedSms(key, messageTime)) == -1L) return@withTransaction false
            db.dao()
                .importExpense(
                    Expense(
                        outingId = db.dao().outingAt(messageTime),
                        category =
                            sa.rihla.core.merchantCategory(purchase.merchant, db.dao().rules()),
                        amountHalala = purchase.amountHalala,
                        createdAt = messageTime,
                        source = "SMS_NOTIFICATION",
                        merchant = purchase.merchant,
                        importKey = key,
                    )
                ) != -1L
        }
    }
}
