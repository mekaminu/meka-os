package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NeedsYouMeanwhileTest {
    private fun habit(id: String, pace: HabitPace, done: Boolean = false) = HabitItem(
        id = id, title = id, targetPerWeek = 3, timing = HabitTiming.ANYTIME, minutes = 30, goalId = null,
        doneThisWeek = 1, weekTarget = 3, doneToday = done, pace = pace, streak = 0, streakUnit = "week",
        week = List(7) { false }, meta = "", streakLine = null, hasConflict = false,
    )

    private fun waiting(id: String) =
        WaitingItem(id, "Reply from $id", id, null, 0L, null, null, DueState.LATER, "$id · since Mon 5 Oct", false)

    private fun renewal(id: String, state: RenewalState) = RenewalItem(
        id = id, title = id, kind = ObligationKind.INSURANCE, subject = null, notes = null, dueDay = 0, cancelByDay = null,
        leadDays = 21, costPence = null, repeats = RenewalRepeat.YEARLY, repeatLabel = "Every year", state = state,
        meta = "renews Thu 12 Nov", doneLabel = "Renewed", stopLabel = "Cancelled it", hasConflict = false,
    )

    private fun goals(vararg h: HabitItem) = GoalsView(h.toList(), emptyList(), 0)

    private fun lists(waiting: List<WaitingItem> = emptyList(), attention: List<RenewalItem> = emptyList(), upcoming: List<RenewalItem> = emptyList(), later: List<RenewalItem> = emptyList()) =
        ListsView(waiting, emptyList(), emptyList(), RenewalsView(attention, upcoming, later, 0, 0))

    @Test
    fun nothingAtAllIsEmpty() {
        val m = NeedsYouMeanwhileRules.build(null, null)
        assertTrue(m.isEmpty)
        assertEquals(emptyList(), m.sections)
        assertNull(m.habitsLine)
        assertTrue(NeedsYouMeanwhileRules.build(goals(), lists()).isEmpty)
    }

    @Test
    fun habitsTodayAreDoneDueOrBehindInGoalsOrder() {
        val m = NeedsYouMeanwhileRules.build(
            goals(habit("Behind", HabitPace.BEHIND), habit("Due", HabitPace.DUE), habit("Later", HabitPace.ON_TRACK),
                habit("Met", HabitPace.WEEK_MET), habit("Done", HabitPace.DONE_TODAY, done = true)),
            null,
        )
        assertEquals(listOf("Behind", "Due", "Done"), m.habits.map { it.id })
        assertEquals("1 of 3 done", m.habitsLine)
        assertEquals(listOf("Habits today"), m.sections)
        assertEquals("Tick Due for today", NeedsYouMeanwhileRules.tickLabel(m.habits[1]))
        assertEquals("Untick Done for today", NeedsYouMeanwhileRules.tickLabel(m.habits[2]))
    }

    @Test
    fun waitingShowsThreeThenMore() {
        val m = NeedsYouMeanwhileRules.build(null, lists(waiting = listOf(waiting("Ada"), waiting("Bo"), waiting("Cy"), waiting("Di"), waiting("Ed"))))
        assertEquals(listOf("Ada", "Bo", "Cy"), m.waiting.map { it.id })
        assertEquals("Reply from Ada", m.waiting[0].title)
        assertEquals("Ada · since Mon 5 Oct", m.waiting[0].meta)
        assertEquals("+2 more", m.waitingMore)
        assertEquals(listOf("Waiting on"), m.sections)
        assertNull(NeedsYouMeanwhileRules.build(null, lists(waiting = listOf(waiting("Ada")))).waitingMore)
    }

    @Test
    fun renewalsAreAttentionThenUpcomingNeverLater() {
        val m = NeedsYouMeanwhileRules.build(null, lists(
            attention = listOf(renewal("Car", RenewalState.SOON)),
            upcoming = listOf(renewal("Home", RenewalState.LATER), renewal("TV", RenewalState.LATER), renewal("Gym", RenewalState.LATER)),
            later = listOf(renewal("Passport", RenewalState.LATER)),
        ))
        assertEquals(listOf("Car", "Home", "TV"), m.renewals.map { it.id })
        assertEquals("+1 more", m.renewalsMore)
        assertEquals("renews Thu 12 Nov", m.renewals[0].meta)
        val onlyLater = NeedsYouMeanwhileRules.build(null, lists(later = listOf(renewal("Passport", RenewalState.LATER))))
        assertTrue(onlyLater.renewals.isEmpty())
    }

    @Test
    fun sectionsInOrder() {
        val m = NeedsYouMeanwhileRules.build(
            goals(habit("Stretch", HabitPace.DUE)),
            lists(waiting = listOf(waiting("Ada")), upcoming = listOf(renewal("Home", RenewalState.LATER))),
        )
        assertEquals(listOf("Habits today", "Waiting on", "Coming up to renew"), m.sections)
    }
}
