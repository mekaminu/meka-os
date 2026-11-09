package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayInkTest {
    @Test
    fun goldIsOnlyForTapsAndTheNowMark() {
        assertEquals(setOf(TodayInk.TAP, TodayInk.NOW_MARK), TodayInk.entries.filter(TodayInkRules::isGold).toSet())
    }

    @Test
    fun linesThatOnlyTellYouSomethingAreNeverGold() {
        assertFalse(TodayInkRules.isGold(TodayInkRules.detail(running = true)))
        assertEquals(TodayInk.LIVE, TodayInkRules.detail(running = true))
        assertEquals(TodayInk.QUIET, TodayInkRules.detail(running = false))
        assertFalse(TodayInkRules.isGold(TodayInkRules.nowWords))
        assertEquals(TodayInk.ATTENTION, TodayInkRules.clash(past = false))
        assertEquals(TodayInk.QUIET, TodayInkRules.clash(past = true))
    }

    @Test
    fun aBandsBarIsTheNowMarkOnlyWhileYoureInIt() {
        assertTrue(TodayInkRules.isGold(TodayInkRules.bar(running = true)))
        assertEquals(TodayInk.QUIET, TodayInkRules.bar(running = false))
    }

    @Test
    fun lateAndCarriedTasksAreAmberAndFlaggedOnesKeepTheirRed() {
        assertEquals(TodayInk.ATTENTION, TodayInkRules.taskLine(late = true, carried = false, flagged = false))
        assertEquals(TodayInk.ATTENTION, TodayInkRules.taskLine(late = false, carried = true, flagged = false))
        assertEquals(TodayInk.LIVE, TodayInkRules.taskLine(late = false, carried = false, flagged = false))
        assertNull(TodayInkRules.taskLine(late = true, carried = true, flagged = true))
    }
}
