package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 6: Reading's three numbers, its four cards, + Add's choices and the row sheets. */
class ReadingPageTest {
    private fun row(id: String, title: String, state: ReadingState, progress: String, fraction: Float? = null) =
        ReadingRow(id, title, "Book · Alex Writer", progress, fraction, state.key, "", "Book: $title, $progress")

    private val habits = row("a", "Atomic Habits", ReadingState.READING, "Page 120 of 320 · 38%", 0.375f)
    private val podcast = row("p", "The Rest Is History", ReadingState.READING, "Episode 4")
    private val dune = row("d", "Dune", ReadingState.WANT, "Not started · added 2 Oct")
    private val done = row("f", "Project Hail Mary", ReadingState.FINISHED, "Finished 10 Oct")
    private val down = row("x", "War and Peace", ReadingState.PUT_DOWN, "Put down 3 Oct · at page 80")

    private fun view(goal: ReadingGoalView? = null, upNext: List<ReadingRow> = listOf(dune)) =
        ReadingView(listOf(habits, podcast), upNext, listOf(done), listOf(down), "2 on the go · 1 up next", null, goal, if (goal != null) 12 else 0)

    @Test
    fun threeNumbersOnTheGoUpNextAndTheGoalOrWhatFinished() {
        val p = ReadingPageRules.page(view())
        assertEquals("Reading", p.title)
        assertEquals(
            listOf(PageTile("2", "on the go", false), PageTile("1", "up next", false), PageTile("1", "finished lately", false)),
            p.tiles,
        )
        val g = ReadingGoalView("Read 12 books in 2026", "4 of 12", "On pace", 0.33f, true, "")
        assertEquals(PageTile("4 of 12", "books this year", true), ReadingPageRules.page(view(g)).tileAt(2))
        val empty = ReadingPageRules.page(ReadingView.EMPTY)
        assertEquals(listOf("None", "None", "None"), empty.tiles.map { it.number })
        assertEquals(listOf("item", "goal"), p.addChoices.map { it.key })
        assertEquals(ReadingGoalRules.HINT, ReadingPageRules.hint(ReadingPageRules.ADD_GOAL))
        assertEquals(ReadingRules.HINT, ReadingPageRules.hint(ReadingPageRules.ADD_ITEM))
    }

    @Test
    fun fourCardsWithHowFarOnTheRight() {
        val p = ReadingPageRules.page(view())
        assertEquals(listOf("On the go", "Up next", "Finished lately", "Reading goal"), p.cards.map { it.title })
        val go = p.cardAt(0)
        assertEquals("38%", go.rowAt(0).whenText)
        assertEquals("Book · Alex Writer", go.rowAt(0).line)
        // Nothing to measure against: how far goes in the grey line, nothing on the right.
        assertEquals("", go.rowAt(1).whenText)
        assertEquals("Book · Alex Writer · Episode 4", go.rowAt(1).line)
        assertEquals(listOf("Project Hail Mary", "War and Peace"), p.cardAt(2).rows.map { it.name })
        assertEquals("Put down 3 Oct · at page 80", p.cardAt(2).rowAt(1).line)
        val goal = p.cardAt(3).rowAt(0)
        assertEquals(ReadingPageRules.KEY_GOAL, goal.key)
        assertEquals(ReadingPageRules.NO_GOAL, goal.name)
    }

    @Test
    fun emptyCardsSayWhatToDo() {
        val p = ReadingPageRules.page(ReadingView.EMPTY)
        assertEquals(ReadingPageRules.GO_EMPTY, p.cardAt(0).empty)
        assertEquals(ReadingPageRules.NEXT_EMPTY, p.cardAt(1).empty)
        assertTrue(p.cards.take(3).all { it.rows.isEmpty() })
    }

    @Test
    fun moreThanFiveUpNextHaveSeeAll() {
        val many = (1..7).map { row("n$it", "Book $it", ReadingState.WANT, "Not started") }
        val v = view(upNext = many)
        val next = ReadingPageRules.page(v).cardAt(1)
        assertEquals(7, next.count)
        assertEquals(5, next.rowCount)
        assertEquals(LifePageRules.SEE_ALL, next.more)
        assertEquals(7, ReadingPageRules.allRows(v, ReadingPageRules.NEXT).size)
    }

    @Test
    fun aRowsSheetFindsItWhereverItMovedAndOffersWhatFits() {
        val v = view()
        val key = ReadingPageRules.itemKey("d")
        assertEquals("Dune", ReadingPageRules.item(v, key)?.title)
        assertEquals(listOf("Start"), ReadingPageRules.acts(dune).map { it.label })
        assertEquals(listOf("Finished", "Put down"), ReadingPageRules.acts(habits).map { it.label })
        assertEquals(listOf("Again"), ReadingPageRules.acts(done).map { it.label })
        assertEquals(listOf("Pick up"), ReadingPageRules.acts(down).map { it.label })
        assertTrue(ReadingPageRules.takesProgress(habits))
        assertFalse(ReadingPageRules.takesProgress(dune))
        // Started: the same key finds it on the go.
        val started = v.copy(onTheGo = v.onTheGo + dune.copy(state = ReadingState.READING.key), upNext = emptyList())
        assertEquals(ReadingState.READING.key, ReadingPageRules.item(started, key)?.state)
        assertNull(ReadingPageRules.item(v.copy(upNext = emptyList()), key))
        assertNull(ReadingPageRules.item(v, ReadingPageRules.KEY_GOAL))
    }
}
