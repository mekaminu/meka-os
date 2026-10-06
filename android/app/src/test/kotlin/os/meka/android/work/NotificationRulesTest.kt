package os.meka.android.work

import os.meka.core.domain.CaptureApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationRulesTest {
    @Test
    fun onlyWhatsAppTextsAndMissedCallsAreRead() {
        assertEquals(CaptureApp.WHATSAPP, NotificationRules.appFor("com.whatsapp", "msg", null))
        assertEquals(CaptureApp.SMS, NotificationRules.appFor("com.samsung.android.messaging", "msg", null))
        assertEquals(CaptureApp.SMS, NotificationRules.appFor("org.example.sms", "msg", "org.example.sms"))
        assertEquals(CaptureApp.PHONE, NotificationRules.appFor("com.samsung.android.dialer", "missed_call", null))
        assertNull(NotificationRules.appFor("com.samsung.android.dialer", "call", null)) // an ongoing call
        assertNull(NotificationRules.appFor("com.google.android.gm", "email", null))
        assertNull(NotificationRules.appFor("com.whatsapp.fake", "msg", null))
    }

    @Test
    fun missedCallsNameTheCallerEitherWay() {
        assertEquals("Ada Okafor", NotificationRules.missedCallPerson("Missed call", "Ada Okafor"))
        assertEquals("07700 900123", NotificationRules.missedCallPerson("07700 900123", "Missed voice call"))
        assertNull(NotificationRules.missedCallPerson("Missed calls", "2 missed calls"))
        assertTrue(NotificationRules.looksLikeMissedCall(null, "Missed video call"))
        assertFalse(NotificationRules.looksLikeMissedCall(null, "Ongoing call"))
    }

    @Test
    fun summariesWithoutAPersonAreSkipped() {
        assertTrue(NotificationRules.isSummaryOnly("WhatsApp", "5 new messages from 2 chats"))
        assertTrue(NotificationRules.isSummaryOnly("Tom", "3 new messages"))
        assertFalse(NotificationRules.isSummaryOnly("Tom", "Running late"))
    }

    @Test
    fun groupTitlesSplitIntoGroupAndSender() {
        assertEquals("Five-a-side" to "Tom", NotificationRules.splitGroupTitle("Five-a-side: Tom"))
        assertEquals(null to "Tom", NotificationRules.splitGroupTitle("Tom"))
    }
}
