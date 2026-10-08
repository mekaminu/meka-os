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
            listOf(
                "Lists", "Goals and habits", "Review", "Vault", "Morning brief", "News", "Shut down the day", "Work mode", "Notifications",
                "Appearance", "Calendars", "Activity", "Your data",
            ),
            ShellNav.more(connected = true).map { it.label },
        )
        assertFalse(MoreItem.CALENDARS in ShellNav.more(connected = false))
        // Every place behind Ask is reachable from More.
        val places = ShellNav.more(connected = false).mapNotNull { it.destination }.toSet()
        assertEquals(ShellDestination.entries.filter { ShellNav.parent(it) != null }.toSet(), places)
    }

    @Test
    fun moreIsGroupedIntoPlacesDailyAndSettings() {
        // Fold review 2026-10-08, item 10.
        val sections = ShellNav.moreSections(connected = true)
        assertEquals(listOf("Places", "Daily", "Settings"), sections.map { it.group.label })
        assertEquals(listOf("Lists", "Goals and habits", "Review", "Vault"), sections[0].items.map { it.label })
        assertEquals(listOf("Morning brief", "News", "Shut down the day"), sections[1].items.map { it.label })
        assertEquals(
            listOf("Work mode", "Notifications", "Appearance", "Calendars", "Activity", "Your data"),
            sections[2].items.map { it.label },
        )
        // The same rows as the flat list, in the same order; the places are exactly the Places section.
        assertEquals(ShellNav.more(connected = true), sections.flatMap { it.items })
        assertTrue(sections[0].items.all { it.destination != null })
        assertTrue(sections.drop(1).flatMap { it.items }.all { it.destination == null })
        // Not connected: Calendars leaves Settings, the sections stay.
        val offline = ShellNav.moreSections(connected = false)
        assertEquals(3, offline.size)
        assertFalse(MoreItem.CALENDARS in offline[2].items)
    }

    @Test
    fun moreSectionsStaggerOneStepApart() {
        assertEquals(listOf(2, 4, 6), (0..2).map { ShellNav.moreLabelStep(it) })
        assertEquals(listOf(3, 4, 5, 6), (0..3).map { ShellNav.moreRowStep(0, it) })
        assertEquals(5, ShellNav.moreRowStep(1, 0))
        assertEquals(12, ShellNav.moreRowStep(2, 5))
        // Each label comes before its own rows.
        for (s in 0..2) assertTrue(ShellNav.moreLabelStep(s) < ShellNav.moreRowStep(s, 0))
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
    fun headerMovesIntoMoreWithWorkSayingWhereYouAre() {
        // Today clarity, slice 2: Today's header keeps Search and Plan my day; the rest is in More.
        assertEquals("At work · Work hours and the Work switch", ShellNav.moreLine(MoreItem.WORK, 0, atWork = true))
        assertEquals("Off work · Work hours and the Work switch", ShellNav.moreLine(MoreItem.WORK, 3, atWork = false))
        assertEquals("Your day, who you're waiting on and headlines", ShellNav.moreLine(MoreItem.BRIEF, 2))
        assertEquals("Tick off, carry over and see tomorrow", ShellNav.moreLine(MoreItem.SHUTDOWN, 0))
        assertFalse(ShellNav.moreLit(MoreItem.WORK, 2))
        // Appearance unfolds in place; every other row opens a place or a pane.
        assertEquals(listOf(MoreItem.APPEARANCE), MoreItem.entries.filter { ShellNav.unfoldsInPlace(it) })
        assertTrue(listOf(MoreItem.BRIEF, MoreItem.NEWS, MoreItem.SHUTDOWN, MoreItem.APPEARANCE).all { it.destination == null })
        assertEquals("Barça, AI and the headlines · topics and sources", ShellNav.moreLine(MoreItem.NEWS, 3))
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

    @Test
    fun everyTabHasItsOwnIconAndThePlacesNone() {
        val glyphs = ShellNav.TABS.map { ShellNav.glyph(it) }
        assertEquals(listOf(TabGlyph.DAY, TabGlyph.NEEDS, TabGlyph.CALENDAR, TabGlyph.ASK), glyphs)
        ShellDestination.entries.filter { it !in ShellNav.TABS }.forEach { assertNull(ShellNav.glyph(it)) }
    }
}
