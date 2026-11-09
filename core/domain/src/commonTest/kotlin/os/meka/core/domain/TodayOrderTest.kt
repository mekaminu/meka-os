package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodayOrderTest {
    @Test
    fun theTasksSitUnderNowAndTheEveningWithTheScheduleUnderThemAndTheCardsLast() {
        val order = TodayOrderRules.ORDER
        assertEquals(TodaySlot.entries.size, order.size)
        assertEquals(order.toSet().size, order.size)
        assertTrue(order.indexOf(TodaySlot.UP_NEXT) < order.indexOf(TodaySlot.TIMELINE))
        assertTrue(order.indexOf(TodaySlot.TIMELINE) < order.indexOf(TodaySlot.CARDS))
        assertEquals(TodaySlot.CARDS, order.last())
        // The Today rebuild: the day's tasks under Now and the evening card, the schedule under them.
        assertTrue(order.indexOf(TodaySlot.UP_NEXT) < order.indexOf(TodaySlot.EVENING))
        assertTrue(order.indexOf(TodaySlot.EVENING) < order.indexOf(TodaySlot.ANYTIME))
        assertTrue(order.indexOf(TodaySlot.ANYTIME) < order.indexOf(TodaySlot.DONE))
        assertTrue(order.indexOf(TodaySlot.DONE) < order.indexOf(TodaySlot.TIMELINE))
        assertTrue(TodayOrderRules.leads(TodaySlot.UP_NEXT))
        assertTrue(TodayOrderRules.leads(TodaySlot.TIMELINE))
        assertFalse(TodayOrderRules.leads(TodaySlot.CARDS))
        assertTrue(TodayOrderRules.leads(TodaySlot.ANYTIME))
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
