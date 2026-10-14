package os.meka.android.shell

import os.meka.core.domain.LifeTileLine
import os.meka.core.domain.LifeTileRules

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
    fun moreReachesEveryPlaceAndPaneCalendarsOnlyOnceConnected() {
        assertFalse(MoreItem.CALENDARS in ShellNav.more(connected = false))
        assertFalse(MoreItem.MAIL in ShellNav.more(connected = false))
        assertFalse(MoreItem.FAMILY in ShellNav.more(connected = false))
        assertFalse(MoreItem.WATCH in ShellNav.more(connected = false))
        // Every place behind Ask is reachable from More.
        val places = ShellNav.more(connected = false).mapNotNull { it.destination }.toSet()
        assertEquals(ShellDestination.entries.filter { ShellNav.parent(it) != null }.toSet(), places)
        // Every item is a tile, a Daily row, the Settings row or in the Settings pane, exactly once.
        val all = ShellNav.lifeTiles() + ShellNav.daily() + MoreItem.SETTINGS + ShellNav.settings(connected = true)
        assertEquals(MoreItem.entries.toSet(), all.toSet())
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun askIsALifeGridDailyAndOneSettingsRow() {
        // Fold review 2026-10-12, item 7.
        assertEquals(
            listOf("Lists", "Goals and habits", "People", "Dinners", "Bins", "Date night", "School", "Appointments", "Annual leave", "Gifts", "Trips", "Errands", "Car", "Emergency card", "What Navilife knows"),
            ShellNav.lifeTiles().map { it.label },
        )
        assertEquals(LifeTileRules.ORDER, ShellNav.lifeTiles().map { it.tile })
        assertEquals(
            listOf("Morning brief", "Week ahead", "News", "Shut down the day", "Train queue", "Voice notes", "Life admin hour", "Bedside clock", "Review"),
            ShellNav.daily().map { it.label },
        )
        assertEquals(
            listOf("About you", "Work mode", "Protected time", "Notifications", "Appearance", "Navilife's voice", "Talk", "Calendars", "Mail", "Family", "Watch", "Recovery key", "What Navilife may do", "Navilife's AI", "Activity", "Setup", "Health", "Vault", "Your data"),
            ShellNav.settings(connected = true).map { it.label },
        )
        assertFalse(MoreItem.WATCH in ShellNav.settings(connected = false))
        assertFalse(MoreItem.RECOVERY in ShellNav.settings(connected = false))
        // Only MEKA's AI just says how it is.
        assertEquals(listOf(MoreItem.AI), MoreItem.entries.filterNot { ShellNav.opens(it) })
    }

    @Test
    fun theStatusRowShowsOnlyWhenSomethingNeedsALook() {
        assertNull(ShellNav.statusItem(0, 0))
        assertEquals(MoreItem.SETUP, ShellNav.statusItem(0, 3))
        assertEquals(MoreItem.HEALTH, ShellNav.statusItem(2, 3))
    }

    @Test
    fun aTileSaysWhatIsLiveElseItsWords() {
        assertEquals(LifeTileLine("Chilli tonight", true), ShellNav.tileLine(MoreItem.DINNERS, LifeTileLine("Chilli tonight", true)))
        assertEquals(LifeTileLine("Set your bin days", false), ShellNav.tileLine(MoreItem.BINS, null))
        // Today's tiles first.
        val lines = os.meka.core.domain.LifeTileRules.lines(os.meka.core.domain.LifeTileInputs(binsTonight = "Black bin out tonight"))
        assertEquals(MoreItem.BINS, ShellNav.lifeTiles(lines).first())
        assertEquals(ShellNav.lifeTiles().toSet(), ShellNav.lifeTiles(lines).toSet())
        // Ask polish, slice 1: set-up tiles sink under "To set up"; together they're still every tile, once.
        assertEquals(MoreItem.BINS, ShellNav.lifeLive(lines).first())
        assertTrue(MoreItem.DATE_NIGHT in ShellNav.lifeToSetUp(lines))
        assertFalse(MoreItem.DATE_NIGHT in ShellNav.lifeLive(lines))
        assertEquals(ShellNav.lifeTiles().toSet(), (ShellNav.lifeLive(lines) + ShellNav.lifeToSetUp(lines)).toSet())
        assertEquals(listOf(4, 4, 4, 5), (0..3).map { ShellNav.tileStep(it, 3) })
    }

    @Test
    fun askStaggersAPartAStepAfterTheOneBefore() {
        assertEquals(2, ShellNav.STATUS_STEP)
        assertEquals(listOf(3, 5, 7), (0..2).map { ShellNav.moreLabelStep(it) })
        assertEquals(listOf(4, 4, 5, 5, 6), (0..4).map { ShellNav.tileStep(it) })
        assertEquals(6, ShellNav.moreRowStep(1, 0))
        assertEquals(7, ShellNav.SETTINGS_STEP)
        // Each label comes before its own rows.
        for (s in 0..1) assertTrue(ShellNav.moreLabelStep(s) < ShellNav.moreRowStep(s, 0))
    }

    @Test
    fun healthSaysItsSummaryAndIsLitWhenSomethingNeedsALook() {
        // Reliability first, item 3.
        assertEquals("Is everything Navilife needs working?", ShellNav.moreLine(MoreItem.HEALTH, 0))
        assertEquals("2 things need a look", ShellNav.moreLine(MoreItem.HEALTH, 0, health = "2 things need a look"))
        assertTrue(ShellNav.moreLit(MoreItem.HEALTH, 0, healthAttention = 2))
        assertFalse(ShellNav.moreLit(MoreItem.HEALTH, 3, healthAttention = 0))
        // Health shows before this device is connected (it says so), unlike Calendars.
        assertTrue(MoreItem.HEALTH in ShellNav.more(connected = false))
    }

    @Test
    fun setupSaysWhatIsLeftAndIsLitUntilAllSet() {
        // Setup checklist (Meka approved 2026-10-09).
        assertEquals("Everything Navilife can do, and what's left to set up", ShellNav.moreLine(MoreItem.SETUP, 0))
        assertEquals("3 steps left · 12 of 15 done", ShellNav.moreLine(MoreItem.SETUP, 0, setup = "3 steps left · 12 of 15 done"))
        assertTrue(ShellNav.moreLit(MoreItem.SETUP, 0, setupLeft = 3))
        assertFalse(ShellNav.moreLit(MoreItem.SETUP, 0, healthAttention = 2))
        assertTrue(MoreItem.SETUP in ShellNav.more(connected = false))
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

    @Test
    fun dailyRowsTakeTheCoresLiveLinesElseTheirWords() {
        val lines = os.meka.core.domain.DailyLines(mapOf(
            os.meka.core.domain.DailyRow.SHUTDOWN to os.meka.core.domain.DailyLine("2 left", true),
            os.meka.core.domain.DailyRow.TRAIN_QUEUE to os.meka.core.domain.DailyLine("2 saved", false),
        ))
        assertEquals(os.meka.core.domain.DailyLine("2 left", true), ShellNav.dailyLine(MoreItem.SHUTDOWN, lines))
        assertEquals("2 saved", ShellNav.dailyLine(MoreItem.TRAIN_QUEUE, lines).text)
        assertEquals(MoreItem.NEWS.line, ShellNav.dailyLine(MoreItem.NEWS, lines).text)
        assertEquals(MoreItem.REVIEW.line, ShellNav.dailyLine(MoreItem.REVIEW, lines).text)
        assertEquals("Screen saver on", ShellNav.dailyLine(MoreItem.BEDSIDE, lines, screenSaver = true).text)
        assertEquals(MoreItem.BEDSIDE.line, ShellNav.dailyLine(MoreItem.BEDSIDE, lines, screenSaver = null).text)
        // Every Daily row but Review has a core row.
        assertEquals(listOf(MoreItem.REVIEW), ShellNav.daily().filter { ShellNav.dailyRow(it) == null })
    }
}
