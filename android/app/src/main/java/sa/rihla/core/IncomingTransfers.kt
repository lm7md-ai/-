package sa.rihla.core

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.*
import java.time.format.*

data class ParsedTransfer(val amount: Long, val sender: String, val time: Long?)

fun transferName(value: String) =
    normalizeSms(value)
        .lowercase()
        .replace(Regex("[أإآ]"), "ا")
        .replace('ى', 'ي')
        .replace(Regex("\\s+"), " ")
        .trim()

fun transferHash(value: String) =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
        ""
    ) {
        "%02x".format(it.toInt() and 255)
    }

object IncomingTransferParser {
    fun parse(body: String): ParsedTransfer? {
        if (body.length !in 1..10000) return null
        val text = normalizeSms(body)
        if (
            !Regex("(?:حوال[ةه]|تحويل|transfer)", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
                !Regex("وارد|مستلم|received|incoming|credited", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text) ||
                Regex(
                        "صادر|لم يتم|مرفوض|رفض|ملغ|الغاء|إلغاء|عكس|استرداد|refund|revers|declin|failed|\\botp\\b|رمز تحقق|رمز تفعيل",
                        RegexOption.IGNORE_CASE,
                    )
                    .containsMatchIn(text)
        )
            return null
        val currency = "(?:SAR|SR|ريال(?: سعودي)?|ر\\.?\\s*س\\.?)"
        val money =
            Regex(
                "(?:^|[\\n;؛])\\s*(?:مبلغ|المبلغ|بقيمة|قيمة|amount)\\s*[:：=]?\\s*(?:$currency\\s*)?([0-9]+(?:[.,][0-9]+)*)(?:\\s*$currency)?(?=\\s*(?:$|[\\n;؛]))",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
            )
        val rajhi =
            Regex(
                "(?:حوال[ةه]\\s+(?:داخلية|محلية)\\s+واردة)\\s+ب[ـ\\s]*$currency\\s*([0-9]+(?:[.,][0-9]+)*)",
                RegexOption.IGNORE_CASE,
            )
        val matches =
            money
                .findAll(text)
                .filter { Regex(currency, RegexOption.IGNORE_CASE).containsMatchIn(it.value) }
                .toList() + rajhi.findAll(text).toList()
        val amounts =
            matches
                .mapNotNull {
                    val n = it.groupValues[1]
                    val value =
                        when {
                            n.matches(Regex("[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?")) ->
                                n.replace(",", "")
                            n.matches(Regex("[0-9]+,[0-9]{1,2}")) -> n.replace(',', '.')
                            n.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?")) -> n
                            else -> return@mapNotNull null
                        }
                    runCatching { BigDecimal(value).movePointRight(2).longValueExact() }
                        .getOrNull()
                        ?.takeIf { v -> v in 1..1_000_000_000 }
                }
                .toList()
        if (amounts.size != 1) return null
        val sender =
            Regex(
                    "(?:^|[\\n;؛])\\s*(?:من|المرسل|اسم المحول|المحول|from|sender)\\s*[:：=]?\\s*([^\\n]+)",
                    RegexOption.IGNORE_CASE,
                )
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.take(120)
                .orEmpty()
        val identity = sender.substringAfter(';', sender).substringAfter('؛').trim()
        return ParsedTransfer(amounts.single(), identity, messageTime(text))
    }

    fun messageTime(text: String): Long? {
        val patterns =
            listOf(
                "uuuu-MM-dd HH:mm:ss",
                "uuuu-MM-dd HH:mm",
                "uuuu/MM/dd HH:mm:ss",
                "uuuu/MM/dd HH:mm",
                "uu/MM/dd HH:mm:ss",
                "uu/MM/dd HH:mm",
                "dd/MM/uuuu HH:mm",
            )
        val date =
            Regex(
                    "(?<![0-9])(?:[0-9]{4}[-/][0-9]{2}[-/][0-9]{2}|[0-9]{2}/[0-9]{2}/[0-9]{2,4})[ T]+[0-9]{2}:[0-9]{2}(?::[0-9]{2})?(?![0-9])"
                )
                .find(text)
                ?.value
                ?.replace('T', ' ') ?: return null
        return patterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                    LocalDateTime.parse(
                            date,
                            DateTimeFormatter.ofPattern(pattern)
                                .withResolverStyle(ResolverStyle.STRICT),
                        )
                        .atZone(SaudiZone)
                        .toInstant()
                        .toEpochMilli()
                }
                .getOrNull()
        }
    }
}
