package sa.rihla.core

import org.junit.Assert.*
import org.junit.Test

class IncomingTransfersTest {
    private val receipt =
        "حوالة داخلية واردة بـSR 130\nلـ1111\nمن2222; سالم عبدالله مثال\n26/10/22 10:18"

    @Test
    fun rajhiIncomingAmountSenderAndHistoricalDateAreExtractedWithoutAccountDigits() {
        val parsed = IncomingTransferParser.parse(receipt)!!
        assertEquals(13000L, parsed.amount)
        assertEquals("سالم عبدالله مثال", parsed.sender)
        val local = java.time.Instant.ofEpochMilli(parsed.time!!).atZone(SaudiZone)
        assertEquals(2026, local.year)
        assertEquals(10, local.monthValue)
        assertEquals(22, local.dayOfMonth)
        assertEquals(10, local.hour)
        assertEquals(18, local.minute)
        assertEquals(13000L, IncomingTransferParser.parse(receipt.replace("130", "١٣٠"))!!.amount)
    }

    @Test
    fun outgoingFailedRefundAndPurchaseMessagesCannotBecomeIncome() {
        listOf(
                receipt.replace("واردة", "صادرة"),
                receipt + "\nحالة: مرفوضة",
                receipt + "\nاسترداد",
                "شراء PoS\nبـSR 130\nلـSHOP",
            )
            .forEach { assertNull(IncomingTransferParser.parse(it)) }
        assertNull(IncomingTransferParser.parse(receipt + "\nمبلغ: SR 15"))
        assertNull(IncomingTransferParser.parse(receipt.replace("130", "1.2345")))
        assertNull(IncomingTransferParser.parse(receipt.replace("SR", "USD")))
    }

    @Test
    fun groupingDecimalsBalanceAndMissingSenderAreHandledConservatively() {
        assertEquals(
            123450L,
            IncomingTransferParser.parse(receipt.replace("130", "1,234.50"))!!.amount,
        )
        assertEquals(13000L, IncomingTransferParser.parse(receipt + "\nالرصيد SR 9000")!!.amount)
        assertEquals(
            "",
            IncomingTransferParser.parse("حوالة واردة\nمبلغ: SAR 25\n2020-01-02 10:15")!!.sender,
        )
        assertNull(IncomingTransferParser.messageTime("20/13/40 10:90"))
        assertEquals(transferName("أحمد  علي"), transferName("احمد علي"))
    }
}
