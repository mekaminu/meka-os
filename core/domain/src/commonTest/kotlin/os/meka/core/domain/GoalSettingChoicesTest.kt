package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: a goal's own settings on Goals are rows opening sheets, not open chip rows. */
class GoalSettingChoicesTest {
    private val R = GoalSettingChoiceRules
    /** Thu 26 Nov 2026. */
    private val today = CivilDate.toEpochDay(2026, 11, 26)

    private fun goal(id: String = "g1", title: String = "Swim a mile", horizon: GoalHorizon = GoalHorizon.MONTH,
                     dueDay: Long? = null, parentId: String? = null, parentTitle: String? = null, metric: GoalMetric? = null) =
        GoalItem(id, title, null, horizon, 0, false, 0, 0, 0, false, "This month", false,
            dueDay = dueDay, parentId = parentId, parentTitle = parentTitle, metric = metric)

    private fun habit(id: String, title: String, perWeek: Int = 3) = HabitItem(
        id, title, perWeek, HabitTiming.MORNING, 30, null, 0, perWeek, false, HabitPace.ON_TRACK, 0, "week",
        List(7) { false }, "0 of $perWeek this week", null, false,
    )

    private fun metric(habitId: String? = null, habitTitle: String? = null) =
        GoalMetric(5_000, "lengths", 0, today, null, emptyList(), habitId = habitId, habitTitle = habitTitle)

    @Test fun eachRowSaysWhatIsInForce() {
        val g = goal(dueDay = CivilDate.toEpochDay(2026, 11, 30), parentId = "y", parentTitle = "Get fit", metric = metric("h1", "Swim"))
        assertEquals("This month", R.horizonRow(g).value)
        assertEquals("Mon 30 Nov", R.dueRow(g, today).value)
        assertEquals("No date", R.dueRow(goal(), today).value)
        assertEquals("Overdue · was Sat 31 Oct", R.dueRow(goal(dueDay = CivilDate.toEpochDay(2026, 10, 31)), today).value)
        assertEquals("Get fit", R.underRow(g).value)
        assertEquals("Nothing", R.underRow(goal()).value)
        assertEquals("Swim", R.habitRow(g, listOf(habit("h1", "Swim"))).value)
        assertEquals("No habit", R.habitRow(goal(metric = metric()), listOf(habit("h1", "Swim"))).value)
        assertEquals("Swim a mile, horizon: This month. Opens its choices", R.horizonRow(g).spoken)
    }

    @Test fun horizonSaysWhenEachEnds() {
        val s = R.horizonSheet(goal(), today)
        assertEquals(GoalRules.HORIZONS.map { GoalRules.horizonLabel(it) }, s.choices.map { it.label })
        assertEquals(listOf("This month"), s.choices.filter { it.lit }.map { it.label })
        assertEquals("Ends Sun 29 Nov", s.choices.first { it.name == "WEEK" }.line)
        assertEquals("Ends Mon 30 Nov", s.choices.first { it.name == "MONTH" }.line)
        assertEquals("Ends Thu 31 Dec", s.choices.first { it.name == "YEAR" }.line)
        assertEquals(R.SOMEDAY_LINE, s.choices.first { it.name == "SOMEDAY" }.line)
        assertEquals(GoalHorizon.YEAR, R.horizonNamed(goal(), "YEAR"))
        assertNull(R.horizonNamed(goal(), "MONTH"))
        assertNull(R.horizonNamed(goal(), "DECADE"))
    }

    @Test fun doneBySaysHowFarOffAndKeepsADaySetAnotherWay() {
        val s = R.dueSheet(goal(), today)
        assertEquals(listOf("No date"), s.choices.filter { it.lit }.map { it.label })
        // In the last quarter the quarter and the year both end Thu 31 Dec: one choice, the year's.
        assertEquals(4, s.choices.size)
        assertEquals(s.choices.size, s.choices.map { it.name }.distinct().size)
        assertTrue(s.choices.last().label.startsWith("End of year"))
        assertEquals("Due in 3 days", s.choices[1].line)
        assertTrue(s.choices[1].label.startsWith("End of week"))
        assertEquals(5, GoalSettingChoiceRules.dueChoices(CivilDate.toEpochDay(2026, 5, 6)).size)
        val end = CivilDate.toEpochDay(2026, 12, 31)
        assertEquals(DuePick(end), R.dueNamed(goal(), today, end.toString()))
        assertEquals(DuePick(null), R.dueNamed(goal(dueDay = end), today, R.NONE_NAME))
        assertNull(R.dueNamed(goal(), today, R.NONE_NAME))
        assertNull(R.dueNamed(goal(dueDay = end), today, end.toString()))

        // A day set by Break it down leads the sheet, lit, and choosing it changes nothing.
        val own = CivilDate.toEpochDay(2026, 12, 12)
        val kept = R.dueSheet(goal(dueDay = own), today)
        assertEquals(5, kept.choices.size)
        assertTrue(kept.choices.first().lit)
        assertEquals("Sat 12 Dec", kept.choices.first().label)
        assertEquals("Due in 16 days", kept.choices.first().line)
        assertEquals(1, kept.choices.count { it.lit })
        assertNull(R.dueNamed(goal(dueDay = own), today, own.toString()))
        assertEquals("Was due 2 days ago", R.dueLine(today - 2, today))
    }

    @Test fun underOffersTheBiggerGoalsAndNothing() {
        val year = goal("y", "Get fit", GoalHorizon.YEAR)
        val g = goal()
        assertFalse(R.showsUnder(g, emptyList()))
        assertTrue(R.showsUnder(goal(parentId = "y", parentTitle = "Get fit"), emptyList()))
        val s = R.underSheet(g, listOf(year))
        assertEquals(listOf("Nothing", "Get fit"), s.choices.map { it.label })
        assertEquals(listOf("Nothing"), s.choices.filter { it.lit }.map { it.label })
        assertEquals("Shown beneath Get fit · this year", s.choices[1].line)
        assertEquals(GoalLinkPick("y"), R.underNamed(g, listOf(year), "y"))
        assertNull(R.underNamed(g, listOf(year), R.NONE_NAME))
        assertEquals(GoalLinkPick(null), R.underNamed(goal(parentId = "y"), listOf(year), R.NONE_NAME))
        assertNull(R.underNamed(g, listOf(year), "zz"))
    }

    @Test fun aNumberCountsOneHabitsTicks() {
        val habits = listOf(habit("h1", "Swim"), habit("h2", "Stretch", 7))
        val s = R.habitSheet(goal(metric = metric("h2", "Stretch")), habits)
        assertEquals(listOf("No habit", "Swim", "Stretch"), s.choices.map { it.label })
        assertEquals(listOf("Stretch"), s.choices.filter { it.lit }.map { it.label })
        assertEquals("Each tick of Swim adds 1 · 3 a week", s.choices[1].line)
        assertEquals("Each tick of Stretch adds 1 · every day", s.choices[2].line)
        assertEquals(HabitLinkPick("h1"), R.habitNamed(goal(metric = metric("h2")), habits, "h1"))
        assertEquals(HabitLinkPick(null), R.habitNamed(goal(metric = metric("h2")), habits, R.NONE_NAME))
        assertNull(R.habitNamed(goal(metric = metric()), habits, R.NONE_NAME))
        assertNull(R.habitNamed(goal(metric = metric("h2")), habits, "h2"))
    }
}
