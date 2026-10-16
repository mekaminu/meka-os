package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 4: the "Archived for you" card with the digests. */
class ArchivedForYouTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_000L
    private fun at(minute: Int, d: Long = day) = cal.toEpochMs(d, minute)
    private fun batch(
        id: String, atMs: Long, account: String = "Personal", kinds: Map<MailTidy, Int> = mapOf(MailTidy.NEWSLETTER to 3),
        asked: Long? = null, undone: Long? = null, note: String? = null,
    ): MailTidyBatch {
        val (summary, detail) = MailAutopilotRules.lines(kinds, account)
        return MailTidyBatch(id, atMs, account, summary, detail, kinds.values.sum(), asked, undone, note, kinds)
    }

    @Test
    fun nothingBeforeTheDigestThenTheCardAtHalfTwelve() {
        val tidies = listOf(batch("a", at(8 * 60)))
        assertNull(ArchivedForYouRules.card(tidies, null, at(12 * 60), cal))
        val card = assertNotNull(ArchivedForYouRules.card(tidies, null, at(12 * 60 + 30), cal))
        assertEquals("Archived for you", card.title)
        assertEquals("Lunchtime digest", card.slot)
        assertEquals("Tidied 3 · 3 newsletters", card.headline)
        assertEquals(listOf("08:00"), card.lines.map { it.time })
        assertEquals("3 newsletters → Navilife/Newsletters", card.lines[0].detail)
        assertTrue(card.lines[0].canPutBack)
    }

    @Test
    fun theHeadlineSumsEachKindAcrossAccountsAndLeavesPutBackOnesOut() {
        val tidies = listOf(
            batch("a", at(9 * 60), kinds = mapOf(MailTidy.NEWSLETTER to 5, MailTidy.RECEIPT to 2)),
            batch("b", at(10 * 60), "Business", mapOf(MailTidy.NEWSLETTER to 4, MailTidy.ARCHIVE to 2, MailTidy.RECEIPT to 1)),
            batch("c", at(11 * 60), kinds = mapOf(MailTidy.ARCHIVE to 7), asked = at(11 * 60 + 5)),
            batch("d", at(11 * 60 + 30), kinds = mapOf(MailTidy.ARCHIVE to 1), undone = at(11 * 60 + 40), note = "Put back 1 email in the inbox"),
        )
        val card = assertNotNull(ArchivedForYouRules.card(tidies, null, at(13 * 60), cal))
        assertEquals("Tidied 14 · 9 newsletters · 3 receipts and orders · 2 notifications and promos", card.headline)
        assertEquals(listOf("d", "c", "b", "a"), card.lines.map { it.id })
        assertEquals("Put back 1 email in the inbox", card.lines[0].state)
        assertEquals(MailAutopilotPanelRules.PUTTING_BACK, card.lines[1].state)
        assertTrue(!card.lines[1].canPutBack)
        assertEquals("Business", card.lines[2].account)
        assertEquals(ArchivedForYouRules.ALL_PUT_BACK, ArchivedForYouRules.headline(tidies.drop(2)))
    }

    @Test
    fun gotItFoldsItAwayUntilTheNextDigestFindsNewTidies() {
        val morning = batch("a", at(9 * 60))
        val afternoon = batch("b", at(14 * 60))
        val seen = at(13 * 60)
        // Seen at 13:00: the 14:00 tidy waits for the evening digest.
        assertNull(ArchivedForYouRules.card(listOf(morning, afternoon), seen, at(15 * 60), cal))
        val evening = assertNotNull(ArchivedForYouRules.card(listOf(morning, afternoon), seen, at(18 * 60 + 30), cal))
        assertEquals("Evening digest", evening.slot)
        assertEquals(listOf("b"), evening.lines.map { it.id })
        // An evening card not seen is still there the next morning (yesterday's 18:30 is the latest digest), dated.
        val next = assertNotNull(ArchivedForYouRules.card(listOf(afternoon), seen, at(7 * 60, day + 1), cal))
        assertEquals("Evening digest", next.slot)
        assertEquals("${CivilDate.shortLabel(day)} · 14:00", next.lines[0].time)
    }

    @Test
    fun oldTidiesBeforeSliceFourAndLongListsStayShort() {
        // A batch written before slice 4 has no kinds: it counts in the total only.
        val old = MailTidyBatch("o", at(9 * 60), "Personal", "Tidied 6 emails · Personal", null, 6, null, null, null)
        val card = assertNotNull(ArchivedForYouRules.card(listOf(old), null, at(12 * 60 + 31), cal))
        assertEquals("Tidied 6", card.headline)
        assertEquals("Tidied 6 emails · Personal", card.lines[0].detail)
        // Nothing older than two days, and at most eight tidies listed.
        assertNull(ArchivedForYouRules.card(listOf(batch("x", at(9 * 60, day - 3))), null, at(13 * 60), cal))
        val many = (0 until 11).map { batch("m$it", at(60 + it * 30)) }
        val long = assertNotNull(ArchivedForYouRules.card(many, null, at(13 * 60), cal))
        assertEquals(ArchivedForYouRules.SHOWN, long.lines.size)
        assertEquals("+ 3 earlier tidies · in All tidied mail", long.moreLine)
    }

    @Test
    fun seenRoundTrips() {
        assertEquals(42L, ArchivedForYouRules.readSeen(ArchivedForYouRules.seenFields(42L)))
        assertNull(ArchivedForYouRules.readSeen(null))
    }
}
