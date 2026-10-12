package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fold review 2026-10-12 17:56, item 3: Needs you's after-work card, its empty line and one rule for today's habits. */
class NeedsYouReviewTest {
    private fun msg(who: String, at: Long) =
        CapturedItem(Capture.itemId(CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, null, at, "hi $at"), CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, "hi $at", null, at)

    @Test
    fun theAfterWorkCardNamesTheFirstTwoPeople() {
        assertNull(AfterWorkSummaries.build(emptyList(), PeopleLists()).namesLine)
        assertEquals("Ada", AfterWorkSummaries.build(listOf(msg("Ada", 1)), PeopleLists()).namesLine)
        assertEquals("Tom and Ada", AfterWorkSummaries.build(listOf(msg("Ada", 1), msg("Tom", 2)), PeopleLists()).namesLine)
        val five = AfterWorkSummaries.build(
            listOf(msg("Ada", 1), msg("Tom", 2), msg("Bea", 3), msg("Cal", 4), msg("Jeanette", 5), msg("Jeanette", 6)),
            PeopleLists(family = setOf("Jeanette")),
        )
        // Family first, then by latest.
        assertEquals("Jeanette, Cal and 3 more", five.namesLine)
        assertEquals(6, five.toCatchUp)
        assertEquals("Open", AfterWorkSummary.OPEN_LABEL)
    }

    @Test
    fun nothingToDecideSaysWhatIsWaiting() {
        assertEquals("Nothing needs you", NeedsYouStackRules.emptyLine(0))
        assertEquals("Nothing to decide · 1 message to catch up on", NeedsYouStackRules.emptyLine(1))
        assertEquals("Nothing to decide · 9 messages to catch up on", NeedsYouStackRules.emptyLine(9))
    }

    @Test
    fun habitsShowCapitalisedAndEveryScreenCountsTodaysTheSameWay() {
        val world = SyncWorld()
        var n = 0
        val d = world.device("android")
        val g = Goals(d.replica, { "G${n++}" }, { world.clock.nowMs })
        g.addHabit("gym", perWeek = 7)
        g.addHabit("iPad sketch", perWeek = 7)
        val habits = g.habits()
        assertEquals(setOf("Gym", "iPad sketch"), habits.map { it.title }.toSet())
        // A habit that isn't due today (on pace for the week) isn't today's on any screen.
        val onPace = habits.first().copy(id = "x", pace = HabitPace.ON_TRACK, doneToday = false)
        val all = habits + onPace
        val today = NeedsYouMeanwhileRules.habitsToday(GoalsView.EMPTY.copy(habits = all))
        assertEquals(today.size, DayTileRules.habitsToday(all))
        assertEquals("0 of ${today.size} habits done today",
            LifeTileRules.lines(LifeTileInputs(habits = DayTileRules.habitsToday(all), habitsDone = DayTileRules.habitsDone(all))).of(LifeTile.GOALS)?.text)
    }
}
