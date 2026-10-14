package sa.rihla.core

import org.junit.Assert.*
import org.junit.Test

class PurchaseSmsTest {
    @Test
    fun actualRajhiGooglePayPurchaseExtractsAmountMerchantAndSaudiTime() {
        val body = "شراء PoS\nعبر5231;مدى-جوجل باي\nبـSR 32\nلـEXPRESS FOOD\n26/10/14 16:51"
        assertEquals(SmsPurchase(3200, "EXPRESS FOOD"), PurchaseSmsParser.parse(body))
        val arrival = java.time.Instant.parse("2026-10-14T13:51:20Z").toEpochMilli()
        assertEquals(java.time.Instant.parse("2026-10-14T13:51:00Z").toEpochMilli(), purchaseMessageTime(body, arrival))
        assertEquals(arrival, purchaseMessageTime(body.replace("26/10/14", "26/99/14"), arrival))
    }
    @Test
    fun rajhiArabicSeparatesPurchaseFromCardAndBalance() {
        val body =
            "شراء عبر نقاط البيع\nبطاقة: 1234\nبمبلغ: 24.50 SAR\nلدى: TEST STORE\nفي: 2026-10-13 15:30\nالرصيد: 1000.00 SAR"
        assertEquals(SmsPurchase(2450, "TEST STORE"), PurchaseSmsParser.parse(body))
    }

    @Test
    fun rajhiEnglishUsesAmountBeforeAvailableBalance() {
        assertEquals(
            SmsPurchase(3500, "DEMO PETROL"),
            PurchaseSmsParser.parse(
                "POS Purchase\nCard: 1234\nAmount: SAR 35.00\nAt: DEMO PETROL\nAvailable balance: SAR 999.99"
            ),
        )
    }

    @Test
    fun arabicDigitsDecimalsAndBidiMarksAreSupported() {
        assertEquals(
            123456L,
            PurchaseSmsParser.parse("شراء\nبـ: ١٬٢٣٤٫٥٦ ر.س\nلدى: متجر")!!.amountHalala,
        )
        assertEquals(
            2450L,
            PurchaseSmsParser.parse("شراء مبلغ: \u200f۲۴٫۵۰ ريال سعودي")!!.amountHalala,
        )
    }

    @Test
    fun integerAndCommaDecimalAmountsAreSupported() {
        assertEquals(2500L, PurchaseSmsParser.parse("شراء مبلغ: 25 SAR")!!.amountHalala)
        assertEquals(2550L, PurchaseSmsParser.parse("شراء مبلغ: 25,50 ريال")!!.amountHalala)
        assertEquals(
            123456L,
            PurchaseSmsParser.parse("Purchase amount SAR 1,234.56")!!.amountHalala,
        )
    }

    @Test
    fun otpRefundDeclinedAndNonPurchaseMessagesAreIgnored() {
        listOf(
                "رمز تحقق لعملية شراء بمبلغ 25 SAR: 123456",
                "OTP for purchase amount SAR 25.00: 123456",
                "استرداد عملية شراء بمبلغ 25 SAR",
                "Purchase refund amount SAR 25",
                "عملية شراء مرفوضة مبلغ 25 SAR",
                "Purchase declined SAR 25",
                "تم تحويل مبلغ 25 SAR",
                "سحب نقدي مبلغ 25 SAR",
                "Your available balance is SAR 25",
                "Purchase reversed amount SAR 25",
            )
            .forEach { assertNull(it, PurchaseSmsParser.parse(it)) }
    }

    @Test
    fun ambiguousAmountsAndUnsupportedCurrenciesAreIgnored() {
        listOf(
                "Purchase SAR 25 SAR 30",
                "شراء مبلغ 25 USD\nالرصيد 100 SAR",
                "شراء مبلغ 12,34.50 SAR",
                "شراء مبلغ 0 SAR",
                "شراء مبلغ 25.555 SAR",
                "شراء مبلغ 10000001 SAR",
                "شراء بطاقة 1234 الرصيد 55 SAR",
            )
            .forEach {
                assertNull(it, PurchaseSmsParser.parse(it))
            }
    }

    @Test
    fun labeledPurchaseAmountDoesNotIncludeFees() {
        assertEquals(
            10000L,
            PurchaseSmsParser.parse("شراء\nمبلغ: 100 SAR\nرسوم: 2 SAR\nالرصيد: 500 SAR")!!
                .amountHalala,
        )
    }

    @Test
    fun missingMerchantDoesNotPreventAClearPurchase() {
        assertEquals(SmsPurchase(1200, ""), PurchaseSmsParser.parse("شراء 12 SAR"))
        assertNull(PurchaseSmsParser.parse(""))
        assertNull(PurchaseSmsParser.parse("شراء 12 SAR".repeat(2000)))
    }
}
