package sa.rihla.data

import androidx.room.withTransaction
import java.security.MessageDigest
import sa.rihla.core.PurchaseSmsParser
import sa.rihla.core.normalizeSms

class SmsExpenseImporter(private val db: JourneyDatabase) {
    suspend fun import(packageName: String, body: String, receivedAt: Long): Boolean {
        if (receivedAt <= 0) return false
        val purchase = PurchaseSmsParser.parse(body) ?: return false
        val key =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    "$packageName\n$receivedAt\n${normalizeSms(body).replace(Regex("\\s+"), " ").trim()}"
                        .toByteArray(Charsets.UTF_8)
                )
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        return db.withTransaction {
            if (db.dao().claimSms(ImportedSms(key, receivedAt)) == -1L) return@withTransaction false
            db.dao()
                .importExpense(
                    Expense(
                        outingId = db.dao().outingAt(receivedAt),
                        category = "OTHER",
                        amountHalala = purchase.amountHalala,
                        createdAt = receivedAt,
                        source = "SMS_NOTIFICATION",
                        merchant = purchase.merchant,
                        importKey = key,
                    )
                ) != -1L
        }
    }
}
