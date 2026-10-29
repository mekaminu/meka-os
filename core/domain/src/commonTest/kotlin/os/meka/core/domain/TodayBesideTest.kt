package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Today beside its calmer mockup: the Anytime label's count and the hairlines between rows. */
class TodayBesideTest {
    @Test
    fun theLabelCountsTheOpenTasksAsTheMockupDoes() {
        assertEquals("Anytime today · 4", TodayBesideRules.anytimeLabel(4))
        assertEquals("Anytime today · 1", TodayBesideRules.anytimeLabel(1))
        assertEquals("Anytime today", TodayBesideRules.anytimeLabel(0))
    }

    @Test
    fun aHairlineSitsBetweenRowsNeverUnderTheLast() {
        assertTrue(TodayBesideRules.dividerAfter(0, 4))
        assertTrue(TodayBesideRules.dividerAfter(2, 4))
        assertFalse(TodayBesideRules.dividerAfter(3, 4))
        assertFalse(TodayBesideRules.dividerAfter(0, 1))
        assertFalse(TodayBesideRules.dividerAfter(-1, 4))
    }

    @Test
    fun aRowIsTheTouchTargetAndNoTaller() {
        assertEquals(48, TodayBesideRules.ROW_DP)
        assertTrue(TodayBesideRules.ROW_WITH_LINE_MAX_DP < 60)
    }
}
