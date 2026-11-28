package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Goals' habit settings are rows opening sheets, not open chip rows. */
class GoalHabitChoicesTest {
    private fun habit(perWeek: Int = 5, timing: HabitTiming = HabitTiming.EVENING, minutes: Int = 20, booked: Boolean = false,
                      rotation: List<String> = emptyList(), goalId: String? = null) = HabitItem(
        "h1", "Read 20 pages", perWeek, timing, minutes, goalId, 0, perWeek, false, HabitPace.ON_TRACK, 0, "week",
        List(7) { false }, "0 of 5 this week", null, false, booked = booked, rotation = rotation,
    )

    private fun goal(id: String, title: String) = GoalItem(id, title, null, GoalHorizon.YEAR, 0, false, 0, 0, 0, false, "This year", false)

    @Test fun eachRowSaysWhatIsInForce() {
        val h = habit(booked = true, rotation = listOf("Push", "Pull", "Legs"), goalId = "g1")
        assertEquals("5 a week", GoalHabitChoiceRules.oftenRow(h).value)
        assertEquals("Evening", GoalHabitChoiceRules.whenRow(h).value)
        assertEquals("20 min", GoalHabitChoiceRules.longRow(h).value)
        assertEquals("On", GoalHabitChoiceRules.bookRow(h).value)
        assertEquals("Push · Pull · Legs", GoalHabitChoiceRules.rotationRow(h).value)
        assertEquals("Swim a mile", GoalHabitChoiceRules.towardsRow(h, listOf(goal("g1", "Swim a mile"))).value)
        assertEquals("No goal", GoalHabitChoiceRules.towardsRow(habit(), listOf(goal("g1", "Swim a mile"))).value)
        assertEquals("Read 20 pages, how often: 5 a week. Opens its choices", GoalHabitChoiceRules.oftenRow(h).spoken)
    }

    @Test fun eachSheetLightsOneAndSaysWhatItDoes() {
        val h = habit()
        val often = GoalHabitChoiceRules.oftenSheet(h)
        assertEquals(GoalRules.TARGET_CHOICES.map { it.label }, often.choices.map { it.label })
        assertEquals(listOf("5 a week"), often.choices.filter { it.lit }.map { it.label })
        assertTrue(often.choices.first().line.startsWith("Every day · a daily streak"))
        assertTrue(often.choices.first { it.lit }.spoken.endsWith(", chosen"))

        val whenSheet = GoalHabitChoiceRules.whenSheet(h)
        assertEquals("Planned between 17:00 and 22:00", whenSheet.choices.first { it.lit }.line)
        assertEquals("Planned between 06:00 and 12:00", whenSheet.choices.first { it.name == "MORNING" }.line)
        assertEquals("Planned wherever your day has room", whenSheet.choices.first { it.name == "ANYTIME" }.line)

        val long = GoalHabitChoiceRules.longSheet(h)
        assertEquals(GoalRules.MINUTE_CHOICES.map { "$it min" }, long.choices.map { it.label })
        assertEquals("Navilife finds an hour for it", long.choices.first { it.name == "60" }.line)
        assertEquals("Navilife finds 1 h 30 for it", long.choices.first { it.name == "90" }.line)
        assertEquals(listOf("Off"), GoalHabitChoiceRules.bookSheet(h).choices.filter { it.lit }.map { it.label })

        val rot = GoalHabitChoiceRules.rotationSheet(habit(booked = true, rotation = listOf("Upper", "Lower")))
        assertEquals(SessionRules.ROTATIONS.size, rot.choices.size)
        assertEquals(listOf("Upper · Lower"), rot.choices.filter { it.lit }.map { it.label })
        assertEquals("Sessions take turns: Upper, then Lower", rot.choices.first { it.lit }.line)
    }

    @Test fun aChoiceSavesOnlyWhatChanges() {
        val h = habit(goalId = "g1")
        assertEquals(3, GoalHabitChoiceRules.oftenNamed(h, "3"))
        assertNull(GoalHabitChoiceRules.oftenNamed(h, "5"))
        assertNull(GoalHabitChoiceRules.oftenNamed(h, "6"))
        assertEquals(HabitTiming.MORNING, GoalHabitChoiceRules.whenNamed(h, "MORNING"))
        assertNull(GoalHabitChoiceRules.whenNamed(h, "EVENING"))
        assertEquals(45, GoalHabitChoiceRules.longNamed(h, "45"))
        assertNull(GoalHabitChoiceRules.longNamed(h, "20"))
        assertEquals(true, GoalHabitChoiceRules.bookNamed(h, "On"))
        assertNull(GoalHabitChoiceRules.bookNamed(h, "Off"))
        assertEquals(1, GoalHabitChoiceRules.rotationNamed(h, "1"))
        assertNull(GoalHabitChoiceRules.rotationNamed(h, "0"))

        val goals = listOf(goal("g1", "Swim a mile"), goal("g2", "Learn Spanish"))
        assertEquals(GoalLinkPick("g2"), GoalHabitChoiceRules.towardsNamed(h, goals, "g2"))
        assertEquals(GoalLinkPick(null), GoalHabitChoiceRules.towardsNamed(h, goals, GoalHabitChoiceRules.NO_GOAL_NAME))
        assertNull(GoalHabitChoiceRules.towardsNamed(h, goals, "g1"))
        assertNull(GoalHabitChoiceRules.towardsNamed(habit(), goals, GoalHabitChoiceRules.NO_GOAL_NAME))
        assertNull(GoalHabitChoiceRules.towardsNamed(h, goals, "gone"))
        val sheet = GoalHabitChoiceRules.towardsSheet(h, goals)
        assertEquals(listOf("No goal", "Swim a mile", "Learn Spanish"), sheet.choices.map { it.label })
        assertEquals(listOf("Swim a mile"), sheet.choices.filter { it.lit }.map { it.label })
    }
}
