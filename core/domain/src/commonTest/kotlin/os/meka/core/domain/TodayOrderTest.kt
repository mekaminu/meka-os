package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodayOrderTest {
    @Test
    fun calmTodayUpNextAndTheTimelineLeadWithTheCardsAfterThem() {
        val order = TodayOrderRules.ORDER
        assertEquals(TodaySlot.entries.size, order.size)
        assertEquals(order.toSet().size, order.size)
        assertTrue(order.indexOf(TodaySlot.UP_NEXT) < order.indexOf(TodaySlot.TIMELINE))
        assertTrue(order.indexOf(TodaySlot.TIMELINE) < order.indexOf(TodaySlot.CARDS))
        assertEquals(TodaySlot.DONE, order.last())
        assertTrue(TodayOrderRules.leads(TodaySlot.UP_NEXT))
        assertTrue(TodayOrderRules.leads(TodaySlot.TIMELINE))
        assertFalse(TodayOrderRules.leads(TodaySlot.CARDS))
        assertFalse(TodayOrderRules.leads(TodaySlot.ANYTIME))
    }

    @Test
    fun theOpeningStaggerArrivesInTheOrderTheSectionsSit() {
        val steps = TodayOrderRules.ORDER.map { TodayOrderRules.stagger(it) }
        assertEquals(steps.sorted(), steps)
        assertTrue(steps.all { it in 1 until TodayOrderRules.SECTIONS })
        // The timeline's rows follow its label and come before the cards.
        assertTrue(TodayOrderRules.TIMELINE_ROWS_STEP > TodayOrderRules.stagger(TodaySlot.TIMELINE))
        assertTrue(TodayOrderRules.TIMELINE_ROWS_STEP < TodayOrderRules.stagger(TodaySlot.CARDS))
    }
}
