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

    /** A tidy whose counts follow its emails' kinds, as the server writes them. */
    private fun mixed(id: String, emails: List<MailTidiedEmail>, atMs: Long): MailTidyBatch {
        val counts = emails.groupingBy { it.tidy }.eachCount()
        return MailAutopilotRules.readBatch(id, MailAutopilotRules.batchFields(atMs, "Personal", counts) + MailTidiedRules.fields(emails))!!
    }

    @Test
    fun aTilesKindOpensTodaysEmailsOfThatKindWithTheTilesNumbers() {
        // Meka 2026-11-18 23:34: "Can't do anything with tidied emails". Each tile opens its kind, today's only.
        val today = mixed("mt1", listOf(
            email("a", "Deliveroo", "2 for 1", 9 * 60),
            email("b", "The Digest", "This week", 10 * 60, tidy = MailTidy.NEWSLETTER),
            email("c", "Prize Desk", "You won", 11 * 60, tidy = MailTidy.JUNK_TRASH),
            email("d", "Bank Alert", "Verify now", 11 * 60 + 5, tidy = MailTidy.SCAM_JUNK),
        ), at(12 * 60))
        val yesterday = mixed("mt2", listOf(email("e", "Deliveroo", "Tonight only", 8 * 60, day - 1)), at(18 * 60, day - 1))
        val tiles = NeedsYouMailRules.tiles(NeedsYouMailRules.today(listOf(today, yesterday), now, cal))
        assertEquals(listOf("all", "newsletters", "promos", "junk"), tiles.map { it.kind })
        assertEquals("2 junk and scam emails tidied today. Opens them", tiles[3].spoken)
        assertEquals("1 promo tidied today. Opens it", tiles[2].spoken)

        val junk = ArchivedAllRules.list(listOf(today, yesterday), null, now, cal, TidiedKind.JUNK.id)
        assertEquals("junk", junk.kind)
        assertEquals(listOf("Verify now", "You won"), junk.days.flatMap { it.rows }.map { it.row.subject })
        assertEquals("2 junk and scam emails tidied today · from 2 senders", junk.summary)
        assertEquals(listOf("Last 30 days", "Today · 4", "Newsletters · 1", "Promos · 1", "Junk & scams · 2"), junk.kinds.map { it.label })
        assertEquals(listOf("junk"), junk.kinds.filter { it.selected }.map { it.kind })

        val promos = ArchivedAllRules.list(listOf(today, yesterday), null, now, cal, TidiedKind.PROMOS.id)
        assertEquals(listOf("2 for 1"), promos.days.flatMap { it.rows }.map { it.row.subject }, "yesterday's promo is not today's")
        assertEquals(listOf("Today"), promos.days.map { it.label })

        val all = ArchivedAllRules.list(listOf(today, yesterday), null, now, cal, TidiedKind.ALL.id)
        assertEquals(4, all.days.flatMap { it.rows }.size)
        assertEquals("4 emails tidied today · from 4 senders", all.summary)

        // The last 30 days is the list as before, its chip lit.
        val month = ArchivedAllRules.list(listOf(today, yesterday), null, now, cal)
        assertNull(month.kind)
        assertEquals(5, month.days.flatMap { it.rows }.size)
        assertTrue(month.kinds.first().selected)

        // A sender within a kind.
        val one = ArchivedAllRules.list(listOf(today, yesterday), "deliveroo", now, cal, TidiedKind.PROMOS.id)
        assertEquals("Deliveroo · 1 promo tidied today", one.summary)
    }

    @Test
    fun aKindWithNothingTodaySaysSoAndNoChipsShowWithoutTodaysTidies() {
        val today = mixed("mt1", listOf(email("a", "Deliveroo", "2 for 1", 9 * 60)), at(12 * 60))
        val news = ArchivedAllRules.list(listOf(today), null, now, cal, TidiedKind.NEWSLETTERS.id)
        assertEquals("No newsletters tidied today", news.summary)
        assertEquals("No newsletters tidied today", news.empty)
        assertTrue(news.days.isEmpty())
        val old = mixed("mt2", listOf(email("e", "Deliveroo", "Tonight only", 8 * 60, day - 1)), at(18 * 60, day - 1))
        assertTrue(ArchivedAllRules.list(listOf(old), null, now, cal).kinds.isEmpty())
        assertEquals("No newsletters tidied today. Opens the list", NeedsYouMailRules.tileSpoken(0, "newsletters", TidiedKind.NEWSLETTERS))
        assertEquals(TidiedKind.JUNK, TidiedKind.of("junk"))
        assertNull(TidiedKind.of("nope"))
    }

    /** Week 5, A9: the sender row's first chip names senders, so a lit "All" never reads as the day filter. */
    @Test
    fun theSenderRowsFirstChipSaysAllSendersNotAll() {
        assertEquals("All senders", ArchivedAllRules.ALL)
        val today = mixed("mt1", listOf(email("a", "Deliveroo", "2 for 1", 9 * 60)), at(12 * 60))
        val l = ArchivedAllRules.list(listOf(today), null, now, cal, TidiedKind.PROMOS.id)
        assertTrue(l.kinds.none { it.label == ArchivedAllRules.ALL || it.label == "All" }, "the kind row has no chip named like the sender row's")
        assertTrue(l.senders.none { it.label.startsWith(ArchivedAllRules.ALL) })
    }
}
