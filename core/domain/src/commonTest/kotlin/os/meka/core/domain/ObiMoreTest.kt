package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Obi sees everything, slice 3: the Week board's trays, decisions, Reading and the coverage registry. */
class ObiMoreTest {
    private fun task(id: String, title: String, lifecycle: Lifecycle = Lifecycle.ACTIVE, kind: SomedayKind? = null) =
        Task(id, title, null, lifecycle, null, null, null, 0, null, kind, 0, null, false)

    private fun card(id: String, title: String) = BoardCard(task(id, title), title, null, null, done = false, carried = false)

    private fun board(lane: List<BoardCard>, next: List<BoardCard> = emptyList()) = WeekBoard(
        0L,
        listOf(
            BoardWeek(0L, "This week", "26 Oct–1 Nov", emptyList(), lane),
            BoardWeek(7L, "Next week", "2–8 Nov", emptyList(), next),
            BoardWeek(14L, "9–15 Nov", "9–15 Nov", emptyList(), emptyList()),
        ),
        emptyList(),
    )

    @Test
    fun theBoardsTraysAreAllThereWhenAskedAndOneCountsLineOtherwise() {
        val b = board(listOf(card("a", "Fix the shed"), card("b", "Call the bank")), listOf(card("c", "Book the MOT")))
        val someday = listOf(SomedayGroup(SomedayKind.IDEA, "Ideas", listOf(task("d", "Learn the guitar", Lifecycle.SOMEDAY, SomedayKind.IDEA))))
        assertEquals(
            listOf(
                "Week board · 3 with no day yet · 1 on Someday",
                "No day yet · This week (26 Oct–1 Nov) · 2: “Fix the shed”, “Call the bank”",
                "No day yet · Next week (2–8 Nov) · 1: “Book the MOT”",
                "Someday · Ideas · 1: “Learn the guitar”",
            ),
            ObiMoreRules.boardLines(b, someday, full = true),
        )
        assertEquals(listOf("Week board · 3 with no day yet · 1 on Someday"), ObiMoreRules.boardLines(b, someday, full = false))
        assertEquals(emptyList(), ObiMoreRules.boardLines(board(emptyList()), emptyList(), full = true))
        assertTrue(ObiMoreRules.boardAsked("What's on Someday?"))
        assertTrue(ObiMoreRules.boardAsked("anything with no day yet?"))
        assertTrue(!ObiMoreRules.boardAsked("How's my study going?"))
    }

    @Test
    fun manyTitlesSayHowManyMore() {
        val lane = (1..8).map { card("t$it", "Job $it") }
        val line = ObiMoreRules.boardLines(board(lane), emptyList(), full = true)[1]
        assertTrue(line.endsWith("“Job 6” and 2 more"), line)
    }

    @Test
    fun decisionsCarryTheirReviewAndWhy() {
        val d = DecisionItem("x", "Keep the old car another year", "  cheaper  ", 0, 10, DecisionStatus.ACTIVE, null, DueState.DUE, "Review due", false)
        assertEquals(
            listOf("Decisions · 1 active · 1 to review", "“Keep the old car another year” · Review due · why: cheaper"),
            ObiMoreRules.decisionLines(listOf(d), full = true),
        )
        assertEquals(listOf("Decisions · 1 active · 1 to review"), ObiMoreRules.decisionLines(listOf(d), full = false))
        assertTrue(ObiMoreRules.decisionsAsked("What did I decide about the car?"))
    }

    @Test
    fun readingListsWhatsOnTheGoAndNext() {
        val row = ReadingRow("r", "Dune", "Book · Frank Herbert", "Page 120 of 320 · 38%", 0.38f, "reading", "", "")
        val next = ReadingRow("s", "Atomic Habits", "Book · James Clear", "Not started", null, "want", "", "")
        val v = ReadingView(listOf(row), listOf(next), emptyList(), emptyList(), "1 on the go · 1 up next", null)
        assertEquals(
            listOf(
                "Reading · 1 on the go · 1 up next",
                "On the go · Dune · Book · Frank Herbert · Page 120 of 320 · 38%",
                "Up next · Atomic Habits · Book · James Clear · Not started",
            ),
            ObiMoreRules.readingLines(v, full = true),
        )
        assertEquals(listOf("Reading · 1 on the go · 1 up next"), ObiMoreRules.readingLines(v, full = false))
        assertEquals(emptyList(), ObiMoreRules.readingLines(ReadingView.EMPTY, full = true))
        assertTrue(ObiMoreRules.readingAsked("What book am I reading?"))
    }

    @Test
    fun everyPlaceInNavilifeSaysHowObiReachesIt() {
        // The `when` is exhaustive; this pins what isn't readable yet, so nothing is left out quietly.
        val later = HelpPlace.entries.filter { ObiCoverage.reach(it) is ObiReach.Later }.toSet()
        // Slice 4 reached the last five (the emergency card, News, voice notes, Health, the Vault): none is left.
        assertEquals(emptySet(), later)
        // Every tile in Ask's Life grid is a place with a reach of its own.
        LifeTile.entries.forEach { t ->
            val place = HelpPlace.entries.single { it.name == t.name }
            assertTrue(ObiCoverage.reach(place) !is ObiReach.Help, t.name)
        }
        // Every area is reached from a place, except the day's own (Needs you, a task's checklist), which are tabs.
        assertEquals(ObiArea.entries.toSet() - setOf(ObiArea.NEEDS_YOU, ObiArea.CHECKLISTS), ObiCoverage.reachedAreas)
        // Live's look-up takes every area by its key.
        ObiArea.entries.forEach { assertEquals(it, ObiArea.of(it.key)) }
    }
}
