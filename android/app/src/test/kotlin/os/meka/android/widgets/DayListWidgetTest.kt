package os.meka.android.widgets

import os.meka.android.MainActivity
import os.meka.core.domain.DayListWidgetRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The Day list, slice 6: the home-screen widget's wiring. */
class DayListWidgetTest {
    @Test
    fun theLayoutHasARowForEveryTaskTheRulesList() {
        assertEquals(DayListWidgetRules.ROWS, DayListWidget.slots)
    }

    @Test
    fun itsCodesStayApartFromTheOtherWidgets() {
        val mine = listOf(DayListWidget.OPEN_CODE, DayListWidget.ADD_CODE) + (0 until DayListWidget.slots).map(DayListWidget::tickCode)
        assertEquals(mine.size, mine.toSet().size)
        val others = listOf(1, 2, os.meka.android.ask.TalkAutoListen.WIDGET_REQUEST_CODE, BriefWidget.REQUEST_CODE) +
            HomeWidget.entries.map { WidgetRouting.requestCode(it) } +
            NewsWidgetSize.entries.flatMap { listOf(NewsWidgetRouting.requestCode(it), NewsWidgetRouting.requestCode(it) + 10) }
        mine.forEach { assertTrue(it !in others, "code $it is taken") }
    }

    @Test
    fun addOpensTheDayListReadyToTypeAndTheHeaderOnlyToLook() {
        assertEquals("day_list_add", MainActivity.OPEN_DAY_LIST_ADD)
        assertNotEquals(MainActivity.OPEN_DAY_LIST, MainActivity.OPEN_DAY_LIST_ADD)
        assertEquals("+ Add tasks", DayListWidgetRules.ADD) // the layout's @string/widget_day_list_add
    }
}
