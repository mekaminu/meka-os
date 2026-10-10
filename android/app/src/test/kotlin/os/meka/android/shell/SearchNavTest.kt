package os.meka.android.shell

import os.meka.android.lists.ListTab
import os.meka.core.domain.SearchTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Mac must agree rule for rule (macos/MekaOSTests/SearchNavTests.swift). */
class SearchNavTest {
    @Test
    fun listResultsOpenTheirTabGoalsAndHabitsOpenGoals() {
        assertEquals(ShellDestination.LISTS, SearchNav.destination(SearchTarget.LISTS_WAITING))
        assertEquals(ShellDestination.LISTS, SearchNav.destination(SearchTarget.LISTS_RENEWALS))
        assertEquals(ShellDestination.GOALS, SearchNav.destination(SearchTarget.GOALS))
        assertEquals(ListTab.WAITING, SearchNav.listTab(SearchTarget.LISTS_WAITING))
        assertEquals(ListTab.SOMEDAY, SearchNav.listTab(SearchTarget.LISTS_SOMEDAY))
        assertEquals(ListTab.DECISIONS, SearchNav.listTab(SearchTarget.LISTS_DECISIONS))
        assertEquals(ListTab.RENEWALS, SearchNav.listTab(SearchTarget.LISTS_RENEWALS))
        assertEquals(ShellDestination.LISTS, SearchNav.destination(SearchTarget.LISTS_SHOPPING))
        assertEquals(ListTab.SHOPPING, SearchNav.listTab(SearchTarget.LISTS_SHOPPING))
        assertEquals("Opens Shopping", SearchNav.hint(SearchTarget.LISTS_SHOPPING))
        assertNull(SearchNav.listTab(SearchTarget.GOALS))
    }

    @Test
    fun tasksOpenInPlaceAndEventsGoNowhere() {
        assertNull(SearchNav.destination(SearchTarget.TASK))
        assertNull(SearchNav.destination(SearchTarget.INFO))
        assertEquals("Opens the task", SearchNav.hint(SearchTarget.TASK))
        assertNull(SearchNav.hint(SearchTarget.INFO))
    }
}
