package sa.rihla.data

import android.app.Application
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sa.rihla.core.*
import sa.rihla.sms.TransferArchive

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TransferStoreTest {
    private val text =
        "حوالة داخلية واردة بـSR 130\nلـ1111\nمن2222; سالم عبدالله مثال\n20/10/22 10:18"

    @Test
    fun rescansAreIdempotentUncertainDuplicatesRequireReviewAndDifferentSendersStaySeparate() {
        val c = RuntimeEnvironment.getApplication()
        val store = TransferStore(c)
        try {
            store.configure(setOf("سالم عبدالله مثال"), 1603351090000, true)
            assertTrue(store.import(text, 1603351080000, "inbox:1"))
            assertFalse(store.import(text, 1603351080000, "inbox:1"))
            assertTrue(store.import(text, 1603351085000, "sms:1"))
            assertTrue(store.rows().any { it.duplicate })
            val value = transferTotals(store.rows(), store.aliases, store.cutoff)
            assertEquals(13000L, value.all)
            assertEquals(13000L, value.old)
            assertEquals(0L, value.fresh)
            val dupe = store.rows().first { it.duplicate }
            store.decide(dupe.id, 1)
            assertEquals(26000L, transferTotals(store.rows(), store.aliases, store.cutoff).all)
            store.decide(dupe.id, -1)
            assertTrue(
                store.import(text.replace("سالم عبدالله مثال", "شخص آخر"), 1603351080000, "inbox:2")
            )
            assertEquals(13000L, transferTotals(store.rows(), store.aliases, store.cutoff).all)
        } finally {
            store.close()
        }
    }

    @Test
    fun originalSmsTimeSeparatesOldAndNewEvenWithinOneBankTimestampMinute() {
        val bankTime = 1603351080000L
        val cutoff = bankTime + 30000
        val rows =
            listOf(
                TransferRow(
                    "a",
                    100,
                    "اسم والد",
                    bankTime,
                    "h",
                    1,
                    false,
                    "sms:1",
                    bankTime + 10000,
                ),
                TransferRow(
                    "b",
                    200,
                    "اسم والد",
                    bankTime,
                    "h2",
                    1,
                    false,
                    "sms:2",
                    bankTime + 40000,
                ),
            )
        val value = transferTotals(rows, emptySet(), cutoff)
        assertEquals(100L, value.old)
        assertEquals(200L, value.fresh)
        assertEquals(300L, value.all)
    }

    @Test
    fun privateBackupRestoresDecisionsAndRepeatedRestoreDoesNotDuplicate() {
        val c = RuntimeEnvironment.getApplication()
        var json = ""
        TransferStore(c).use { store ->
            store.configure(setOf("سالم عبدالله مثال"), 1603351090000, true)
            store.import(text, 1603351080000, "inbox:1")
            val id = store.rows().single().id
            store.decide(id, 1)
            json = store.export()
        }
        c.deleteDatabase("incoming_transfers.db")
        c.getSharedPreferences("father_transfers", 0).edit().clear().commit()
        TransferStore(c).use { store ->
            assertEquals(1, store.restore(json))
            assertEquals(1, store.rows().single().decision)
            assertEquals(0, store.restore(json))
            assertEquals(13000L, transferTotals(store.rows(), store.aliases, store.cutoff).all)
            val invalid = JSONObject(json)
            invalid.getJSONArray("items").getJSONObject(0).put("amount", -1)
            try {
                store.restore(invalid.toString())
                fail("Invalid amount accepted")
            } catch (_: IllegalArgumentException) {}
            assertEquals(1, store.rows().size)
        }
    }

    @Test
    fun xmlReadsOnlyIncomingAndRejectsDtdAndOversizedBackupText() = runBlocking {
        val store = TransferStore(RuntimeEnvironment.getApplication())
        try {
            val escaped = text.replace("&", "&amp;").replace("\n", "&#10;")
            val xml =
                "<smses><sms type=\"1\" date=\"1603351080000\" body=\"$escaped\"/><sms type=\"2\" date=\"1603351080000\" body=\"$escaped\"/></smses>"
            assertEquals(
                1,
                TransferArchive.xml(ByteArrayInputStream(xml.toByteArray()), store).second,
            )
            assertEquals(
                0,
                TransferArchive.xml(ByteArrayInputStream(xml.toByteArray()), store).second,
            )
            try {
                TransferArchive.xml(
                    ByteArrayInputStream(
                        "<!DOCTYPE x [<!ENTITY y SYSTEM 'file:///not-readable'>]><smses/>"
                            .toByteArray()
                    ),
                    store,
                )
                fail("DTD accepted")
            } catch (_: IllegalArgumentException) {}
            try {
                readTransferBackup(ByteArrayInputStream(ByteArray(20)), 10)
                fail("Oversized input accepted")
            } catch (_: IllegalArgumentException) {}
        } finally {
            store.close()
        }
    }

    @Test
    fun restoringAnotherSourceQuarantinesMatchingAutomaticRowsWithoutChangingDecisions() {
        val context = RuntimeEnvironment.getApplication()
        var backup = ""
        TransferStore(context).use { store ->
            store.configure(setOf("سالم عبدالله مثال"), 1603351090000, true)
            store.import(text, 1603351080000, "xml:1")
            backup = store.export()
        }
        context.deleteDatabase("incoming_transfers.db")
        TransferStore(context).use { store ->
            store.import(text, 1603351080000, "inbox:1")
            assertEquals(1, store.restore(backup))
            assertEquals(2, store.rows().size)
            assertEquals(13000L, transferTotals(store.rows(), store.aliases, store.cutoff).all)
            assertTrue(store.rows().first { it.origin == "xml:1" }.duplicate)
            assertEquals(0, store.restore(backup))
            store.decide(store.rows().first { it.origin == "xml:1" }.id, 1)
            assertEquals(26000L, transferTotals(store.rows(), store.aliases, store.cutoff).all)
        }
    }

    @Test
    fun fractionalFinancialValuesAndFutureOriginalTimestampsAreRejectedAtomically() {
        val context = RuntimeEnvironment.getApplication()
        TransferStore(context).use { store ->
            store.configure(setOf("سالم عبدالله مثال"), 1603351090000, true)
            store.import(text, 1603351080000, "inbox:1")
            val backup = store.export()
            for ((key, value) in
                listOf(
                    "amount" to 13000.5,
                    "decision" to 1.5,
                    "receivedAt" to System.currentTimeMillis() + 86400000L,
                )) {
                val bad = JSONObject(backup)
                bad.getJSONArray("items").getJSONObject(0).put(key, value)
                assertThrows(Exception::class.java) { store.restore(bad.toString()) }
                assertEquals(1, store.rows().size)
                assertEquals(0, store.rows().single().decision)
            }
        }
    }
}
