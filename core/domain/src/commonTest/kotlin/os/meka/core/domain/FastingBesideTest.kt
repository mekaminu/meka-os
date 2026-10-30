package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FastingBesideTest {
    @Test
    fun goalOnRowKeepsWholeHoursShort() {
        assertEquals("6h short", FastingBesideRules.goalOnRow("6h 30m short"))
        assertEquals("12h short", FastingBesideRules.goalOnRow("12h 05m short"))
        assertEquals("6h short", FastingBesideRules.goalOnRow("6h short"))
        assertEquals("40m short", FastingBesideRules.goalOnRow("40m short"))
        assertEquals("on goal", FastingBesideRules.goalOnRow("on goal"))
    }

    @Test
    fun goalOnRowFromTheScreensOwnWords() {
        val goal = FastingScreenRules.goalWord(plannedMs = 16 * 3_600_000L, actualMs = (9 * 60 + 30) * 60_000L)
        assertEquals("6h 30m short", goal)
        assertEquals("6h short", FastingBesideRules.goalOnRow(goal))
    }

    @Test
    fun spokenSaysTheGoalInFull() {
        val row = FastingHistoryRow("f1", "Sat 17 → Sun 18", "9h 30m", "6h 30m short", onGoal = false)
        assertEquals("Sat 17 to Sun 18, 9h 30m, 6h 30m short", FastingBesideRules.spoken(row))
    }

    @Test
    fun theMockupsSizes() {
        assertEquals(28, FastingBesideRules.TITLE_SP)
        assertEquals(46, FastingBesideRules.TIME_SP)
        assertEquals(48, FastingBesideRules.ROW_DP)
        assertTrue(FastingBesideRules.ROW_MAX_DP in FastingBesideRules.ROW_DP..FastingBesideRules.ROW_DP + 4)
        assertEquals(0, FastingBesideRules.ROW_GAP_DP % 8)
    }
}
