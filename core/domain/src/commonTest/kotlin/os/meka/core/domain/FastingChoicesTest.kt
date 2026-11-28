package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Fasting's plan, goal and start open sheets, not open chip rows. */
class FastingChoicesTest {
    private val R = FastingChoiceRules
    private val hour = 3_600_000L
    private val now = 1_000L * hour

    /** A 16 h fast started 13½ hours ago at 03:45, goal 19:45. */
    private fun daily(target: Int = 16, startedAgoMin: Int = 13 * 60 + 30) = FastNow(
        id = "f1", startedAtMs = now - startedAgoMin * 60_000L, targetHours = target,
        goalAtMs = now - startedAgoMin * 60_000L + target * hour, reachedGoal = false,
        startedLine = "Started 03:45", goalLine = "Goal $target h · at 19:45", goalWhen = "19:45",
    )

    private fun extended(hours: Int = 120, title: String = "5-day fast") = FastNow(
        id = "f2", startedAtMs = now - 30 * hour, targetHours = hours, goalAtMs = now - 30 * hour + hours * hour,
        reachedGoal = false, startedLine = "Started Mon 20:00", goalLine = "Goal 5 days · Sat 20:00", extended = true,
        title = title, goalWhen = "Sat 20:00",
    )

    @Test fun thePlanSheetLightsTheOneInForceAndSaysItsWindow() {
        val plan = FastingRules.DEFAULT_PLAN
        assertEquals("16:8", R.planRow(plan).value)
        val s = R.planSheet(plan)
        assertEquals(listOf("14:10", "16:8", "18:6", "20:4"), s.choices.map { it.label })
        assertEquals(listOf("16:8"), s.choices.filter { it.lit }.map { it.label })
        assertEquals("Fast 18 h · eat 13:00–19:00", s.choices[2].line)
        assertEquals(2, R.planNamed(plan, "18:6"))
        assertNull(R.planNamed(plan, "16:8"), "the plan in force isn't picked again")
        assertNull(R.planNamed(plan, "nonsense"))
    }

    @Test fun aPlanSetAnotherWayLeadsTheSheetLit() {
        val s = R.planSheet(FastingPlan(15, 11 * 60, 20 * 60))
        assertEquals("15 h", s.choices.first().label)
        assertTrue(s.choices.first().lit)
        assertEquals(1, s.choices.count { it.lit })
    }

    @Test fun theGoalSheetSaysWhenEachGoalEndsAndHowLongIsLeft() {
        val cur = daily()
        val s = R.goalSheet(cur, now)
        assertEquals(FastingRules.TARGET_CHOICES.map { "$it h" }, s.choices.map { it.label })
        assertEquals(listOf("16 h"), s.choices.filter { it.lit }.map { it.label })
        assertEquals("Ends 21:45 · 4 h 30 m to go", s.choices.first { it.label == "18 h" }.line)
        assertEquals(R.REACHED, s.choices.first { it.label == "12 h" }.line)
        assertEquals(18, R.goalNamed(cur, "18"))
        assertNull(R.goalNamed(cur, "16"))
        assertNull(R.goalNamed(cur, "17"), "only the offered goals")
    }

    @Test fun aLongerFastOffersDaysAndAnUntilFastLeadsWithItsOwn() {
        val five = R.goalSheet(extended(), now)
        assertEquals(FastingRules.EXTENDED_CHOICES.map { it.label }, five.choices.map { it.label })
        assertEquals(listOf("5 days"), five.choices.filter { it.lit }.map { it.label })
        assertEquals("3 d 18 h to go", five.choices.first { it.label == "5 days" }.line)
        val until = extended(hours = 48, title = "Fast until Fri 18:00")
        val u = R.goalSheet(until, now)
        assertEquals("Until Fri 18:00", u.choices.first().label)
        assertEquals(1, u.choices.count { it.lit })
        assertEquals(48, R.goalNamed(until, "48"), "a day count replaces the until moment")
    }

    @Test fun theStartSheetOffersOnlyMovesThatStayInThePast() {
        val cur = daily()
        val s = R.startSheet(cur, now)
        assertEquals(listOf("−1 h", "−30 min", "+30 min"), s.choices.map { it.label })
        assertEquals("Counts from 02:45 · goal 18:45", s.choices[0].line)
        assertTrue(s.choices.none { it.lit })
        assertEquals(-60, R.startNamed(cur, now, "-60"))
        val justStarted = daily(startedAgoMin = 10)
        assertEquals(listOf("−1 h", "−30 min"), R.startSheet(justStarted, now).choices.map { it.label })
        assertNull(R.startNamed(justStarted, now, "30"))
        assertEquals("Counts an hour earlier · goal an hour sooner", R.startLine(extended(), -60))
    }
}
