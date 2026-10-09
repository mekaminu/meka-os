package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HabitChipsTest {
    private fun habit(id: String, pace: HabitPace, done: Boolean = false, title: String = id) = HabitItem(
        id = id, title = title, targetPerWeek = 3, timing = HabitTiming.ANYTIME, minutes = 30, goalId = null,
        doneThisWeek = 1, weekTarget = 3, doneToday = done, pace = pace, streak = 0, streakUnit = "week",
        week = List(7) { false }, meta = "", streakLine = null, hasConflict = false,
    )

    private fun goals(vararg h: HabitItem) = GoalsView(h.toList(), emptyList(), 0)

    @Test
    fun noHabitsTodayMeansNoChips() {
        assertTrue(HabitChipRules.build(null as GoalsView?).isEmpty())
        assertTrue(HabitChipRules.build(goals()).isEmpty())
        assertTrue(HabitChipRules.build(goals(habit("Later", HabitPace.ON_TRACK), habit("Met", HabitPace.WEEK_MET))).isEmpty())
    }

    @Test
    fun chipsAreTodaysHabitsInGoalsOrder() {
        val chips = HabitChipRules.build(
            goals(habit("Behind", HabitPace.BEHIND), habit("Later", HabitPace.ON_TRACK), habit("Due", HabitPace.DUE),
                habit("Done", HabitPace.DONE_TODAY, done = true)),
        )
        assertEquals(listOf("Behind", "Due", "Done"), chips.map { it.id })
        assertEquals(listOf(false, false, true), chips.map { it.done })
        assertEquals(listOf(true, false, false), chips.map { it.behind })
        assertEquals("Tick Due for today", chips[1].tickLabel)
        assertEquals("Untick Done for today", chips[2].tickLabel)
    }

    @Test
    fun longNamesAreShortenedAtAWordButSpokenWhole() {
        val chip = HabitChipRules.build(goals(habit("r", HabitPace.DUE, title = "Read 20 pages of a novel"))).single()
        assertEquals("Read 20 pages of…", chip.title)
        assertEquals("Tick Read 20 pages of a novel for today", chip.tickLabel)
        assertEquals("Stretch", HabitChipRules.shortTitle("  Stretch "))
        assertEquals("Supercalifragilist…", HabitChipRules.shortTitle("Supercalifragilisticexpialidocious"))
        assertTrue(HabitChipRules.shortTitle("A".repeat(40)).length <= HabitChipRules.MAX_TITLE_CHARS + 1)
    }

    @Test
    fun theStripUnderTheTickerDropsTheHabitsTile() {
        val tiles = listOf(
            DayTile(DayTileKind.NEXT_EVENT, 25, label = "until Standup"),
            DayTile(DayTileKind.HABITS, 0, total = 1, label = "habits today"),
            DayTile(DayTileKind.RENEWALS, 2, label = "renewals due"),
        )
        val strip = HabitChipRules.stripTiles(tiles)
        assertEquals(listOf(DayTileKind.NEXT_EVENT, DayTileKind.RENEWALS), strip.map { it.kind })
        assertFalse(HabitChipRules.stripTiles(listOf(tiles[1])).any())
    }

    @Test
    fun aWeeklyHabitsChipCarriesTheWeeksGoes() {
        // Fold review 2026-10-09 13:45, item 1: "1/2" beside a twice-a-week habit's name; a daily one has none.
        val weekly = habit("g", HabitPace.DUE).copy(targetPerWeek = 2, weekTarget = 2, doneThisWeek = 1)
        val daily = habit("s", HabitPace.DUE).copy(targetPerWeek = 7, weekTarget = 7)
        val chips = HabitChipRules.build(goals(weekly, daily))
        assertEquals("1/2", chips.first { it.id == "g" }.count)
        assertEquals(null, chips.first { it.id == "s" }.count)
    }
}
