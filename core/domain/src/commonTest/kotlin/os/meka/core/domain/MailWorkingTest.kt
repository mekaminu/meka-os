package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailWorkingTest {
    private val ready = 1_000_000L
    private val now = 50_000_000L
    private val min = 60_000L

    private fun backlog(
        id: String = "mb1", key: String? = "gmail:alex", name: String? = "Personal Gmail",
        approvedAt: Long? = null, applied: Int = 0, finishedAt: Long? = null, approvalSeen: Long? = null,
        readAskedAt: Long? = null, marked: Int = 0, readDone: Long? = null, readSeen: Long? = null,
    ) = MailBacklog(
        id = id, account = "Personal", startedAtMs = 1L, scanned = 3_300, readyAtMs = ready,
        counts = mapOf(MailBacklogKind.NEWSLETTER to 3_000, MailBacklogKind.JUNK to 256, MailBacklogKind.KEEP to 40),
        top = null, approvedAtMs = approvedAt, applied = applied, finishedAtMs = finishedAt,
        markReadApprovedAtMs = readAskedAt, markedRead = marked, markReadFinishedAtMs = readDone,
        accountKey = key, accountName = name, approvalSeen = approvalSeen, markReadSeen = readSeen,
    )

    @Test
    fun nothingAskedMeansNothingWorking() {
        assertTrue(MailWorkingRules.lines(listOf(backlog()), nowMs = now).isEmpty())
    }

    @Test
    fun markReadCountsUpFromSentToNavilifeHasItToSoFar() {
        val asked = now - 2 * min
        val sent = MailWorkingRules.lines(listOf(backlog(readAskedAt = asked)), nowMs = now).single()
        assertEquals("Marking read · Personal Gmail", sent.title)
        assertEquals(MailWorkingRules.SENDING, sent.meta)
        val seen = MailWorkingRules.lines(listOf(backlog(readAskedAt = asked, readSeen = asked)), nowMs = now).single()
        assertEquals(MailWorkingRules.HAS_IT, seen.meta)
        val going = MailWorkingRules.lines(listOf(backlog(readAskedAt = asked, readSeen = asked, marked = 500)), nowMs = now).single()
        assertEquals("500 so far · ${MailMarkReadRules.KEEPS_UNREAD}", going.meta)
    }

    @Test
    fun aFinishedMarkReadStaysSixHoursThenGoes() {
        val asked = now - 3 * 60 * min
        val done = MailWorkingRules.lines(listOf(backlog(readAskedAt = asked, readSeen = asked, marked = 1_050, readDone = now - 60 * min)), nowMs = now).single()
        assertEquals("Marked read · Personal Gmail", done.title)
        assertEquals("1,050 newsletters and promos · they stay in the inbox", done.meta)
        val old = backlog(readAskedAt = asked - 600 * min, readSeen = asked, marked = 1_050, readDone = now - MailWorkingRules.SHOW_DONE_MS - 1)
        assertTrue(MailWorkingRules.lines(listOf(old), nowMs = now).isEmpty())
    }

    @Test
    fun aLostApprovalIsLeftToItsCardWithTheReason() {
        val asked = now - MailBacklogRules.NOT_REACHED_MS - min
        assertTrue(MailWorkingRules.lines(listOf(backlog(readAskedAt = asked)), nowMs = now).isEmpty())
        assertTrue(MailWorkingRules.lines(listOf(backlog(approvedAt = asked)), nowMs = now).isEmpty())
    }

    @Test
    fun theCleanUpCountsMovedOfTotalThenSaysCleanedUp() {
        val at = now - 5 * min
        val going = MailWorkingRules.lines(listOf(backlog(approvedAt = at, approvalSeen = at, applied = 1_200)), nowMs = now).single()
        assertEquals("Cleaning up · Personal Gmail", going.title)
        assertEquals("1,200 of 3,256 moved…", going.meta)
        val done = MailWorkingRules.lines(listOf(backlog(approvedAt = at, approvalSeen = at, applied = 3_256, finishedAt = now - min)), nowMs = now).single()
        assertEquals("Cleaned up · Personal Gmail", done.title)
        assertTrue(done.meta.startsWith("3,256 older emails moved"))
    }

    @Test
    fun aPutBackCleanUpAndPauseAreSaid() {
        val at = now - 5 * min
        val b = backlog(approvedAt = at, approvalSeen = at, applied = 1_200)
        val undo = MailTidyBatch("mt1", at, "Personal", "", null, 1_200, undoAskedAtMs = now - min, undoneAtMs = null, undoNote = null,
            backlog = true, accountKey = "gmail:alex")
        assertTrue(MailWorkingRules.lines(listOf(b), listOf(undo), nowMs = now).isEmpty())
        assertEquals(MailWorkingRules.PAUSED, MailWorkingRules.lines(listOf(b), paused = true, nowMs = now).single().meta)
    }

    @Test
    fun twoAccountsSharingALabelEachGetTheirLines() {
        val at = now - 2 * min
        val gmail = backlog(readAskedAt = at, readSeen = at, marked = 500)
        val outlook = backlog(id = "mb2", key = "outlook:alex", name = "Outlook", readAskedAt = at, readSeen = at, marked = 90)
        val lines = MailWorkingRules.lines(listOf(gmail, outlook), nowMs = now)
        assertEquals(listOf("Marking read · Personal Gmail", "Marking read · Outlook"), lines.map { it.title })
        assertEquals(2, lines.map { it.id }.toSet().size)
    }

    @Test
    fun theStackCarriesTheLinesThroughPlanWeek() {
        val line = MeanwhileLine("w", "Marking read · Outlook", "90 so far")
        val stack = NeedsYouStack(emptyList(), listOf(line))
        assertEquals(listOf(line), NeedsYouStackRules.withPlanWeek(stack, null).working)
        assertNull(NeedsYouStack.EMPTY.working.firstOrNull())
    }
}
