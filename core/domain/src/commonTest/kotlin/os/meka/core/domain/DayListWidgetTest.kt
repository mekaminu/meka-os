package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Day list, slice 6: the home-screen widget. */
class DayListWidgetTest {
    private fun task(id: String, title: String, est: Int? = null, done: Boolean = false) = Task(
        id = id, title = title, notes = null, lifecycle = if (done) Lifecycle.DONE else Lifecycle.ACTIVE, dueAtMs = null,
        scheduledAtMs = null, estimateMinutes = est, priority = 0, goalId = null, somedayKind = null, createdAtMs = 0L,
        completedAtMs = if (done) 1L else null, hasConflict = false, area = null,
    )

    private fun card(t: Task, time: String? = null) = BoardCard(t, t.title, time, null, t.isDone, false)

    private fun list(vararg cards: BoardCard): DayListView = DayListRules.view(
        BoardDay(
            epochDay = CivilDate.toEpochDay(2026, 11, 10), weekday = "Tue", number = "10", isToday = true, isPast = false,
            place = null, placeLabel = null, chips = emptyList(), moreChips = 0, cards = cards.toList(), countLine = "",
            accessibilityLabel = "",
        ),
        nowMinute = 8 * 60, timesOn = false,
    )

    @Test
    fun theWidgetListsTodaysOpenTasksWithTheDoneLine() {
        val w = DayListWidgetRules.view(list(
            card(task("a", "Book the MOT", 30, done = true)),
            card(task("b", "Call Sam", 15), time = "14:30"),
            card(task("c", "Email Alex", 30)),
        ))
        assertEquals("Today", w.title)
        assertTrue(w.line.startsWith("1 of 3 done · ends about"), w.line)
        assertEquals(listOf("b", "c"), w.rows.map { it.id }) // done ones are left off
        assertEquals("14:30", w.rows[0].time)
        assertNull(w.rows[1].time) // no projected starts on the widget
        assertEquals("Call Sam, 14:30, 15m long", w.rows[0].spoken)
        assertEquals(40, w.progressPercent) // 30 of 75 planned minutes
        assertNull(w.more)
    }

    @Test
    fun moreThanFitsSaysHowManyMore() {
        val cards = (1..7).map { card(task("t$it", "Task $it", 15)) }.toTypedArray()
        val w = DayListWidgetRules.view(list(*cards))
        assertEquals(DayListWidgetRules.ROWS, w.rows.size)
        assertEquals("+3 more", w.more)
        assertEquals("+5 more", DayListWidgetRules.view(list(*cards), rows = 2).more)
    }

    @Test
    fun anEmptyOrFinishedDaySaysSo() {
        val empty = DayListWidgetRules.view(list())
        assertTrue(empty.empty)
        assertEquals(DayListWidgetRules.EMPTY, empty.line)
        assertEquals(DayListWidgetRules.EMPTY, DayListWidgetRules.view(null).line)
        val done = DayListWidgetRules.view(list(card(task("a", "Gym", 45, done = true)), card(task("b", "Read", 30, done = true))))
        assertEquals("All 2 done", done.line)
        assertTrue(done.rows.isEmpty())
        assertEquals(100, done.progressPercent)
    }

    @Test
    fun screenReadersHearTheWholeWidget() {
        val w = DayListWidgetRules.view(list(card(task("b", "Call Sam", 15), time = "14:30")))
        assertEquals("Today. ${w.line}. Call Sam, 14:30, 15m long", DayListWidgetRules.describe(w))
        assertEquals("Mark done: Call Sam", DayListWidgetRules.tickLabel(w.rows[0]))
    }

    @Test
    fun theMacsDesktopWidgetSaysClickNotTap() {
        assertEquals("Tap + Add tasks and type one after another", DayListWidgetRules.emptyHint(desk = false))
        assertEquals("Click + Add tasks and type one after another", DayListWidgetRules.emptyHint(desk = true))
        assertTrue(DayListWidgetRules.emptyHint(true).contains(DayListWidgetRules.ADD))
        assertEquals(1_800_000L, DayListWidgetRules.DESK_REFRESH_MS)
    }
}
