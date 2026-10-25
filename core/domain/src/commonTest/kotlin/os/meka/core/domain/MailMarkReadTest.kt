package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailMarkReadTest {
    private val now = 1_790_985_600_000L
    private val min = 60_000L
    private val day = 86_400_000L

    private fun b(tidy: Int, at: Long? = now - 5 * min) = MailBacklog(
        id = "mbP", account = "Personal", startedAtMs = now - day, scanned = 0, readyAtMs = null,
        counts = emptyMap(), top = null, approvedAtMs = null,
        inboxUnread = 412, tidyUnread = tidy, unreadCountedAtMs = at,
    )

    @Test
    fun offeredOncePerAccountWithEnoughUnreadTidyKind() {
        val card = MailMarkReadRules.card(b(380), now)!!
        assertEquals("read:mbP", card.id)
        assertEquals("Mark 380 read · Personal", card.title)
        assertEquals("380 unread newsletters, promos and receipts in your inbox · they stay where they are, just marked read · people, banks, schools and anything needing you stay unread", card.why)
        assertEquals("Mark read", card.yesLabel)
        assertEquals(DecisionEffect.APPROVE_BACKLOG, card.yes)
        assertEquals(DecisionEffect.OPEN_REVIEW, card.open)
        // Too few, never counted, or a stale count: no card.
        assertNull(MailMarkReadRules.card(b(MailMarkReadRules.MIN - 1), now))
        assertNull(MailMarkReadRules.card(b(380, at = null), now))
        assertNull(MailMarkReadRules.card(b(380, at = now - 7 * 3_600_000L), now))
    }

    @Test
    fun noCardWhileRunningAndOnlyAWeekAfterItFinished() {
        val asked = b(380).copy(markReadApprovedAtMs = now - min)
        assertTrue(MailMarkReadRules.running(asked))
        assertNull(MailMarkReadRules.card(asked, now))
        assertTrue(MailMarkReadRules.shouldMark(asked, paused = false))
        assertFalse(MailMarkReadRules.shouldMark(asked, paused = true))
        val finished = asked.copy(markedRead = 380, markReadFinishedAtMs = now)
        assertFalse(MailMarkReadRules.running(finished))
        assertFalse(MailMarkReadRules.shouldMark(finished, paused = false))
        assertNull(MailMarkReadRules.card(finished.copy(tidyUnread = 40), now + day))
        // A week on, with more built up again, it's offered once more.
        val later = finished.copy(tidyUnread = 40, unreadCountedAtMs = now + 8 * day)
        assertEquals("Mark 40 read · Personal", MailMarkReadRules.card(later, now + 8 * day)!!.title)
    }

    @Test
    fun undoOnlyWhileNothingIsMarkedAndTheFieldsStartAFreshRun() {
        val asked = b(380).copy(markReadApprovedAtMs = now)
        assertTrue(MailMarkReadRules.canUnapprove(asked))
        assertFalse(MailMarkReadRules.canUnapprove(asked.copy(markedRead = 12)))
        assertFalse(MailMarkReadRules.canUnapprove(b(380)))
        val f = MailMarkReadRules.approveFields(now)
        val read = MailBacklogRules.read("mbP", MailBacklogRules.fields("Personal", now - day, 0, emptyMap(), null, null) + f)!!
        assertEquals(now, read.markReadApprovedAtMs)
        assertEquals(0, read.markedRead)
        assertNull(read.markReadFinishedAtMs)
        val progressed = MailBacklogRules.read("mbP", MailBacklogRules.fields("Personal", now - day, 0, emptyMap(), null, null) + f +
            MailMarkReadRules.progressFields(500, null))!!
        assertEquals(500, progressed.markedRead)
        assertTrue(MailMarkReadRules.running(progressed))
    }

    @Test
    fun mailSaysWhatItDidUnderTheCleanUp() {
        assertNull(MailMarkReadRules.line(b(380)))
        val asked = b(380).copy(markReadApprovedAtMs = now)
        assertEquals("Mark read · on its way to Navilife…", MailMarkReadRules.line(asked))
        assertEquals("Mark read · Navilife has it · marking from its next check…", MailMarkReadRules.line(asked.copy(markReadSeen = now)))
        assertEquals("Marking newsletters and promos read · 120 so far…", MailMarkReadRules.line(asked.copy(markedRead = 120)))
        val done = asked.copy(markedRead = 1380, markReadFinishedAtMs = now + min)
        assertEquals("Marked 1,380 newsletters and promos read · they stay in the inbox", MailMarkReadRules.line(done))
        val row = MailBacklogRules.row(done)
        assertTrue(row.detail!!.endsWith("Marked 1,380 newsletters and promos read · they stay in the inbox"))
    }

    @Test
    fun theCardSitsAfterTheCleanUpAndBeforeEmptyJunk() {
        val withJunk = b(380).copy(junkFolder = 322, junkCountedAtMs = now)
        assertEquals(listOf("read:mbP", "junk:mbP"), MailBacklogRules.cards(listOf(withJunk), nowMs = now).map { it.id })
        assertTrue(MailBacklogRules.cards(listOf(withJunk), paused = true, nowMs = now).isEmpty())
    }
}
