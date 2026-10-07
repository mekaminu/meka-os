package os.meka.android.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac must agree rule for rule (macos/MekaOSTests/ShellNavTests.swift). */
class ShellNavTest {
    @Test
    fun closedFoldGetsTheBarOpenFoldGetsTheRail() {
        assertEquals(ShellLayout.BOTTOM_BAR, ShellNav.layoutFor(360f))
        assertEquals(ShellLayout.BOTTOM_BAR, ShellNav.layoutFor(599.9f))
        assertEquals(ShellLayout.RAIL, ShellNav.layoutFor(600f))
        assertEquals(ShellLayout.RAIL, ShellNav.layoutFor(840f))
    }

    @Test
    fun barAndRailHaveTheSameFourTabsInOrder() {
        val four = listOf("Today", "Needs you", "Calendar", "Ask")
        assertEquals(four, ShellNav.destinations(ShellLayout.BOTTOM_BAR).map { it.label })
        assertEquals(four, ShellNav.destinations(ShellLayout.RAIL).map { it.label })
    }

    @Test
    fun placesBehindAskLightAskAndGoBackToIt() {
        for (d in listOf(ShellDestination.LISTS, ShellDestination.GOALS, ShellDestination.REVIEW, ShellDestination.VAULT)) {
            assertEquals(ShellDestination.ASK, ShellNav.barSelection(d))
            assertEquals(ShellDestination.ASK, ShellNav.parent(d))
        }
        for (d in ShellNav.TABS) {
            assertEquals(d, ShellNav.barSelection(d))
            assertNull(ShellNav.parent(d))
        }
    }

    @Test
    fun contentSlidesTheWayYouMoved() {
        assertEquals(1, ShellNav.direction(ShellDestination.TODAY, ShellDestination.NEEDS_YOU))
        assertEquals(1, ShellNav.direction(ShellDestination.NEEDS_YOU, ShellDestination.CALENDAR))
        assertEquals(-1, ShellNav.direction(ShellDestination.ASK, ShellDestination.TODAY))
        assertEquals(0, ShellNav.direction(ShellDestination.LISTS, ShellDestination.LISTS))
        // Opening a place from More moves forward; back to Ask, or to any tab, moves back.
        assertEquals(1, ShellNav.direction(ShellDestination.ASK, ShellDestination.LISTS))
        assertEquals(1, ShellNav.direction(ShellDestination.ASK, ShellDestination.VAULT))
        assertEquals(-1, ShellNav.direction(ShellDestination.REVIEW, ShellDestination.ASK))
        assertEquals(-1, ShellNav.direction(ShellDestination.GOALS, ShellDestination.CALENDAR))
    }

    @Test
    fun moreListsEveryPlaceAndPaneCalendarsOnlyOnceConnected() {
        assertEquals(
            listOf("Lists", "Goals and habits", "Review", "Vault", "Work mode", "Notifications", "Activity", "Your data", "Calendars"),
            ShellNav.more(connected = true).map { it.label },
        )
        assertFalse(MoreItem.CALENDARS in ShellNav.more(connected = false))
        // Every place behind Ask is reachable from More.
        val places = ShellNav.more(connected = false).mapNotNull { it.destination }.toSet()
        assertEquals(ShellDestination.entries.filter { ShellNav.parent(it) != null }.toSet(), places)
    }

    @Test
    fun listsSaysWhatIsDue() {
        assertEquals("Waiting for · Someday · Decisions · Renewals", ShellNav.moreLine(MoreItem.LISTS, 0))
        assertEquals("1 needs you · Waiting for · Someday · Decisions · Renewals", ShellNav.moreLine(MoreItem.LISTS, 1))
        assertEquals("3 need you · Waiting for · Someday · Decisions · Renewals", ShellNav.moreLine(MoreItem.LISTS, 3))
        assertEquals("Habits, goals and fasting", ShellNav.moreLine(MoreItem.GOALS, 3))
        assertTrue(ShellNav.moreLit(MoreItem.LISTS, 2))
        assertFalse(ShellNav.moreLit(MoreItem.LISTS, 0))
        assertFalse(ShellNav.moreLit(MoreItem.REVIEW, 2))
    }

    @Test
    fun badgeIsQuietAtZeroAndCapsAtNinePlus() {
        assertNull(ShellNav.badge(0))
        assertNull(ShellNav.badge(-2))
        assertEquals("3", ShellNav.badge(3))
        assertEquals("9", ShellNav.badge(9))
        assertEquals("9+", ShellNav.badge(10))
    }

    @Test
    fun screenReadersHearTheRealCount() {
        assertEquals("Needs you, 12 waiting", ShellNav.accessibilityLabel(ShellDestination.NEEDS_YOU, 12))
        assertEquals("Needs you", ShellNav.accessibilityLabel(ShellDestination.NEEDS_YOU, 0))
        assertEquals("Today", ShellNav.accessibilityLabel(ShellDestination.TODAY, 12))
    }
}
