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

    @Test
    fun aDiscordPostToADaySaysWhichDayAndThatNothingIsTraded() {
        assertEquals("Idea on Fri 23 Oct · a task to look at it, nothing is traded", BoardFeedRules.ideaLine(wed + 2, wed))
        assertEquals("Idea for tomorrow · a task to look at it, nothing is traded", BoardFeedRules.ideaLine(wed + 1, wed))
        assertEquals("Idea for today · a task to look at it, nothing is traded", BoardFeedRules.ideaLine(wed, wed))
        assertTrue(BoardFeedRules.ideaSpoken("Admin").endsWith("nothing is traded"))
        // A post kept on his week reads "On your week"; one in Trading ideas doesn't.
        val ch = DiscordChannel("s#ideas", "S", "ideas")
        val now = wed * CivilDate.DAY_MS + 12 * 3_600_000L
        val posts = listOf(
            DiscordRules.Post("p1", ch, "Admin", "BTC long", now - 60_000, done = false, kept = "t1"),
            DiscordRules.Post("p2", ch, "Admin", "ETH short", now - 30_000, done = false, kept = "t2"),
        )
        val rows = DiscordRules.cards(posts, setOf(ch.key), now, cal, onWeek = setOf("p1"))!!.cards.single().posts
        assertEquals(listOf(true, false), rows.map { it.onWeek })
        assertEquals(listOf(true, true), rows.map { it.kept })
    }

    @Test
    fun aNeedsYouCardGoesToALaterDayAndSaysItsDueDateStays() {
        // From tomorrow on: Done covers today.
        assertEquals(listOf("Tomorrow", "Fri", "Sat", "Sun"), BoardFeedRules.needsDays(wed).map { it.label })
        assertEquals(wed + 1, BoardFeedRules.needsDayFor(wed - 1, wed))
        assertEquals(wed + 1, BoardFeedRules.needsDayFor(wed, wed))
        assertEquals(wed + 3, BoardFeedRules.needsDayFor(wed + 3, wed))
        assertEquals("Moved “Pay council tax” to tomorrow · still due today", BoardFeedRules.needsLine("Pay council tax", wed + 1, wed, wed))
        assertEquals("Moved “Pay council tax” to Fri 23 Oct · still due yesterday", BoardFeedRules.needsLine("Pay council tax", wed + 2, wed - 1, wed))
        assertEquals("Moved “Pay council tax” to Fri 23 Oct · still due Mon 19 Oct", BoardFeedRules.needsLine("Pay council tax", wed + 2, wed - 2, wed))
        assertEquals("Moved “Gym” to Fri 23 Oct", BoardFeedRules.needsLine("Gym", wed + 2, null, wed))
        assertEquals("Moved “Gym” to Fri 23 Oct", BoardFeedRules.needsLine("Gym", wed + 2, wed + 2, wed))
        // Only a task's overdue and due-today cards offer it.
        fun card(kind: DecisionKind, task: String? = "t1") = DecisionCard(
            "c", kind, "Pay", "why", task, "Done", "Tomorrow", "Open",
            DecisionEffect.COMPLETE_TASK, DecisionEffect.SNOOZE_TASK, DecisionEffect.OPEN_TASK, urgent = false,
        )
        assertTrue(BoardFeedRules.canSendToDay(card(DecisionKind.OVERDUE)))
        assertTrue(BoardFeedRules.canSendToDay(card(DecisionKind.DUE_TODAY)))
        assertTrue(!BoardFeedRules.canSendToDay(card(DecisionKind.CONFLICT)))
        assertTrue(!BoardFeedRules.canSendToDay(card(DecisionKind.LISTS, null)))
    }

    @Test
    fun aSchoolDateOrABirthdayGoesToADayUpToItsDate() {
        // A trip on Friday: today, tomorrow and Friday; nothing after it, nothing once it's gone.
        assertEquals(listOf("Today", "Tomorrow", "Fri"), BoardFeedRules.dateDays(wed, wed + 2).map { it.label })
        assertEquals(listOf("Today"), BoardFeedRules.dateDays(wed, wed).map { it.label })
        assertTrue(BoardFeedRules.dateDays(wed, wed - 1).isEmpty())
        // Weeks away: the usual five chips.
        assertEquals(5, BoardFeedRules.dateDays(wed, wed + 30).size)
        // Never before today, never after the date.
        assertEquals(wed, BoardFeedRules.dateDayFor(wed - 1, wed, wed + 2))
        assertEquals(wed + 2, BoardFeedRules.dateDayFor(wed + 4, wed, wed + 2))
        assertEquals(wed + 1, BoardFeedRules.dateDayFor(wed + 1, wed, wed + 2))
        assertEquals("Ready for Trip to the zoo · Alex · Fri 23 Oct", BoardFeedRules.schoolTitle("Trip to the zoo", "Alex", wed + 2))
        assertEquals("Ready for Non-uniform day · Fri 23 Oct", BoardFeedRules.schoolTitle(" Non-uniform day ", "", wed + 2))
        assertEquals("Gift for Mum's birthday · Sat 31 Oct", BoardFeedRules.giftTitle("Mum's birthday", wed + 10))
        assertEquals("Order by Tuesday 27 October for delivery in time\nIdeas: Scarf, Book\nFrom Gifts", BoardFeedRules.giftNotes(wed + 6, wed, listOf("Scarf", " ", "Book")))
        assertEquals("The order-by day has passed\nFrom Gifts", BoardFeedRules.giftNotes(wed - 1, wed, emptyList()))
        assertEquals("Task “Gym” for tomorrow · on your Board", BoardFeedRules.dateLine("Gym", wed + 1, wed))
        assertEquals("Task “Gym” on Fri 23 Oct · on your Board", BoardFeedRules.dateLine("Gym", wed + 2, wed))
        // One id per thing and date; a removed one makes room for the next.
        assertEquals("tbsx1-${wed + 2}", BoardFeedRules.dateTaskId("s", "x1", wed + 2))
        assertEquals("tbgo1-${wed + 2}-2", BoardFeedRules.dateTaskId("g", "o1", wed + 2, 2))
    }
}
