package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board by hand, slice 4: goal pills, tray chips, narrow past days and the lanes' widths. */
class BoardTidyTest {
    private fun near(expected: Float, actual: Float) = assertTrue(kotlin.math.abs(expected - actual) < 0.01f, "$expected ≠ $actual")

    private fun task(id: String) = Task(id, id, null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false)

    private fun card(id: String, done: Boolean = false) = BoardCard(task(id), "Title $id", null, null, done, false)

    private fun day(epoch: Long, past: Boolean = false, cards: List<BoardCard> = emptyList()) = BoardDay(
        epochDay = epoch, weekday = "Mon", number = "19", isToday = false, isPast = past, place = null, placeLabel = null,
        chips = emptyList(), moreChips = 0, cards = cards, countLine = "", accessibilityLabel = "Monday 19 October, 2 events",
    )

    @Test
    fun goalsAreSmallPillsNeverZeroOfZero() {
        val p = BoardTidyRules.goalPill(BoardGoal("g", "Run 5k", 2, 4, finished = false, picked = true))
        assertEquals("◎ Run 5k", p.label)
        assertEquals("2/4", p.count)
        assertEquals(0.5f, p.fill)
        assertNull(BoardTidyRules.goalPill(BoardGoal("g", "Read", 0, 0, false, true)).count)
        val done = BoardTidyRules.goalPill(BoardGoal("g", "Read", 1, 3, finished = true, picked = true))
        assertEquals("✓", done.count)
        assertEquals(1f, done.fill)
    }

    @Test
    fun traysAreChipsAndAnEmptyOneNeverOpens() {
        val week = BoardWeek(20_000, "This week", "19–25 Oct", emptyList(), lane = listOf(card("a"), card("b"), card("c")))
        val board = WeekBoard(20_002, listOf(week), someday = emptyList())
        val chips = BoardTidyRules.trayChips(week, board)
        assertEquals(listOf("No day yet · 3", "Someday"), chips.map { it.label })
        assertEquals("Someday, empty. Drop a task here", chips[1].accessibilityLabel)
        // Tap No day yet: it opens; tap the empty Someday: nothing changes; tap No day yet again: it closes.
        val open = BoardTidyRules.toggle(null, chips[0])
        assertEquals(BoardTray.NO_DAY, open)
        assertEquals(BoardTray.NO_DAY, BoardTidyRules.toggle(open, chips[1]))
        assertNull(BoardTidyRules.toggle(open, chips[0]))
        // Its last card moved to a day: the open lane closes rather than show an empty box.
        val emptied = BoardTidyRules.trayChips(week.copy(lane = emptyList()), board)
        assertNull(BoardTidyRules.stillOpen(BoardTray.NO_DAY, emptied))
        assertEquals("No day yet", emptied[0].label)
    }

    @Test
    fun pastDaysNeverFoldNarrowAnyMore() {
        // Option B (Meka 2026-10-24 14:39): Mon–Fri are five full columns every time, a past one only dimmed.
        val mon = day(20_000, past = true, cards = listOf(card("a", done = true), card("b", done = true), card("c")))
        assertFalse(BoardTidyRules.collapsed(mon, emptySet()))
        assertFalse(BoardTidyRules.collapsed(mon, setOf(20_000)))
        assertFalse(BoardTidyRules.collapsed(day(20_002), emptySet()))
        assertEquals("Monday 19 October, 2 done. Tap to open", BoardTidyRules.collapsedLabel(mon))
    }

    @Test
    fun theWeekSharesTheWidthPastDaysTooAndNoLaneTooWide() {
        // Wednesday: Monday and Tuesday are past but keep their full share (Option B: nothing folds).
        val week1 = (0 until 7).map { day(20_000L + it, past = it < 2) }
        val week2 = (7 until 14).map { day(20_000L + it) }
        val w = BoardTidyRules.laneWidths(week1 + week2, 800f, emptySet())
        near(800f / 7, w[0])
        near(800f / 7, w[2])
        // Next week's seven share it all.
        near(800f / 7, w[7])
        // On a very wide screen no lane is wider than 250; opening Monday widens it like the rest.
        assertEquals(250f, BoardTidyRules.laneWidths(week2, 2400f, emptySet())[0])
        assertEquals(BoardTidyRules.laneWidths(week1, 800f, setOf(20_000L))[0], BoardTidyRules.laneWidths(week1, 800f, setOf(20_000L))[3])
        // Scroll places: the second week starts after the first's total; the nearest start wins.
        val starts = BoardTidyRules.starts(w)
        near(800f, starts[7])
        assertEquals(7, BoardTidyRules.indexAt(790f, w))
        assertEquals(1, BoardTidyRules.columnAt(130f, w))
    }
}
