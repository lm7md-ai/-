package sa.rihla.core

import java.math.BigDecimal

data class SmsPurchase(val amountHalala: Long, val merchant: String)

/** Rajhi's standalone Saudi local timestamp gives both delivery channels one receipt identity. */
fun purchaseMessageTime(body: String, receivedAt: Long): Long {
    val line = Regex("(?m)^\\s*([0-9]{2}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2})\\s*$")
        .find(normalizeSms(body))?.groupValues?.get(1) ?: return receivedAt
    return try {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("uu/MM/dd HH:mm")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)
        java.time.LocalDateTime.parse(line, formatter).atZone(java.time.ZoneId.of("Asia/Riyadh"))
            .toInstant().toEpochMilli().takeIf { it > 0 && it <= receivedAt + 120_000 } ?: receivedAt
    } catch (_: java.time.DateTimeException) { receivedAt }
}

fun normalizeSms(text: String): String = buildString {
    text.forEach { char ->
        when {
            char in '٠'..'٩' -> append('0' + (char - '٠'))
            char in '۰'..'۹' -> append('0' + (char - '۰'))
            char == '٫' -> append('.')
            char == '٬' -> append(',')
            char == 'ـ' ||
                char in '\u200e'..'\u200f' ||
                char in '\u202a'..'\u202e' ||
                char in '\u2066'..'\u2069' -> Unit
            else -> append(char)
        }
    }
}

/** Deliberately conservative: an unclear SMS never becomes a guessed expense. */
object PurchaseSmsParser {
    private val purchase =
        Regex("شراء|مشتريات|purchase|\\bpos\\b|payment\\s+at|paid\\s+at", RegexOption.IGNORE_CASE)
    private val excluded =
        Regex(
            "رمز|تحقق|\\botp\\b|verification|refund|reversal|reversed|استرداد|استرجاع|إلغاء|الغاء|ملغ|declin|رفض|مرفوض|غير كاف|insufficient|تحويل|transfer|سحب|withdraw|cash\\s*back",
            RegexOption.IGNORE_CASE,
        )
    private const val number = "[0-9]+(?:[.,][0-9]+)*"
    private const val currency = "(?:SAR|SR|ر\\.?\\s*س\\.?|ريال(?:\\s+سعودي)?)"
    private val money =
        Regex(
            "(?<![0-9.,])($number)\\s*$currency(?![\\p{L}])|$currency\\s*[:=]?\\s*($number)(?![0-9.,])",
            RegexOption.IGNORE_CASE,
        )
    private val notPurchaseAmount =
        Regex(
            "(?:رصيد|balance|حد|limit|رسوم|fees?|ضريبة|vat)[^0-9\\n]{0,25}$",
            RegexOption.IGNORE_CASE,
        )
    private val amountLabel =
        Regex("(?:مبلغ|قيمة|amount|ب|for)[\\s:=\\-]*$", RegexOption.IGNORE_CASE)
    private val merchant =
        Regex(
            "(?:^|\\n|[;؛])\\s*(?:لدى|عند|التاجر|merchant|at|ل)\\s*[:=]?\\s*([^\\n;؛]+)",
            RegexOption.IGNORE_CASE,
        )

    private fun amount(text: String): Long? {
        val decimal =
            when {
                text.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?")) -> text
                text.matches(Regex("[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?")) ->
                    text.replace(",", "")
                text.matches(Regex("[0-9]+,[0-9]{1,2}")) -> text.replace(',', '.')
                else -> return null
            }
        return try {
            BigDecimal(decimal).movePointRight(2).longValueExact().takeIf { it in 1..1_000_000_000 }
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun parse(body: String): SmsPurchase? {
        if (body.length !in 1..10000) return null
        val text = normalizeSms(body)
        if (!purchase.containsMatchIn(text) || excluded.containsMatchIn(text)) return null
        val amounts =
            money
                .findAll(text)
                .mapNotNull { match ->
                    val prefix =
                        text
                            .substring((match.range.first - 60).coerceAtLeast(0), match.range.first)
                            .substringAfterLast('\n')
                    if (notPurchaseAmount.containsMatchIn(prefix)) return@mapNotNull null
                    val value =
                        amount(match.groupValues[1].ifEmpty { match.groupValues[2] })
                            ?: return@mapNotNull null
                    value to amountLabel.containsMatchIn(prefix)
                }
                .toList()
        val labeled = amounts.filter { it.second }
        val candidates = if (labeled.isNotEmpty()) labeled else amounts
        if (candidates.size != 1) return null
        val name = merchant.find(text)?.groupValues?.get(1)?.trim()?.take(100) ?: ""
        return SmsPurchase(candidates.single().first, name)
    }
}
