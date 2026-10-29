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
        assertEquals("Personal · 412 unread", l.text)
        // What "can tidy" means, as its own tappable line (a tap opens Mail's clean-up).
        assertEquals("Tidy 380 newsletters, promos and receipts ›", l.tidy)
        assertEquals("412 unread emails in Personal. 380 of them are newsletters, promos or receipts Navilife can tidy; double tap Tidy to see the clean-up.", l.spoken)
        assertFalse(l.lit)
        val five = MailUnreadRules.todayLine(listOf(b("Personal", 5)), now)!!
        assertEquals("Personal · 5 unread", five.text)
        assertNull(five.tidy)
        val one = MailUnreadRules.todayLine(listOf(b("Personal", 1204, 1)), now)!!
        assertEquals("Personal · 1,204 unread", one.text)
        assertEquals("Tidy 1 newsletter, promo or receipt ›", one.tidy)
    }

    @Test
    fun severalAccountsInLabelOrderWithTheTidyTotal() {
        val l = MailUnreadRules.todayLine(listOf(b("Outlook", 82, 40), b("Business", 5), b("Personal", 412, 380)), now)!!
        assertEquals("Unread mail · Personal 412 · Business 5 · Outlook 82", l.text)
        assertEquals("Tidy 420 newsletters, promos and receipts ›", l.tidy)
        assertEquals("Unread mail: 412 in Personal, 5 in Business and 82 in Outlook. 420 of them are newsletters, promos or receipts Navilife can tidy; double tap Tidy to see the clean-up.", l.spoken)
        // An account with nothing unread is left out; with one left it reads as one.
        assertEquals("Outlook · 82 unread", MailUnreadRules.todayLine(listOf(b("Outlook", 82, 40), b("Personal", 0)), now)!!.text)
    }

    @Test
    fun nothingCountedStaleOrNothingUnread() {
        assertNull(MailUnreadRules.todayLine(emptyList(), now))
        assertNull(MailUnreadRules.todayLine(listOf(b("Personal", null)), now))
        assertNull(MailUnreadRules.todayLine(listOf(b("Personal", 40, at = now - 7 * 3_600_000L)), now))
        assertEquals(MailUnreadRules.NONE_UNREAD, MailUnreadRules.todayLine(listOf(b("Personal", 0), b("Outlook", 0)), now)!!.text)
        // A tidy count above the unread count never shows more than is unread.
        assertEquals("Tidy 3 newsletters, promos and receipts ›", MailUnreadRules.todayLine(listOf(b("Personal", 3, 9)), now)!!.tidy)
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
        // From the raw fields, before the backlog's own plan fields exist.
        assertTrue(MailUnreadRules.writeDueFields(null, 10, 0, now))
        val raw = MailUnreadRules.fields(412, 380, now - 5 * min)
        assertFalse(MailUnreadRules.writeDueFields(raw, 415, 380, now))
        assertTrue(MailUnreadRules.writeDueFields(raw, 380, 380, now))
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

    @Test
    fun twoAccountsSharingALabelAreNamedByProvider() {
        // Meka 2026-10-24 14:47: "Personal 154 · Personal 1,048 · Business 28" (Outlook and Gmail both labelled Personal).
        fun acct(provider: String, email: String, label: String, unread: Long) = MailAccountFacts(
            provider = provider, email = email, status = "ok", lastSyncAtMs = now - min, label = label, inboxUnread = unread,
        )
        val outlook = acct(MailProviders.OUTLOOK, "alex@example.net", "PERSONAL", 154)
        val gmail = acct(MailProviders.GMAIL, "alex@example.com", "PERSONAL", 1_048)
        val work = acct(MailProviders.GMAIL, "alex@oakfield.example", "BUSINESS", 28)
        fun keyed(a: MailAccountFacts, label: String, tidy: Int) =
            b(label, a.inboxUnread!!.toInt(), tidy).copy(id = "mb-${a.email}", accountKey = MailAccountRules.key(a.provider, a.email))
        val l = MailUnreadRules.todayLine(
            listOf(keyed(outlook, "Personal", 100), keyed(gmail, "Personal", 900), keyed(work, "Business", 3)), now, listOf(outlook, gmail, work),
        )!!
        assertEquals("Unread mail · Gmail 1,048 · Outlook 154 · Business 28", l.text)
        assertEquals("Tidy 1,003 newsletters, promos and receipts ›", l.tidy)
        assertTrue(l.spoken.startsWith("Unread mail: 1,048 in Personal Gmail, 154 in Personal Outlook and 28 in Business Gmail."))
        // Labels that differ stay short; two Gmails sharing a label take their full names, never the same word twice.
        assertEquals(listOf("Personal", "Business"),
            MailUnreadRules.shortNames(listOf(Triple("Personal", MailProviders.GMAIL, "Personal Gmail"), Triple("Business", MailProviders.GMAIL, "Business Gmail"))))
        assertEquals(listOf("Gmail one", "Gmail two"),
            MailUnreadRules.shortNames(listOf(Triple("Personal", MailProviders.GMAIL, "Gmail one"), Triple("Personal", MailProviders.GMAIL, "Gmail two"))))
        // An older server without the account list: the server's own names.
        assertEquals(listOf("Personal Outlook", "Personal Gmail"),
            MailUnreadRules.shortNames(listOf(Triple("Personal", null, "Personal Outlook"), Triple("Personal", null, "Personal Gmail"))))
    }
}
