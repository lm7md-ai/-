package sa.rihla.sms

import android.app.Application
import android.app.Notification
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SmsNotificationTest {
    @Test
    fun emptyMessagingArrayFallsBackToFullTextAndInboxLinesAreSupported() {
        val notification = Notification()
        notification.`when` = 0
        notification.extras = Bundle().apply {
            putParcelableArray(Notification.EXTRA_MESSAGES, emptyArray<Bundle>())
            putCharSequence(Notification.EXTRA_BIG_TEXT, "")
            putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("شراء PoS", "بـSR 32", "لـEXPRESS FOOD"))
        }
        val extracted = smsNotificationTexts(notification, 3000).single()
        assertEquals(3000L, extracted.receivedAt)
        assertEquals(sa.rihla.core.SmsPurchase(3200, "EXPRESS FOOD"), sa.rihla.core.PurchaseSmsParser.parse(extracted.body))
    }
    @Test
    @Config(sdk = [26])
    fun androidEightPermissionCheckRequiresThisExactListenerComponent() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val component =
            android.content.ComponentName(context, PurchaseNotificationService::class.java)
        android.provider.Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            component.flattenToString() + "Other",
        )
        assertFalse(PurchaseNotificationService.allowed(context))
        android.provider.Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            component.flattenToString(),
        )
        assertTrue(PurchaseNotificationService.allowed(context))
    }

    @Test
    fun messagingStyleExtractsEachMessageWithItsOwnTimestamp() {
        val notification = Notification()
        notification.extras =
            Bundle().apply {
                putParcelableArray(
                    Notification.EXTRA_MESSAGES,
                    arrayOf(
                        Bundle().apply {
                            putCharSequence("text", "شراء مبلغ 10 SAR")
                            putLong("time", 1000)
                        },
                        Bundle().apply {
                            putCharSequence("text", "شراء مبلغ 20 SAR")
                            putLong("time", 2000)
                        },
                    ),
                )
                putCharSequence(Notification.EXTRA_BIG_TEXT, "شراء مبلغ 10 SAR شراء مبلغ 20 SAR")
            }
        assertEquals(
            listOf(
                SmsNotificationText("شراء مبلغ 10 SAR", 1000),
                SmsNotificationText("شراء مبلغ 20 SAR", 2000),
            ),
            smsNotificationTexts(notification, 3000),
        )
    }

    @Test
    fun stableMessageTimestampSurvivesRepostedNotification() {
        val notification = Notification()
        notification.`when` = 1000
        notification.extras =
            Bundle().apply { putCharSequence(Notification.EXTRA_TEXT, "شراء مبلغ 20 SAR") }
        assertEquals(
            smsNotificationTexts(notification, 2000),
            smsNotificationTexts(notification, 3000),
        )
        notification.flags = Notification.FLAG_GROUP_SUMMARY
        assertTrue(smsNotificationTexts(notification, 3000).isEmpty())
    }

    @Test
    fun hiddenMessageIsIgnoredAndInvalidTimestampUsesArrivalTime() {
        val notification = Notification()
        notification.extras = Bundle()
        assertTrue(smsNotificationTexts(notification, 3000).isEmpty())
        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "شراء مبلغ 20 SAR")
        notification.`when` = 99999
        assertEquals(3000L, smsNotificationTexts(notification, 3000).single().receivedAt)
    }
}
