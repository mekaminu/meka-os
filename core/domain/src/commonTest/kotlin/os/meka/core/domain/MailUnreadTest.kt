package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailUnreadTest {
    private val now = 1_790_985_600_000L
    private val min = 60_000L

    private fun b(account: String, unread: Int?, tidy: Int = 0, at: Long? = now - 5 * min) = MailBacklog(
        id = "mb-$account", account = account, startedAtMs = now - 86_400_000L, scanned = 0, readyAtMs = null,
        counts = emptyMap(), top = null, approvedAtMs = null,
        inboxUnread = unread, tidyUnread = tidy, unreadCountedAtMs = at,
    )

    @Test
    fun oneAccountSaysItsUnreadAndWhatCanBeTidied() {
        val l = MailUnreadRules.todayLine(listOf(b("Personal", 412, 380)), now)!!
        assertEquals("Personal · 412 unread · 380 are newsletters, promos or receipts Navilife can tidy", l.text)
        assertEquals("412 unread emails in Personal; 380 of them are newsletters, promos or receipts Navilife can tidy.", l.spoken)
        assertFalse(l.lit)
        assertEquals("Personal · 5 unread", MailUnreadRules.todayLine(listOf(b("Personal", 5)), now)!!.text)
        assertEquals("Personal · 1,204 unread · 1 is a newsletter, promo or receipt Navilife can tidy",
            MailUnreadRules.todayLine(listOf(b("Personal", 1204, 1)), now)!!.text)
    }

    @Test
    fun severalAccountsInLabelOrderWithTheTidyTotal() {
        val l = MailUnreadRules.todayLine(listOf(b("Outlook", 82, 40), b("Business", 5), b("Personal", 412, 380)), now)!!
        assertEquals("Unread mail · Personal 412 · Business 5 · Outlook 82 · 420 Navilife can tidy", l.text)
        assertEquals("Unread mail: 412 in Personal, 5 in Business and 82 in Outlook; 420 of them are newsletters, promos or receipts Navilife can tidy.", l.spoken)
        // An account with nothing unread is left out; with one left it reads as one.
        assertEquals("Outlook · 82 unread · 40 are newsletters, promos or receipts Navilife can tidy",
            MailUnreadRules.todayLine(listOf(b("Outlook", 82, 40), b("Personal", 0)), now)!!.text)
    }

    @Test
    fun nothingCountedStaleOrNothingUnread() {
        assertNull(MailUnreadRules.todayLine(emptyList(), now))
        assertNull(MailUnreadRules.todayLine(listOf(b("Personal", null)), now))
        assertNull(MailUnreadRules.todayLine(listOf(b("Personal", 40, at = now - 7 * 3_600_000L)), now))
        assertEquals(MailUnreadRules.NONE_UNREAD, MailUnreadRules.todayLine(listOf(b("Personal", 0), b("Outlook", 0)), now)!!.text)
        // A tidy count above the unread count never shows more than is unread.
        assertEquals("Personal · 3 unread · 3 are newsletters, promos or receipts Navilife can tidy",
            MailUnreadRules.todayLine(listOf(b("Personal", 3, 9)), now)!!.text)
    }

    @Test
    fun theServerWritesOnlyWhenItsWorthASync() {
        assertTrue(MailUnreadRules.writeDue(null, 10, 0, now))
        assertTrue(MailUnreadRules.writeDue(b("Personal", null), 10, 0, now))
        val prev = b("Personal", 412, 380, at = now - 5 * min)
        assertFalse(MailUnreadRules.writeDue(prev, 412, 380, now))
        assertFalse(MailUnreadRules.writeDue(prev, 415, 380, now))
        assertTrue(MailUnreadRules.writeDue(prev, 402, 380, now))
        assertTrue(MailUnreadRules.writeDue(prev, 412, 370, now))
        assertTrue(MailUnreadRules.writeDue(prev, 415, 380, now + 30 * min))
        assertTrue(MailUnreadRules.writeDue(b("Personal", 3, at = now - min), 0, 0, now))
    }

    @Test
    fun theFieldsReadBackOnTheBacklogEntity() {
        val f = MailBacklogRules.fields("Personal", now, 0, emptyMap(), null, null) + MailUnreadRules.fields(412, 500, now)
        assertEquals(FieldValue.Int64(412), f[MailBacklogFields.TIDY_UNREAD])
        val read = MailBacklogRules.read("mb1", f)!!
        assertEquals(412, read.inboxUnread)
        assertEquals(412, read.tidyUnread)
        assertEquals(now, read.unreadCountedAtMs)
        assertNull(MailBacklogRules.read("mb1", MailBacklogRules.fields("Personal", now, 0, emptyMap(), null, null))!!.inboxUnread)
    }
}
