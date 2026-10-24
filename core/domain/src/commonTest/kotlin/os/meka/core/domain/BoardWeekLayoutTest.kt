package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Week board redesign, Option B, slice 2: Mon–Fri across, the weekend shorter underneath, the lists below. */
class BoardWeekLayoutTest {
    private fun near(expected: Float, actual: Float) = assertTrue(kotlin.math.abs(expected - actual) < 0.01f, "$expected ≠ $actual")

    private fun task(id: String) = Task(id, id, null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false)

    private fun card(id: String) = BoardCard(task(id), "Title $id", null, null, false, false)

    private fun day(epoch: Long, past: Boolean = false) = BoardDay(
        epochDay = epoch, weekday = "Mon", number = "19", isToday = false, isPast = past, place = null, placeLabel = null,
        chips = emptyList(), moreChips = 0, cards = emptyList(), countLine = "", accessibilityLabel = "Monday 19 October",
    )

    private val week = BoardWeek(20_000, "This week", "19–25 Oct", (0L..6L).map { day(20_000 + it, past = it < 2) })

    @Test
    fun mondayToFridayAcrossTheWeekendUnderneath() {
        assertEquals((20_000L..20_004L).toList(), BoardWeekLayoutRules.weekdays(week).map { it.epochDay })
        assertEquals(listOf(20_005L, 20_006L), BoardWeekLayoutRules.weekend(week).map { it.epochDay })
    }

    @Test
    fun aRowsDaysShareItsWidthEquallyPastOnesToo() {
        // Option B (Meka 2026-10-24 14:39): five equal columns, a past one never folds narrow.
        val w = BoardWeekLayoutRules.rowWidths(BoardWeekLayoutRules.weekdays(week), 800f, emptySet())
        w.forEach { near(160f, it) }
        near(800f, w.sum())
        val opened = BoardWeekLayoutRules.rowWidths(BoardWeekLayoutRules.weekdays(week), 800f, setOf(20_000L, 20_001L))
        opened.forEach { near(160f, it) }
        // The weekend's two share the row.
        BoardWeekLayoutRules.rowWidths(BoardWeekLayoutRules.weekend(week), 800f, emptySet()).forEach { near(400f, it) }
        // Never below zero, however narrow.
        assertTrue(BoardWeekLayoutRules.rowWidths(BoardWeekLayoutRules.weekdays(week), 100f, emptySet()).all { it >= 0f })
    }

    @Test
    fun theWeekendIsShorterAndAShortScreenKeepsTheLeastHeights() {
        val tall = BoardWeekLayoutRules.heights(1000f)
        near(620f, tall.weekdays)
        near(372f, tall.weekend)
        assertTrue(tall.weekend < tall.weekdays)
        near(372f, tall.lists)
        val short = BoardWeekLayoutRules.heights(300f)
        near(BoardWeekLayoutRules.MIN_WEEKDAYS_DP, short.weekdays)
        near(BoardWeekLayoutRules.MIN_WEEKEND_DP, short.weekend)
        near(BoardWeekLayoutRules.MIN_LISTS_DP, short.lists)
    }

    @Test
    fun theListsAreNoDayYetThenSomedayEachSayingWhenEmpty() {
        val w = week.copy(lane = listOf(card("a"), card("b")))
        val lists = BoardWeekLayoutRules.lists(w, WeekBoard(20_002, listOf(w), someday = emptyList()))
        assertEquals(listOf(BoardTray.NO_DAY, BoardTray.SOMEDAY, BoardTray.SOMEDAY, BoardTray.SOMEDAY), lists.map { it.tray })
        assertEquals(listOf("No day yet · 2", "Someday", "Side hustle", "House"), lists.map { it.title })
        assertEquals(listOf("a", "b"), lists[0].cards.map { it.id })
        assertEquals("No day yet, 2 tasks", lists[0].accessibilityLabel)
        assertEquals("Someday, empty. Drop a task here", lists[1].accessibilityLabel)
        assertEquals("Nothing on Someday", lists[1].empty)
        assertEquals("Nothing waiting for a day", BoardWeekLayoutRules.lists(week, WeekBoard(20_002, listOf(week), someday = listOf(card("s"))))[0].empty)
    }
}
