package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Week board item (5) part 1: To a day… on an email puts its task on a day of the Board. */
class BoardFeedTest {
    private val cal = LocalCalendar.UTC
    private val wed = CivilDate.toEpochDay(2026, 10, 21)

    @Test
    fun theDaysRunToSundayThenIntoNextWeekUntilFive() {
        // Wednesday: the rest of this week, five in all.
        assertEquals(listOf("Today", "Tomorrow", "Fri", "Sat", "Sun"), BoardFeedRules.days(wed).map { it.label })
        // Monday: the whole week.
        assertEquals(listOf("Today", "Tomorrow", "Wed", "Thu", "Fri", "Sat", "Sun"), BoardFeedRules.days(wed - 2).map { it.label })
        // Saturday: next week's days carry their date.
        val sat = BoardFeedRules.days(wed + 3)
        assertEquals(listOf("Today", "Tomorrow", "Mon 26", "Tue 27", "Wed 28"), sat.map { it.label })
        assertEquals((wed + 3..wed + 7).toList(), sat.map { it.day })
        assertEquals("On today", sat[0].spoken)
        assertEquals("On Monday 26 October", sat[2].spoken)
        // Sunday: today, tomorrow (Monday) and on by date.
        assertEquals(listOf("Today", "Tomorrow", "Tue 27", "Wed 28", "Thu 29"), BoardFeedRules.days(wed + 4).map { it.label })
    }

    @Test
    fun aChipLeftOpenPastMidnightMeansToday() {
        assertEquals(wed, BoardFeedRules.dayFor(wed - 1, wed))
        assertEquals(wed + 2, BoardFeedRules.dayFor(wed + 2, wed))
    }

    @Test
    fun theLineSaysWhichDay() {
        assertEquals("Task “Book MOT” on Fri 23 Oct · email archived at Navilife's next check", BoardFeedRules.mailLine("Book MOT", wed + 2, wed))
        assertEquals("Task “Book MOT” for tomorrow · 3 emails archived at Navilife's next check", BoardFeedRules.mailLine("Book MOT", wed + 1, wed, 3))
        assertEquals("Task “Book MOT” for today · email archived at Navilife's next check", BoardFeedRules.mailLine("Book MOT", wed, wed))
    }

    @Test
    fun toADayIsFirstUnderMoreWhereTheSurfaceOffersIt() {
        val now = wed * CivilDate.DAY_MS + 12 * 3_600_000L
        val item = assertNotNull(MailNeedsRules.read("mi1", MailNeedsRules.serverFields("Personal", "Sam Taylor", "x@example.com", "Kit list", "Hi", now - 60_000, 1)))
        val row = MailNeedsRules.card(listOf(item), now, cal)!!.rows.single()
        assertEquals(MailRowAction.TO_DAY, MailRowActionRules.actions(row, toDay = true).more.first())
        assertTrue(MailRowAction.TO_DAY !in MailRowActionRules.actions(row).more)
        assertEquals("To a day…", MailRowActionRules.label(MailRowAction.TO_DAY, row))
        assertEquals("Put Kit list on a day of your week as a task, and archive the email", MailRowActionRules.spoken(MailRowAction.TO_DAY, row))
        // A note to self too, before Snooze.
        val note = assertNotNull(MailNeedsRules.read("mi2", MailNeedsRules.serverFields("Personal", "Me", "x@example.com", "Read this", "https://example.com/a", now - 60_000, 1, note = "example.com")))
        assertEquals(listOf(MailRowAction.TO_DAY, MailRowAction.SNOOZE), MailRowActionRules.actions(MailNeedsRules.card(listOf(note), now, cal)!!.rows.single(), toDay = true).more)
    }
}
