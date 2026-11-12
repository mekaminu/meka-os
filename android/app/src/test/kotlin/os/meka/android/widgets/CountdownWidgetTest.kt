package os.meka.android.widgets

import os.meka.android.MainActivity
import os.meka.core.domain.CountdownWidgetRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Countdowns, slice 4: the home-screen widget's wiring. */
class CountdownWidgetTest {
    @Test
    fun theLayoutHasALineForEveryNextOneTheRulesList() {
        assertEquals(CountdownWidgetRules.NEXT, CountdownWidget.nextSlots)
        assertEquals("Holiday · 93 days", CountdownWidget.nextLine("Holiday", "93 days"))
    }

    @Test
    fun itsCodeStaysApartFromTheOtherWidgets() {
        val others = listOf(1, 2, os.meka.android.ask.TalkAutoListen.WIDGET_REQUEST_CODE, BriefWidget.REQUEST_CODE,
            DayListWidget.OPEN_CODE, DayListWidget.ADD_CODE) +
            (0 until DayListWidget.slots).map(DayListWidget::tickCode) +
            HomeWidget.entries.map { WidgetRouting.requestCode(it) } +
            NewsWidgetSize.entries.flatMap { listOf(NewsWidgetRouting.requestCode(it), NewsWidgetRouting.requestCode(it) + 10) }
        assertTrue(CountdownWidget.OPEN_CODE !in others)
    }

    @Test
    fun aTapOpensCountdowns() {
        assertEquals("countdowns", MainActivity.OPEN_COUNTDOWNS)
    }
}
