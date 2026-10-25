package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Approve must land (Meka 2026-10-25 15:10: "I approved marking emails as read but my count hasn't changed"): two
 * accounts sharing a label each get their own cards, an unchanged unread count never goes stale, a tap on a card that was
 * on screen isn't refused for the count's age, and an approval the server never echoed is offered again with the reason.
 */
class ApproveLandsTest {
    private val now = 1_790_985_600_000L
    private val min = 60_000L
    private val day = 86_400_000L

    private fun plan(id: String, key: String?, name: String?, started: Long = now - day) = MailBacklog(
        id = id, account = "Personal", startedAtMs = started, scanned = 4_000, readyAtMs = now - 20 * 3_600_000L,
        counts = mapOf(MailBacklogKind.NEWSLETTER to 3_000, MailBacklogKind.JUNK to 256, MailBacklogKind.KEEP to 200), top = null,
        approvedAtMs = null, inboxUnread = 1_050, tidyUnread = 380, unreadCountedAtMs = now - 5 * min,
        accountKey = key, accountName = name,
    )

    private val outlook = plan("mbO", "maO", "Personal Outlook", started = now - 2 * day)
    private val gmail = plan("mbG", "maG", "Personal Gmail")

    @Test
    fun twoAccountsSharingALabelEachGetTheirOwnCards() {
        val ids = MailBacklogRules.cards(listOf(outlook, gmail), nowMs = now).map { it.id }
        assertEquals(listOf("mbO", "read:mbO", "mbG", "read:mbG"), ids)
        val titles = MailBacklogRules.cards(listOf(outlook, gmail), nowMs = now).map { it.title }
        assertEquals(listOf("Clean up Personal Outlook", "Mark 380 read · Personal Outlook", "Clean up Personal Gmail", "Mark 380 read · Personal Gmail"), titles)
        // Older backlogs without a key still group by label (the newest wins), named by the label.
        val old = MailBacklogRules.cards(listOf(outlook.copy(accountKey = null, accountName = null), gmail.copy(accountKey = null, accountName = null)), nowMs = now)
        assertEquals(listOf("mbG", "read:mbG"), old.map { it.id })
        assertEquals("Clean up Personal", old.first().title)
    }

    @Test
    fun theMailPaneAndHealthReadEachAccountsOwnBacklog() {
        val o = MailAccountFacts(MailProviders.OUTLOOK, "alex@example.com", "ok", now - 3 * min, "PERSONAL", inboxTotal = 12_437, inboxUnread = 156)
        val g = MailAccountFacts(MailProviders.GMAIL, "alex@example.org", "ok", now - 2 * min, "PERSONAL", inboxTotal = 9_000, inboxUnread = 1_050)
        val ok = MailAccountRules.key(o.provider, o.email)
        val gk = MailAccountRules.key(g.provider, g.email)
        val done = outlook.copy(accountKey = ok, approvedAtMs = now - day, approvalSeen = now - day, applied = 3_256, finishedAtMs = now - 3_600_000L, tidyUnread = 0)
        val waiting = gmail.copy(accountKey = gk)
        val rows = MailHealthRules.healthRows(listOf(o, g), listOf(done, waiting), emptyList(), now, LocalCalendar.UTC)
        val byTitle = rows.associate { it.title to it.line }
        assertTrue(byTitle.getValue("Mail · Personal Outlook").contains("clean-up 3,256 done"), byTitle.toString())
        assertTrue(byTitle.getValue("Mail · Personal Gmail").contains("1,050 inbox unread"), byTitle.toString())
        assertTrue(byTitle.getValue("Mail · Personal Gmail").contains("clean-up waiting for your Approve (3,256)"), byTitle.toString())
        assertTrue(byTitle.getValue("Mail · Personal Gmail").contains("mark read not asked (380 could be)"), byTitle.toString())
    }

    @Test
    fun anUnchangedCountIsStampedAgainBeforeItGoesStale() {
        val prev = plan("mbG", "maG", null).copy(inboxUnread = 1_050, tidyUnread = 380, unreadCountedAtMs = now)
        assertFalse(MailUnreadRules.writeDue(prev, 1_050, 380, now + 30 * min))
        assertTrue(MailUnreadRules.writeDue(prev, 1_050, 380, now + MailUnreadRules.RESTAMP_MS))
        assertTrue(MailUnreadRules.RESTAMP_MS < MailUnreadRules.STALE_MS)
    }

    @Test
    fun aTapIsntRefusedForTheCountsAgeButAFreshCardStillNeedsAFreshCount() {
        val stale = gmail.copy(unreadCountedAtMs = now - 7 * 3_600_000L)
        assertNull(MailMarkReadRules.card(stale, now))
        assertTrue(MailMarkReadRules.canApproveTap(stale, now))
        assertFalse(MailMarkReadRules.canApproveTap(stale.copy(tidyUnread = 2), now))
    }

    @Test
    fun anApproveTheServerNeverEchoedComesBackWithTheReason() {
        val asked = gmail.copy(approvedAtMs = now - 5 * min)
        // Just tapped: "on its way", no card.
        assertTrue(MailBacklogRules.unseen(asked))
        assertEquals(MailBacklogRules.SENDING_LINE, MailBacklogRules.row(asked, nowMs = now).line)
        assertNull(MailBacklogRules.planCard(asked, now))
        // The server echoed it: "Navilife has it".
        val seen = asked.copy(approvalSeen = asked.approvedAtMs)
        assertFalse(MailBacklogRules.unseen(seen))
        assertEquals(MailBacklogRules.APPROVED_LINE, MailBacklogRules.row(seen, nowMs = now).line)
        assertNull(MailBacklogRules.seenFields(seen.copy(markReadSeen = null)))
        // Never echoed after a few checks: the card comes back with why, and Mail offers Approve again.
        val later = now + MailBacklogRules.NOT_REACHED_MS + min
        val card = MailBacklogRules.planCard(asked, later)!!
        assertTrue(card.why.endsWith(MailBacklogRules.NOT_REACHED_WHY), card.why)
        assertEquals("mbG", MailBacklogRules.row(asked, nowMs = later).approveId)
        assertNull(MailBacklogRules.planCard(seen, later))
        // Once something moved it's never offered again.
        assertNull(MailBacklogRules.planCard(asked.copy(applied = 500), later))
    }

    @Test
    fun aMarkReadTheServerNeverEchoedComesBackToo() {
        val asked = gmail.copy(markReadApprovedAtMs = now - 5 * min)
        assertNull(MailMarkReadRules.card(asked, now))
        val later = now + MailBacklogRules.NOT_REACHED_MS + min
        val card = MailMarkReadRules.card(asked.copy(unreadCountedAtMs = later - min), later)!!
        assertTrue(card.why.startsWith(MailBacklogRules.NOT_REACHED_WHY_READ), card.why)
        assertNull(MailMarkReadRules.card(asked.copy(markReadSeen = asked.markReadApprovedAtMs, unreadCountedAtMs = later - min), later))
        assertNull(MailMarkReadRules.card(asked.copy(markedRead = 100, unreadCountedAtMs = later - min), later))
        assertEquals("mark read asked · not seen by Navilife yet", MailHealthRules.markReadState(asked))
        assertEquals("mark read running · 500 so far", MailHealthRules.markReadState(asked.copy(markReadSeen = asked.markReadApprovedAtMs, markedRead = 500)))
        assertEquals("mark read finished · 906 marked", MailHealthRules.markReadState(asked.copy(markedRead = 906, markReadFinishedAtMs = now)))
    }

    @Test
    fun theServersEchoAndNamesReadBack() {
        val asked = gmail.copy(approvedAtMs = now, markReadApprovedAtMs = now + 1)
        val echo = MailBacklogRules.seenFields(asked)!!
        assertEquals(FieldValue.Int64(now), echo[MailBacklogFields.APPROVAL_SEEN])
        assertEquals(FieldValue.Int64(now + 1), echo[MailBacklogFields.MARK_READ_SEEN])
        // A Cancel (approval 0) is echoed as 0 too.
        assertEquals(FieldValue.Int64(0), MailBacklogRules.seenFields(asked.copy(approvedAtMs = null, approvalSeen = now, markReadSeen = now + 1))!![MailBacklogFields.APPROVAL_SEEN])
        val read = MailBacklogRules.read("mbG", MailBacklogRules.fields("Personal", now - day, 0, emptyMap(), null, now - day) + echo + mapOf(
            MailBacklogFields.ACCOUNT_KEY to FieldValue.Text("maG"), MailBacklogFields.ACCOUNT_NAME to FieldValue.Text("Personal Gmail"),
        ))!!
        assertEquals("maG", read.accountKey)
        assertEquals("Personal Gmail", read.shownName)
        assertEquals(now, read.approvalSeen)
        assertEquals(now + 1, read.markReadSeen)
    }
}
