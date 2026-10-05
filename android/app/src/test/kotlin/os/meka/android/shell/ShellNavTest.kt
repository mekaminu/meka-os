package os.meka.android.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun barHasFiveRailHasAllSixInOrder() {
        assertEquals(
            listOf("Today", "Needs you", "Lists", "Goals", "Review"),
            ShellNav.destinations(ShellLayout.BOTTOM_BAR).map { it.label },
        )
        assertEquals(
            listOf("Today", "Needs you", "Lists", "Goals", "Review", "Vault"),
            ShellNav.destinations(ShellLayout.RAIL).map { it.label },
        )
    }

    @Test
    fun vaultIsNotLitOnTheBarButEverythingElseIs() {
        assertNull(ShellNav.barSelection(ShellDestination.VAULT, ShellLayout.BOTTOM_BAR))
        assertEquals(ShellDestination.VAULT, ShellNav.barSelection(ShellDestination.VAULT, ShellLayout.RAIL))
        assertEquals(ShellDestination.GOALS, ShellNav.barSelection(ShellDestination.GOALS, ShellLayout.BOTTOM_BAR))
    }

    @Test
    fun contentSlidesTheWayYouMoved() {
        assertEquals(1, ShellNav.direction(ShellDestination.TODAY, ShellDestination.REVIEW))
        assertEquals(-1, ShellNav.direction(ShellDestination.REVIEW, ShellDestination.NEEDS_YOU))
        assertEquals(0, ShellNav.direction(ShellDestination.LISTS, ShellDestination.LISTS))
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
