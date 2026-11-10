package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The approved Needs you look, slice 2: MAIL NAVILIFE TIDIED TODAY as four tiles and one unsubscribe question. */
class NeedsYouMailTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_000L
    private fun at(minute: Int, d: Long = day) = cal.toEpochMs(d, minute)
    private fun batch(
        id: String, atMs: Long, kinds: Map<MailTidy, Int>, asked: Long? = null, backlog: Boolean = false,
    ): MailTidyBatch {
        val (summary, detail) = MailAutopilotRules.lines(kinds, "Personal")
        return MailTidyBatch(id, atMs, "Personal", summary, detail, kinds.values.sum(), asked, null, null, kinds, backlog = backlog)
    }
    private fun offer(n: Int) = MailUnopenedCard(
        title = MailUnopenedRules.TITLE, headline = "Unsubscribe from $n?", unsubscribeLabel = "Unsubscribe from $n", note = "",
        rows = (1..n).map { MailUnopenedRow("s$it", "Kids Club $it", "Personal · 5 emails, none opened · last Mon 12 Oct") },
        moreRows = emptyList(), showAllLabel = null, moreLine = null, ids = (1..n).map { "s$it" }, spoken = "",
    )

    @Test
    fun fourTilesOverTodaysStandingTidiesOnly() {
        val tidies = listOf(
            batch("y", at(20 * 60, day - 1), mapOf(MailTidy.NEWSLETTER to 50)),
            batch("a", at(8 * 60), mapOf(MailTidy.NEWSLETTER to 20, MailTidy.ARCHIVE to 40, MailTidy.RECEIPT to 4)),
            batch("b", at(9 * 60), mapOf(MailTidy.NEWSLETTER to 13, MailTidy.ARCHIVE to 31)),
            batch("j", at(10 * 60), mapOf(MailTidy.JUNK_TRASH to 20, MailTidy.SCAM_JUNK to 3, MailTidy.TRASH to 7)),
            batch("p", at(10 * 60), mapOf(MailTidy.ARCHIVE to 9), asked = at(10 * 60 + 5)),
            batch("k", at(7 * 60), mapOf(MailTidy.ARCHIVE to 900), backlog = true),
        )
        val tiles = NeedsYouMailRules.tiles(NeedsYouMailRules.today(tidies, at(12 * 60), cal))
        assertEquals(listOf("138", "33", "71", "30"), tiles.map { it.number })
        assertEquals(listOf("tidied", "newsletters", "promos", "junk & scams"), tiles.map { it.label })
        assertEquals(listOf(false, false, false, true), tiles.map { it.warn })
    }

    @Test
    fun aTidyLaterTodayWaitsAndNothingTodayMeansNoTiles() {
        val tidies = listOf(batch("a", at(14 * 60), mapOf(MailTidy.NEWSLETTER to 2)))
        assertTrue(NeedsYouMailRules.tiles(NeedsYouMailRules.today(tidies, at(12 * 60), cal)).isEmpty())
        assertNull(NeedsYouMailRules.summary(tidies, null, at(12 * 60), cal))
    }

    @Test
    fun theOneQuestionIsTheBusiestSenderWithHowManyWait() {
        val q = assertNotNull(NeedsYouMailRules.question(offer(11)))
        assertEquals("s1", q.senderId)
        assertEquals("Unsubscribe from Kids Club 1?", q.question)
        assertEquals("Personal · 5 emails, none opened · last Mon 12 Oct", q.line)
        assertEquals("1 of 11", q.more)
        assertNull(assertNotNull(NeedsYouMailRules.question(offer(1))).more)
        assertNull(NeedsYouMailRules.question(null))
        assertNull(NeedsYouMailRules.question(offer(0)))
    }

    @Test
    fun theSummaryShowsTheQuestionAloneWhenNothingWasTidiedToday() {
        val s = assertNotNull(NeedsYouMailRules.summary(emptyList(), offer(2), at(12 * 60), cal))
        assertTrue(s.tiles.isEmpty())
        assertEquals("Nothing tidied yet today.", s.spoken)
        assertEquals(NeedsYouMailRules.FOOT, s.foot)
        val full = assertNotNull(NeedsYouMailRules.summary(listOf(batch("a", at(9 * 60), mapOf(MailTidy.NEWSLETTER to 3, MailTidy.SCAM_JUNK to 1))), null, at(12 * 60), cal))
        assertEquals("Tidied today: 4. 3 newsletters, 0 promos, 1 junk and scams.", full.spoken)
        assertNull(full.question)
    }
}
