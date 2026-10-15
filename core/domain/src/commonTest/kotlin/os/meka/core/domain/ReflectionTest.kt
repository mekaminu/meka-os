package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Weekly reflection, slice 1: tidy fields, suggestions from the week's facts, when it asks, and one per week synced. */
class ReflectionTest {
    private val hour = 3_600_000L
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon12 = d(10, 12)
    private val sun18 = d(10, 18)

    private fun week(isCurrent: Boolean = true, card: ReviewCard = ReviewCard.NONE) = WeeklyReviewView.EMPTY.copy(
        weekStart = mon12, isCurrent = isCurrent, card = card,
        done = listOf(ReviewDone("t1", "Ring the garage", "Fri"), ReviewDone("t2", "Book the dentist", "Thu")),
        doneMore = 3,
        habits = listOf(
            ReviewHabit("h1", "Gym", 3, 3, true, List(7) { it < 3 }, "3 of 3 · met"),
            ReviewHabit("h2", "Read", 0, 5, false, List(7) { false }, "0 of 5"),
        ),
        stillOpen = listOf(ReviewOpen("o1", "Sort the loft", "Was due Wed 14 Oct")),
    )

    private fun goal(id: String, title: String, weekOf: Long? = null, done: Boolean = false) = GoalItem(
        id = id, title = title, target = null, horizon = GoalHorizon.MONTH, progressPct = 0, counted = true,
        tasksDone = 0, tasksTotal = 0, habitCount = 0, done = done, meta = "", hasConflict = false, weekOf = weekOf,
    )

    @Test
    fun fieldsAreTidiedAndHintsAddOnceOnTheirOwnLine() {
        assertEquals("Shipped it\nGym twice", ReflectionRules.tidy("  - shipped   it \n\n • Gym twice  "))
        assertEquals("", ReflectionRules.tidy(" \n "))
        assertTrue(ReflectionRules.tidy("word ".repeat(200)).length <= ReflectionRules.MAX_FIELD)
        assertEquals("Gym", ReflectionRules.withHint("", "Gym"))
        assertEquals("Shipped it\nGym", ReflectionRules.withHint("Shipped it", "Gym"))
        assertEquals("Shipped it\ngym.", ReflectionRules.withHint("Shipped it\ngym.", "Gym"))
        assertNull(ReflectionRules.savePlan(" ", "\n", "", mon12, 0))
        assertEquals(listOf("Rang the garage", "Got the gym in"), ReflectionRules.winLines("rang the garage\n\n- got the gym in"))
    }

    @Test
    fun hintsComeFromTheWeeksFacts() {
        val v = week(isCurrent = false)
        assertEquals(
            listOf("Closed the deal", "Gym · 3 of 3", "Ring the garage", "Book the dentist"),
            ReflectionRules.wentWellHints(v, listOf("Closed the deal")).map { it.text },
        )
        assertEquals(listOf("Sort the loft", "Read"), ReflectionRules.dropHints(v).map { it.text })
        val goals = listOf(goal("g1", "Learn Spanish"), goal("g2", "Run 5k", weekOf = mon12 + 7), goal("g3", "Old", done = true))
        assertEquals(listOf("Run 5k" to "g2", "Learn Spanish" to "g1"), ReflectionRules.mattersHints(goals, mon12 + 7).map { it.text to it.goalId })
        assertEquals("5 done · 1 of 2 habits met · 1 win · 1 still open", ReflectionRules.facts(5, 1, 2, 1, 1))
        assertEquals(ReflectionRules.QUIET_WEEK, ReflectionRules.facts(0, 0, 0, 0, 0))
        // The one thing picks its goal only while the goal's title is still in the words.
        assertEquals("g2", ReflectionRules.goalToPick("Run 5k twice", "g2", goals)?.id)
        assertNull(ReflectionRules.goalToPick("Rest more", "g2", goals))
        assertNull(ReflectionRules.goalToPick("Old", "g3", goals))
    }

    @Test
    fun itAsksOnSundayEveningAndMondayUntilSavedOrSkipped() {
        val card = ReviewCard(true, mon12, 0, false, "Review your week", "")
        val v = week(card = card)
        assertTrue(ReflectionRules.view(v, emptyList(), emptyList(), emptyList(), sun18, 19 * 60, cal).asking)
        assertFalse(ReflectionRules.view(v, emptyList(), emptyList(), emptyList(), sun18, 17 * 60, cal).asking)
        assertFalse(ReflectionRules.view(v, emptyList(), emptyList(), emptyList(), d(10, 16), 19 * 60, cal).asking)
        val skipped = Reflection(mon12, "", "", "", skipped = true, savedAtMs = 0)
        val s = ReflectionRules.view(v, listOf(skipped), emptyList(), emptyList(), sun18, 19 * 60, cal)
        assertFalse(s.asking)
        assertTrue(s.skipped)
        assertEquals(ReflectionRules.SKIPPED_LINE, s.line)
        // A past week reads "that week" and its one thing is about the week after.
        val past = ReflectionRules.view(week(isCurrent = false).copy(weekStart = mon12 - 14), emptyList(), emptyList(), emptyList(), sun18, 19 * 60, cal)
        assertEquals(ReflectionRules.TITLE_PAST_WEEK, past.title)
        assertEquals(ReflectionRules.MATTERS_PAST_WEEK, past.mattersPrompt)
    }

    @Test
    fun oneReflectionAWeekSyncsAndTheJournalListsEarlierWeeks() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        world.clock.nowMs = cal.toEpochMs(sun18, 19 * 60)
        val rFold = Reflections(fold.replica) { world.clock.nowMs }
        val rMac = Reflections(mac.replica) { world.clock.nowMs }
        assertFalse(rFold.save(mon12, "", " ", ""))
        assertTrue(rFold.save(mon12 - 7, "Quiet week", "", ""))
        assertTrue(rFold.save(mon12, "Shipped it", "Late email", "Run 5k"))
        fold.sync(); mac.sync()
        val all = rMac.all()
        assertEquals(listOf(mon12, mon12 - 7), all.map { it.weekStart })
        val v = ReflectionRules.view(week(), all, emptyList(), emptyList(), sun18, 19 * 60, cal)
        assertTrue(v.saved)
        assertEquals("Saved Sun 18 Oct at 19:00", v.line)
        assertEquals(listOf("Week of 5 Oct"), v.journal.map { it.label })
        assertEquals("Week of 5 Oct. Went well: Quiet week", v.journal.single().spoken)
        assertTrue(rMac.remove(mon12 - 7, true))
        mac.sync(); fold.sync()
        assertEquals(listOf(mon12), rFold.all().map { it.weekStart })
        assertFalse(rFold.remove(mon12 - 21, true))
    }
}
