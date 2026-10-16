package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Archived for you" shows which emails, slice 1: each tidy lists its emails by sender. */
class MailTidiedEmailsTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_000L
    private fun at(minute: Int, d: Long = day) = cal.toEpochMs(d, minute)

    private fun email(
        id: String, from: String, subject: String, tidy: MailTidy = MailTidy.ARCHIVE, sort: MailSort? = MailSort.PROMO,
        minute: Int = 9 * 60, domain: String? = "shop.example", pence: Long? = null, d: Long = day,
    ) = MailTidiedEmail(MailTidiedRules.key("acc1", id), tidy, sort, at(minute, d), from, domain, subject, pence)

    private fun batch(id: String, atMs: Long, emails: List<MailTidiedEmail>, count: Int = emails.size): MailTidyBatch {
        val kinds = emails.groupingBy { it.tidy }.eachCount()
        val fields = MailAutopilotRules.batchFields(atMs, "Personal", kinds) + MailTidiedRules.fields(emails) +
            (MailTidyBatchFields.COUNT to FieldValue.Int64(count.toLong()))
        return MailAutopilotRules.readBatch(id, fields)!!
    }

    @Test
    fun theFieldRoundTripsAndNeverCarriesTabsOrNewlines() {
        val es = listOf(
            email("m1", "Amazon", "Your order has\tshipped\n today", MailTidy.RECEIPT, null, pence = 1299, domain = "amazon.co.uk"),
            email("m2", "Deliveroo", "", minute = 10 * 60),
        )
        val text = assertNotNull(MailTidiedRules.encode(es))
        val back = MailTidiedRules.decode(text)
        assertEquals(listOf("Deliveroo", "Amazon"), back.map { it.from }) // newest first
        assertEquals("Your order has shipped today", back[1].subject)
        assertEquals(MailTidiedRules.NO_SUBJECT, back[0].subject)
        assertEquals(1299L, back[1].pence)
        assertEquals("amazon.co.uk", back[1].domain)
        assertNull(MailTidiedRules.encode(emptyList()))
        // A line from a later version, or a broken one, is skipped.
        assertEquals(1, MailTidiedRules.decode("2\tx\ty\n" + MailTidiedRules.line(es[0])).size)
    }

    @Test
    fun aLongTidyFitsTheSyncLimitAndSaysHowManyMore() {
        val es = (1..300).map { email("m$it", "Sender $it", "S".repeat(200), minute = it) }
        val text = assertNotNull(MailTidiedRules.encode(es))
        assertTrue(text.length <= MailTidiedRules.MAX_CHARS)
        val b = batch("t", at(12 * 60), es)
        assertTrue(b.emails.size in 50 until 300)
        val (groups, more) = MailTidiedRules.groups(b, b.emails, at(12 * 60 + 30), cal)
        assertEquals(MailTidiedRules.GROUPS_SHOWN, groups.size)
        assertEquals("+ ${300 - MailTidiedRules.GROUPS_SHOWN} more in this tidy", more)
    }

    @Test
    fun emailsGroupBySenderWithChipsAndTimes() {
        val es = listOf(
            email("d1", "Deliveroo", "20% off tonight", minute = 8 * 60),
            email("d2", "Deliveroo", "Free delivery", minute = 9 * 60),
            email("d3", "Deliveroo", "Your weekly treat", minute = 10 * 60),
            email("d4", "deliveroo", "Last chance", minute = 7 * 60),
            email("a1", "Amazon", "Your order has shipped", MailTidy.RECEIPT, null, minute = 11 * 60, domain = "amazon.co.uk", pence = 1299),
            email("n1", "The Weekly", "Issue 42", MailTidy.NEWSLETTER, null, minute = 6 * 60, domain = null, d = day - 1),
        )
        val b = batch("t", at(11 * 60 + 5), es)
        val (groups, more) = MailTidiedRules.groups(b, b.emails, at(12 * 60 + 30), cal)
        assertNull(more)
        assertEquals(listOf("Amazon", "Deliveroo · 4 promos", "The Weekly"), groups.map { it.title })
        assertEquals("amazon.co.uk · Personal", groups[0].meta)
        assertEquals("Personal", groups[2].meta)
        assertEquals(ArchivedEmailRow(MailTidiedRules.key("acc1", "a1"), "Your order has shipped", "Receipt £12.99", "11:00"), groups[0].rows[0])
        assertEquals(listOf("Your weekly treat", "Free delivery", "20% off tonight"), groups[1].rows.map { it.subject })
        assertEquals("+ 1 more from Deliveroo", groups[1].moreLine)
        assertEquals("Newsletter", groups[2].rows[0].chip)
        assertEquals("${CivilDate.shortLabel(day - 1)} · 06:00", groups[2].rows[0].time)
    }

    @Test
    fun junkAndRescuedRowsAreShownTheSameWay() {
        val es = listOf(
            email("j1", "Prize Desk", "You've won", MailTidy.JUNK_TRASH, null),
            email("r1", "Sam Taylor", "Saturday?", MailTidy.RESCUE, null, minute = 9 * 60 + 1),
            email("u1", "Shopco", "New in", MailTidy.TRASH, MailSort.PROMO, minute = 9 * 60 + 2),
            email("x1", "Bank alerts", "Statement ready", MailTidy.ARCHIVE, MailSort.NOTIFICATION, minute = 9 * 60 + 3),
            email("x2", "Old app", "Hello", MailTidy.ARCHIVE, null, minute = 9 * 60 + 4),
        )
        val b = batch("j", at(10 * 60), es)
        val chips = MailTidiedRules.groups(b, b.emails, at(12 * 60 + 30), cal).first.associate { it.title to it.rows[0].chip }
        assertEquals("Junk · Trash", chips["Prize Desk"])
        assertEquals("Rescued from Junk", chips["Sam Taylor"])
        assertEquals("Unsubscribed · Trash", chips["Shopco"])
        assertEquals("Notification", chips["Bank alerts"])
        assertEquals("Notification or promo", chips["Old app"])
    }

    @Test
    fun theCardListsEachTidysEmailsAndAnOlderTidyKeepsItsCounts() {
        val newer = batch("b", at(11 * 60), listOf(email("d1", "Deliveroo", "20% off"), email("d2", "Deliveroo", "Free delivery", minute = 9 * 60 + 5)))
        val (summary, detail) = MailAutopilotRules.lines(mapOf(MailTidy.ARCHIVE to 1), "Personal")
        val older = MailTidyBatch("a", at(8 * 60), "Personal", summary, detail, 1, null, null, null, mapOf(MailTidy.ARCHIVE to 1))
        val card = assertNotNull(ArchivedForYouRules.card(listOf(older, newer), null, at(12 * 60 + 30), cal))
        assertEquals(listOf("b", "a"), card.lines.map { it.id })
        assertEquals(listOf("Deliveroo · 2 promos"), card.lines[0].emails.map { it.title })
        assertTrue(card.lines[1].emails.isEmpty())
        assertEquals("1 notification or promo archived", card.lines[1].detail)
    }
}
