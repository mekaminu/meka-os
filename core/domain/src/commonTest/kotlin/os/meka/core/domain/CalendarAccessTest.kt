package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarAccessTest {
    @Test
    fun onlyRealCalendarAccountsOfferEditingAndFeedsSayNothing() {
        assertTrue(CalendarAccessRules.offersEditing("google"))
        assertTrue(CalendarAccessRules.offersEditing("microsoft"))
        for (feed in listOf("fixtures", "news", "bank_holidays", "")) {
            assertFalse(CalendarAccessRules.offersEditing(feed))
            assertNull(CalendarAccessRules.line(feed, canEdit = true))
            assertNull(CalendarAccessRules.action(feed, canEdit = false))
        }
    }

    @Test
    fun aReadOnlyAccountOffersAllowEditingAndAnEditableOneStopEditing() {
        assertEquals("Read-only · MEKA only reads this calendar", CalendarAccessRules.line("google", canEdit = false))
        assertEquals(CalendarAccessAction.ALLOW_EDITING, CalendarAccessRules.action("google", canEdit = false))
        assertEquals("Allow editing", CalendarAccessAction.ALLOW_EDITING.label)
        assertEquals("Editing allowed · events you add or change in MEKA go to Outlook", CalendarAccessRules.line("microsoft", canEdit = true))
        assertEquals(CalendarAccessAction.STOP_EDITING, CalendarAccessRules.action("microsoft", canEdit = true))
        assertEquals("Stop editing", CalendarAccessAction.STOP_EDITING.label)
        assertEquals("Editing off · MEKA only reads Google now", CalendarAccessRules.stoppedLine("google"))
    }

    @Test
    fun anExpiredAccountOnlyOffersReconnectWhichKeepsWhatItHad() {
        assertNull(CalendarAccessRules.action("google", canEdit = true, needsReconnect = true))
        assertNull(CalendarAccessRules.line("google", canEdit = false, needsReconnect = true))
        assertTrue(CalendarAccessRules.reconnectAsksEditing(canEdit = true))
        assertFalse(CalendarAccessRules.reconnectAsksEditing(canEdit = false))
    }

    @Test
    fun theHeaderStopsSayingReadOnlyOnceAnAccountMayEdit() {
        assertEquals("Read-only. Events appear in Today on all your devices.", CalendarAccessRules.header(anyEditable = false))
        assertTrue(CalendarAccessRules.header(anyEditable = true).contains("Editing is on only where you allowed it"))
        assertTrue(CalendarAccessRules.allowNote("google").startsWith("Google will ask"))
    }
}
