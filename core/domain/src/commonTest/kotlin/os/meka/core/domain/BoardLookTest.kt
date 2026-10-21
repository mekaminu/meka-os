package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board by hand, slice 2: the look (lane headers, card chips, event lines, free time as one quiet line). */
class BoardLookTest {
    private fun task(id: String, estimate: Int? = null, done: Boolean = false, repeat: String? = null) = Task(
        id, id, null, if (done) Lifecycle.DONE else Lifecycle.ACTIVE, null, null, estimate, 0, null, null, 0, if (done) 1L else null, false,
        recurrenceRule = repeat,
    )

    private fun card(
        id: String, time: String? = null, line: String? = null, done: Boolean = false, carried: Boolean = false,
        estimate: Int? = null, goal: String? = null,
    ) = BoardCard(task(id, estimate, done), "Title $id", time, line, done, carried, goalTitle = goal)

    private fun day(cards: List<BoardCard>, today: Boolean = false, place: String? = null) = BoardDay(
        epochDay = 20_000, weekday = "Wed", number = "21", isToday = today, isPast = false, place = null, placeLabel = place,
        chips = emptyList(), moreChips = 0, cards = cards, countLine = "", accessibilityLabel = "",
    )

    private fun free(start: Int, end: Int) = BoardFreeTime(start * 60_000L, end * 60_000L, start, end)

    @Test
    fun aCardsChipsGoTimeEstimateLineGoal() {
        val c = card("a", time = "14:30", line = "Carried 2 days", carried = true, estimate = 30, goal = "Run 5k")
        val chips = BoardLookRules.chips(c)
        assertEquals(listOf(CardChipKind.TIME, CardChipKind.ESTIMATE, CardChipKind.CARRIED, CardChipKind.GOAL), chips.map { it.kind })
        assertEquals(listOf("14:30", "30m", "Carried 2 days", "◎ Run 5k"), chips.map { it.label })
        assertEquals(CardChipKind.REPEAT, BoardLookRules.chips(card("r", line = "↻ Every weekday")).single().kind)
        assertEquals(CardChipKind.NOTE, BoardLookRules.chips(card("s", line = "Idea")).single().kind)
        // A plain card has no chips; a done card has none at all.
        assertTrue(BoardLookRules.chips(card("p")).isEmpty())
        assertTrue(BoardLookRules.chips(card("d", time = "09:00", line = "Done", done = true, goal = "Run 5k")).isEmpty())
    }

    @Test
    fun aDaysHeaderSaysHowMuchIsDone() {
        val d = day(listOf(card("a"), card("b", done = true), card("c"), card("e", done = true), card("f")), today = true, place = "Office")
        val p = BoardLookRules.progress(d)!!
        assertEquals("2 of 5 done", p.line)
        assertEquals(0.4f, p.fill)
        assertEquals(false, p.complete)
        assertTrue(BoardLookRules.progress(day(listOf(card("x", done = true))))!!.complete)
        assertNull(BoardLookRules.progress(day(emptyList())))
        assertEquals("Today · Office", BoardLookRules.tag(d))
        assertEquals("Home", BoardLookRules.tag(day(emptyList(), place = "Home")))
        assertEquals("Today", BoardLookRules.tag(day(emptyList(), today = true)))
        assertNull(BoardLookRules.tag(day(emptyList())))
        assertEquals("2 done", BoardLookRules.pastLine(d))
        assertEquals("Nothing done", BoardLookRules.pastLine(day(listOf(card("a")))))
    }

    @Test
    fun eventsAreOneSlimLine() {
        val e = CalendarEvent("e", "Dentist", 0, 1, false, null, "google", "a", "Personal")
        assertEquals("09:30 · Dentist", BoardLookRules.eventLine(BoardChip("e", "09:30", "Dentist", 1, e)))
        assertEquals("All day · Half term", BoardLookRules.eventLine(BoardChip("h", "All day", "Half term", 1, e)))
    }

    @Test
    fun freeTimeIsOneQuietLineNeverBoxes() {
        assertNull(BoardLookRules.freeLine(emptyList()))
        val one = BoardLookRules.freeLine(listOf(free(19 * 60, 21 * 60)))!!
        assertEquals("19:00–21:00 · 2h free", one.label)
        assertEquals("Free from 19:00 to 21:00", one.spoken)
        // The longest stretch leads, with how many more; equals go to the earlier one.
        val three = BoardLookRules.freeLine(listOf(free(9 * 60, 10 * 60), free(13 * 60, 16 * 60), free(19 * 60, 20 * 60)))!!
        assertEquals("13:00–16:00 · 3h free · +2 more", three.label)
        assertEquals("Free from 13:00 to 16:00, and 2 more free times", three.spoken)
        val tie = BoardLookRules.freeLine(listOf(free(9 * 60, 10 * 60), free(14 * 60, 15 * 60)))!!
        assertEquals("09:00–10:00 · 1h free · +1 more", tie.label)
        assertEquals("Free from 09:00 to 10:00, and 1 more free time", tie.spoken)
    }
}
