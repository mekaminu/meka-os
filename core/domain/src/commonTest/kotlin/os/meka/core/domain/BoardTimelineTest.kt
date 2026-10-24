package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board redesign, Option B, slice 5: the day timeline beside the week (a tab on the cover screen). */
class BoardTimelineTest {
    private val cal = LocalCalendar.UTC
    private val dayMs = CivilDate.DAY_MS
    private val min = 60_000L
    /** Friday 9 October 2026, an office day on the default schedule (09:00–17:30, 80 minutes each way). */
    private val fri = 20735L

    private fun ms(d: Long, minute: Int) = d * dayMs + minute * min
    private fun ev(id: String, d: Long, from: Int, to: Int, allDay: Boolean = false) =
        CalendarEvent(id, "Event $id", ms(d, from), ms(d, to), allDay, null, "google", null, null)

    private fun task(id: String, at: Long?, estimate: Int? = null, done: Boolean = false) = Task(
        id, "Task $id", null, if (done) Lifecycle.DONE else Lifecycle.ACTIVE, null, at, estimate, 0, null, null, 0,
        if (done) at else null, false,
    )

    private fun card(t: Task, suggested: Int? = null) =
        BoardCard(t, t.title, null, null, t.lifecycle == Lifecycle.DONE, false, suggestedMinutes = suggested)

    private fun day(d: Long, cards: List<BoardCard>) = BoardDay(
        d, LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(d) - 1], "9", false, false, null, null, emptyList(), 0, cards, "", "",
    )

    @Test
    fun eventsTimedTasksAndTheCommuteBecomeBlocksInStartOrder() {
        val cards = listOf(
            card(task("quote", ms(fri, 12 * 60 + 30), estimate = 45)),
            card(task("pot", ms(fri, 18 * 60 + 40)), suggested = 10),
            card(task("loose", null, estimate = 30)),
            card(task("renew", ms(fri, 8 * 60 + 10), estimate = 10, done = true)),
        )
        val tl = BoardTimelineRules.of(
            day(fri, cards), listOf(ev("standup", fri, 9 * 60 + 30, 10 * 60), ev("half", fri, 0, 24 * 60, allDay = true)),
            WorkHours(WorkSchedule.DEFAULT), today = fri, nowMs = ms(fri, 11 * 60), calendar = cal,
        )
        assertEquals(
            listOf(
                "c:to 07:40–09:00", "t:renew 08:10–08:20", "e:standup 09:30–10:00", "t:quote 12:30–13:15",
                "c:home 17:30–18:50", "t:pot 18:40–18:50",
            ),
            tl.blocks.map { "${it.key} ${it.label}" },
        )
        // The untimed task stays off the timeline; the all-day event is a line above it.
        assertEquals("All day: Event half", tl.allDayLine)
        assertTrue(tl.blocks.first { it.key == "t:renew" }.done)
        assertEquals(BoardTimelineRules.COMMUTE_HOME, tl.blocks.first { it.key == "c:home" }.title)
        assertEquals("Today", tl.title)
        assertEquals("Fri 9 Oct", tl.dateLabel)
        assertEquals(660, tl.nowMinute)
        assertEquals((11 - 7) * 44f, tl.nowTopDp)
        assertEquals("Today, Fri 9 Oct, timeline, 6 things at a time, All day: Event half", tl.spoken)
        assertEquals("Task Task renew, 08:10 to 08:20, done", tl.blocks[1].spoken)
        assertNull(tl.emptyLine)
    }

    @Test
    fun overlappingBlocksShareTheWidthAndTheRestKeepItWhole() {
        val tl = BoardTimelineRules.of(
            day(fri, listOf(card(task("a", ms(fri, 9 * 60 + 45), estimate = 30)))),
            listOf(ev("one", fri, 9 * 60, 10 * 60), ev("two", fri, 9 * 60 + 30, 11 * 60), ev("three", fri, 14 * 60, 15 * 60)),
            null, today = fri - 1, nowMs = ms(fri - 1, 600), calendar = cal,
        )
        val by = tl.blocks.associateBy { it.key }
        assertEquals(0, by.getValue("e:one").column)
        assertEquals(1, by.getValue("e:two").column)
        assertEquals(2, by.getValue("t:a").column)
        assertEquals(listOf(3, 3, 3), listOf("e:one", "e:two", "t:a").map { by.getValue(it).columns })
        assertEquals(0 to 1, by.getValue("e:three").let { it.column to it.columns })
        // Not today: no now line, and tomorrow is named.
        assertEquals("Tomorrow", tl.title)
        assertNull(tl.nowMinute)
        assertNull(tl.nowTopDp)
        // No work hours: no commute.
        assertFalse(tl.blocks.any { it.kind == DayBlockKind.COMMUTE })
    }

    @Test
    fun theRowsWidenForAnEarlyOrLateBlockAndAShortBlockStaysReadable() {
        val tl = BoardTimelineRules.of(
            day(fri, emptyList()), listOf(ev("early", fri, 6 * 60, 6 * 60 + 15), ev("late", fri, 23 * 60, 24 * 60 + 60)),
            null, today = fri - 3, nowMs = 0, calendar = cal,
        )
        assertEquals(6, tl.firstHour)
        assertEquals(24, tl.endHour)
        assertEquals("06", tl.hours.first())
        assertEquals("23", tl.hours.last())
        assertEquals(18 * 44f, tl.heightDp)
        val early = tl.blocks.first { it.key == "e:early" }
        assertTrue(early.oneLine)
        assertEquals(BoardTimelineRules.MIN_BLOCK_DP, early.heightDp)
        assertEquals(0f, early.topDp(tl.firstHour))
        // The late one runs past midnight: clipped to the day.
        assertEquals("23:00–24:00", tl.blocks.first { it.key == "e:late" }.label)
        assertEquals("Tuesday", BoardTimelineRules.of(day(fri + 4, emptyList()), emptyList(), null, fri, 0, cal).title)
    }

    @Test
    fun anEmptyDayStillShowsItsRowsWithAQuietLine() {
        val tl = BoardTimelineRules.of(day(fri, emptyList()), emptyList(), null, fri, ms(fri, 5 * 60), cal)
        assertEquals(7, tl.firstHour)
        assertEquals(23, tl.endHour)
        assertEquals(16, tl.hours.size)
        assertEquals(BoardTimelineRules.EMPTY, tl.emptyLine)
        // Now (05:00) is above the rows: no line drawn.
        assertNull(tl.nowTopDp)
        assertEquals("Today, Fri 9 Oct, timeline, nothing at a time yet", tl.spoken)
    }

    @Test
    fun thePanelIsNarrowerOnTheFoldAndOpensOnlyWhenTheColumnsKeepTheirWidth() {
        assertEquals(260f, BoardTimelineRules.panelDp(1280f))
        assertEquals(200f, BoardTimelineRules.panelDp(840f))
        // The Mac's window: plenty of room.
        assertTrue(BoardTimelineRules.opensByDefault(1280f, 48f))
        // A board about 690 dp across: the columns would drop under 100 dp, so it starts closed.
        assertFalse(BoardTimelineRules.opensByDefault(690f, 48f))
        // The density pass: the unfolded Fold (about 830 dp) opens it, the columns about 116 dp each.
        assertTrue(BoardTimelineRules.opensByDefault(829f, 48f))
        assertTrue(BoardTimelineRules.opensByDefault(860f, 48f))
    }

    @Test
    fun everyLineIsCleanUnderTheNonsenseCheck() {
        listOf(
            BoardTimelineRules.EMPTY, "All day: Half term", "Today, Fri 9 Oct, timeline, 6 things at a time",
            BoardTimelineRules.COMMUTE_TO, BoardTimelineRules.COMMUTE_HOME, BoardTimelineRules.HIDE,
        ).forEach { assertTrue(ScreenSenseRules.problemsIn(it).isEmpty(), it) }
    }
}
