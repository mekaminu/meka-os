package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tidied mail, slice 5: All tidied mail five a page with ‹ ›, and Select all on this page. */
class ArchivedAllPageTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_000L
    private val now = cal.toEpochMs(day, 20 * 60)

    private fun email(i: Int, onDay: Long = day) = MailTidiedEmail(
        MailTidiedRules.key("acc1", "e$i"), MailTidy.ARCHIVE, MailSort.PROMO,
        cal.toEpochMs(onDay, 6 * 60 + i), "Sender$i", "s$i.example", "Mail $i", null,
    )

    private fun batch(id: String, emails: List<MailTidiedEmail>): MailTidyBatch {
        val fields = MailAutopilotRules.batchFields(cal.toEpochMs(day, 19 * 60), "Personal", emails.groupingBy { it.tidy }.eachCount()) +
            MailTidiedRules.fields(emails)
        return MailAutopilotRules.readBatch(id, fields)!!
    }

    /** 12 emails: 4 yesterday, 8 today. */
    private fun list(): ArchivedAllList {
        val emails = (0 until 4).map { email(it, day - 1) } + (4 until 12).map { email(it) }
        return ArchivedAllRules.list(listOf(batch("mt1", emails)), null, now, cal)
    }

    @Test
    fun fiveAPageNewestFirstUnderTheirDaysWithTheLabel() {
        val l = list()
        val p0 = ArchivedAllRules.paged(l, 0)
        assertEquals(5, p0.rows.size)
        assertEquals(12, p0.all.size)
        assertEquals("1–5 of 12", p0.page.label)
        assertEquals(3, p0.page.pages)
        assertFalse(p0.page.hasPrev); assertTrue(p0.page.hasNext)
        assertEquals(listOf("Today"), p0.days.map { it.label })
        assertEquals(listOf("Sender11", "Sender10", "Sender9", "Sender8", "Sender7"), p0.rows.map { it.from })
        // Page 2 runs across the day: today's last three, then yesterday's first two under its own heading.
        val p1 = ArchivedAllRules.paged(l, 1)
        assertEquals("6–10 of 12", p1.page.label)
        assertEquals(listOf("Today", "Yesterday"), p1.days.map { it.label })
        assertEquals(listOf(3, 2), p1.days.map { it.rows.size })
        // Past the end: the last page.
        val last = ArchivedAllRules.paged(l, 9)
        assertEquals(2, last.page.page)
        assertEquals("11–12 of 12", last.page.label)
        assertEquals(2, last.rows.size)
    }

    @Test
    fun aShortListIsOnePageWithNoLabel() {
        val l = ArchivedAllRules.list(listOf(batch("mt1", (0 until 3).map { email(it) })), null, now, cal)
        val p = ArchivedAllRules.paged(l, 0)
        assertEquals(1, p.page.pages)
        assertEquals("", p.page.label)
        assertEquals(3, p.rows.size)
        // One page: the toggle reads as before.
        assertEquals(MailTidiedBulkRules.SELECT_ALL, MailTidiedBulkRules.pageBar(p.all, p.rows, emptySet()).toggle)
        assertEquals(3, MailTidiedBulkRules.pageToggled(p.all, p.rows, emptySet()).size)
    }

    @Test
    fun selectAllOnThisPagePicksThisPageAndKeepsPicksFromOthers() {
        val l = list()
        val p0 = ArchivedAllRules.paged(l, 0)
        val p1 = ArchivedAllRules.paged(l, 1)
        val bar0 = MailTidiedBulkRules.pageBar(p0.all, p0.rows, emptySet())
        assertEquals(MailTidiedBulkRules.SELECT_PAGE, bar0.toggle)
        val first = MailTidiedBulkRules.pageToggled(p0.all, p0.rows, emptySet())
        assertEquals(p0.rows.map { it.id }.toSet(), first)
        assertEquals(MailTidiedBulkRules.CLEAR_PAGE, MailTidiedBulkRules.pageBar(p0.all, p0.rows, first).toggle)
        // On page 2, Select all on this page adds its five; the bar counts all ten, across pages.
        val both = MailTidiedBulkRules.pageToggled(p1.all, p1.rows, first)
        assertEquals(10, both.size)
        val bar = MailTidiedBulkRules.pageBar(p1.all, p1.rows, both)
        assertEquals("10 selected", bar.line)
        assertEquals("Trash 10", bar.trash)
        // Clear this page takes page 2's off and leaves page 1's.
        assertEquals(first, MailTidiedBulkRules.pageToggled(p1.all, p1.rows, both))
    }
}
