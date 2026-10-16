package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Archived for you" shows which emails, slice 3: All tidied mail, newest first by day, with a sender filter. */
class ArchivedAllTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_000L
    private val now = cal.toEpochMs(day, 20 * 60)
    private fun at(minute: Int, d: Long = day) = cal.toEpochMs(d, minute)

    private fun email(id: String, from: String, subject: String, minute: Int, d: Long = day, tidy: MailTidy = MailTidy.ARCHIVE) =
        MailTidiedEmail(MailTidiedRules.key("acc1", id), tidy, MailSort.PROMO, at(minute, d), from, "${from.lowercase()}.example", subject, null)

    private fun batch(
        id: String, emails: List<MailTidiedEmail>, atMs: Long, count: Int = emails.size, extra: Map<String, FieldValue> = emptyMap(),
    ): MailTidyBatch {
        val counts = if (emails.isEmpty()) mapOf(MailTidy.ARCHIVE to count) else mapOf(MailTidy.ARCHIVE to count)
        val fields = MailAutopilotRules.batchFields(atMs, "Personal", counts) + MailTidiedRules.fields(emails) + extra
        return MailAutopilotRules.readBatch(id, fields)!!
    }

    @Test
    fun everyEmailOfTheLast30DaysNewestFirstUnderItsDay() {
        val b1 = batch("mt1", listOf(email("a", "Deliveroo", "2 for 1", 9 * 60), email("b", "Amazon", "Shipped", 10 * 60)), at(12 * 60))
        val b2 = batch("mt2", listOf(email("c", "Deliveroo", "Tonight only", 8 * 60, day - 1)), at(18 * 60, day - 1))
        val old = batch("mt3", listOf(email("d", "Old", "Too old", 9 * 60, day - 40)), at(12 * 60, day - 40))
        val l = ArchivedAllRules.list(listOf(b2, b1, old), null, now, cal)
        assertEquals(listOf("Today", "Yesterday"), l.days.map { it.label })
        assertEquals(listOf("Shipped", "2 for 1"), l.days[0].rows.map { it.row.subject })
        assertEquals("10:00", l.days[0].rows[0].row.time)
        assertEquals("mt1", l.days[0].rows[0].row.batchId)
        assertEquals("mt1|" + l.days[0].rows[0].row.key, l.days[0].rows[0].id)
        assertEquals("amazon.example · Personal", l.days[0].rows[0].meta)
        assertEquals("Promo", l.days[0].rows[0].row.chip)
        assertEquals("3 emails tidied in the last 30 days · from 2 senders", l.summary)
        assertTrue(l.allSelected)
        assertEquals(listOf("Deliveroo · 2", "Amazon · 1"), l.senders.map { it.label })
        assertNull(l.more)
        assertNull(l.empty)
    }

    @Test
    fun aSenderFilterShowsOnlyTheirsAndAnUnknownOneShowsAll() {
        val b1 = batch("mt1", listOf(email("a", "Deliveroo", "2 for 1", 9 * 60), email("b", "Amazon", "Shipped", 10 * 60)), at(12 * 60))
        val b2 = batch("mt2", listOf(email("c", "deliveroo", "Tonight only", 8 * 60, day - 1)), at(18 * 60, day - 1))
        val l = ArchivedAllRules.list(listOf(b1, b2), "deliveroo", now, cal)
        assertFalse(l.allSelected)
        assertEquals(listOf("2 for 1", "Tonight only"), l.days.flatMap { it.rows }.map { it.row.subject })
        assertEquals("Deliveroo · 2 emails tidied in the last 30 days", l.summary)
        assertTrue(l.senders.first { it.id == "deliveroo" }.selected)
        assertEquals("Show only Deliveroo's 2 emails", l.senders.first().spoken)
        val gone = ArchivedAllRules.list(listOf(b1, b2), "nobody", now, cal)
        assertTrue(gone.allSelected)
        assertEquals(3, gone.days.sumOf { it.rows.size })
    }

    @Test
    fun theListIsCutAndTheChosenSenderStaysAmongTheChips() {
        // 20 senders: one with 1 email, the rest with 10 each, so the single one isn't in the top 12.
        val emails = (1..19).flatMap { s -> (1..10).map { i -> email("s$s-$i", "Sender$s", "Mail $i", s * 10 + i) } } +
            email("solo", "Zed", "Only one", 5)
        val b = batch("mt1", emails, at(12 * 60))
        val l = ArchivedAllRules.list(listOf(b), null, now, cal)
        assertEquals(ArchivedAllRules.MAX_ROWS, l.days.sumOf { it.rows.size })
        assertEquals("Showing the newest 150 · choose a sender to see all of theirs", l.more)
        assertEquals(ArchivedAllRules.SENDERS_SHOWN, l.senders.size)
        assertFalse(l.senders.any { it.id == "zed" })
        val z = ArchivedAllRules.list(listOf(b), "zed", now, cal)
        assertEquals(ArchivedAllRules.SENDERS_SHOWN, z.senders.size)
        assertTrue(z.senders.last().selected)
        assertEquals("Zed · 1", z.senders.last().label)
        assertNull(z.more)
    }

    @Test
    fun anAskOrAPutBackTidyShowsOnTheRow() {
        val e = email("a", "Deliveroo", "2 for 1", 9 * 60)
        val asks = MailTidiedAskRules.encodeAsks(listOf(MailTidiedAsk(e.key, MailTidiedAction.PUT_BACK, at(13 * 60))))
        val asked = batch("mt1", listOf(e), at(12 * 60), extra = mapOf(MailTidyBatchFields.EMAIL_ASKS to FieldValue.Text(asks)))
        val row = ArchivedAllRules.list(listOf(asked), null, now, cal).days.single().rows.single()
        assertEquals(MailTidiedAction.PUT_BACK.pending, row.row.state)
        assertTrue(row.spoken.startsWith("Deliveroo, 2 for 1, Promo, 09:00, Putting back"))
        // The preview opens from the row as from the card.
        assertNotNull(MailTidiedAskRules.preview(asked, row.row.key, now, cal))
    }

    @Test
    fun tidiesFromBeforeTheListAreCountedAndNothingShowsEmpty() {
        val counted = batch("mt0", emptyList(), at(12 * 60, day - 3), count = 7)
        val l = ArchivedAllRules.list(listOf(counted), null, now, cal)
        assertEquals("Before Navilife listed each email: 1 tidy · 7 emails (counts only)", l.countsOnly)
        assertNull(l.empty)
        assertTrue(l.days.isEmpty())
        val none = ArchivedAllRules.list(emptyList(), null, now, cal)
        assertEquals(ArchivedAllRules.EMPTY, none.empty)
        assertEquals(ArchivedAllRules.EMPTY, none.summary)
        assertTrue(none.senders.isEmpty())
    }

    @Test
    fun aTidyThatCouldNotNameEveryEmailSaysSo() {
        val b = batch("mt1", listOf(email("a", "Deliveroo", "2 for 1", 9 * 60)), at(12 * 60), count = 5)
        assertEquals("1 email tidied in the last 30 days · from 1 sender · 4 more not named", ArchivedAllRules.list(listOf(b), null, now, cal).summary)
    }
}
