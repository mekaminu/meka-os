package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacCommandCentreTest {
    private val r = MacCommandCentreRules

    @Test
    fun threeColumnsOnlyFromElevenHundredPoints() {
        assertEquals(CommandLayout.SINGLE, r.layout(599f))
        assertEquals(CommandLayout.TWO, r.layout(600f))
        // His 1000-pt window: two columns, not three cramped ones.
        assertEquals(CommandLayout.TWO, r.layout(1000f))
        assertEquals(CommandLayout.TWO, r.layout(1099.9f))
        assertEquals(CommandLayout.THREE, r.layout(1100f))
        assertEquals(CommandLayout.THREE, r.layout(1440f))
    }

    @Test
    fun twoColumnsPutNeedsYouBesideTodayAndComingUpUnderIt() {
        assertEquals(listOf(CommandColumn.TODAY, CommandColumn.NEEDS_YOU), r.columns(CommandLayout.TWO, taskOpen = false))
        assertEquals(listOf(CommandColumn.TODAY, CommandColumn.DETAIL), r.columns(CommandLayout.TWO, taskOpen = true))
        assertTrue(r.comingUpUnderToday(CommandLayout.TWO))
        assertFalse(r.comingUpUnderToday(CommandLayout.THREE))
        assertFalse(r.comingUpUnderToday(CommandLayout.SINGLE))
        assertEquals(
            listOf(CommandColumn.TODAY, CommandColumn.NEEDS_YOU, CommandColumn.COMING_UP),
            r.columns(CommandLayout.THREE, taskOpen = false),
        )
        assertEquals(
            listOf(CommandColumn.TODAY, CommandColumn.DETAIL, CommandColumn.COMING_UP),
            r.columns(CommandLayout.THREE, taskOpen = true),
        )
        assertEquals(listOf(CommandColumn.TODAY), r.columns(CommandLayout.SINGLE, taskOpen = true))
    }

    @Test
    fun needsYouIsListedInTodayOnlyWhenAlone() {
        assertTrue(r.todayListsNeedsYou(CommandLayout.SINGLE))
        assertFalse(r.todayListsNeedsYou(CommandLayout.TWO))
        assertFalse(r.todayListsNeedsYou(CommandLayout.THREE))
    }

    @Test
    fun wideWindowGivesNeedsYouTheMostRoom() {
        val w = r.widths(1440f, CommandLayout.THREE)
        assertEquals(3, w.size)
        assertEquals(1440f, w.sum())
        assertEquals(460f, w[0]) // 32%
        assertEquals(374f, w[2]) // 26%
        assertEquals(606f, w[1]) // the rest, ≈ 42%
        assertTrue(w[1] > w[0] && w[0] > w[2] - 100f)
    }

    @Test
    fun minimumsHoldAtTheThreeColumnBreakpoint() {
        val w = r.widths(1100f, CommandLayout.THREE)
        assertEquals(1100f, w.sum())
        assertEquals(352f, w[0])
        assertEquals(286f, w[2])
        assertTrue(w[0] >= r.TODAY_MIN && w[1] >= r.SIDE_MIN && w[2] >= r.COMING_UP_MIN)
    }

    @Test
    fun twoColumnsSplitRoughlyInHalf() {
        val w = r.widths(1000f, CommandLayout.TWO)
        assertEquals(listOf(520f, 480f), w)
        // At the narrowest two-column width Today keeps its minimum and Needs you its own.
        val narrow = r.widths(600f, CommandLayout.TWO)
        assertEquals(600f, narrow.sum())
        assertEquals(312f, narrow[0])
        assertTrue(narrow[1] >= r.SIDE_MIN)
    }

    @Test
    fun aloneTodayTakesTheWholeWidthAndNothingIsNegative() {
        assertEquals(listOf(540f), r.widths(540f, CommandLayout.SINGLE))
        assertEquals(listOf(0f), r.widths(-5f, CommandLayout.SINGLE))
        // Before the first measurement (width 0) nothing goes negative for Today.
        assertTrue(r.widths(0f, CommandLayout.THREE)[0] >= 0f)
    }

    @Test
    fun edgesFadeOnlyWhereThereIsMore() {
        // At the top with more below: only the foot fades.
        assertEquals(EdgeFades(top = false, bottom = true), r.edgeFades(0f, 1200f, 600f))
        // Half way: both.
        assertEquals(EdgeFades(top = true, bottom = true), r.edgeFades(300f, 1200f, 600f))
        // At the end: only the top.
        assertEquals(EdgeFades(top = true, bottom = false), r.edgeFades(600f, 1200f, 600f))
        // Content that fits: neither, and a trackpad bounce doesn't count.
        assertEquals(EdgeFades(top = false, bottom = false), r.edgeFades(0f, 400f, 600f))
        assertEquals(EdgeFades(top = false, bottom = false), r.edgeFades(0.5f, 600.5f, 600f))
        assertEquals(EdgeFades(top = false, bottom = false), r.edgeFades(-20f, 400f, 600f))
    }
}

class MacHeaderTest {
    private val r = MacHeaderRules

    private fun health(vararg rows: HealthRow, todayLine: String? = null, critical: Boolean = false) =
        HealthView(rows.toList(), "", rows.count { it.needsLook }, critical, todayLine, 0L)

    private val notificationsOff = HealthRow(
        "notifications", "Notifications", "Off for Navilife in System Settings · nothing can reach this Mac",
        HealthState.BAD, HealthFix.NOTIFICATIONS_ON, "Turn on",
    )

    @Test
    fun setupAndNotificationsOffAreOneQuietLine() {
        val h = health(notificationsOff, todayLine = "Notifications · Off for Navilife in System Settings", critical = true)
        val line = r.setupLine(setupShown = true, stepsLeft = 4, health = h)!!
        assertEquals("Finish setting up · 4 steps · notifications are off", line.text)
        assertEquals("Open Setup", line.action)
        assertEquals(MacHeaderOpens.SETUP, line.opens)
        assertFalse(line.critical)
        assertEquals("Finish setting up: 4 steps left, and notifications are off.", line.spoken)
    }

    @Test
    fun oneStepAndSeveralIssues() {
        val other = HealthRow("ai", "The AI", "Can't be reached", HealthState.WARN)
        val battery = HealthRow("battery", "Battery", "Restricted", HealthState.BAD)
        assertEquals("Finish setting up · 1 step", r.setupLine(true, 1, health(battery))!!.text)
        assertEquals("Finish setting up · 1 step · The AI needs a look", r.setupLine(true, 1, health(other, battery))!!.text)
        assertEquals("Finish setting up · 2 steps · 2 things need a look", r.setupLine(true, 2, health(other, notificationsOff))!!.text)
        assertEquals("Finish setting up · 3 steps", r.setupLine(true, 3, null)!!.text)
    }

    @Test
    fun withNothingToSetUpHealthKeepsItsOwnLine() {
        val h = health(notificationsOff, todayLine = "Notifications · Off for Navilife in System Settings", critical = true)
        val line = r.setupLine(setupShown = false, stepsLeft = 4, health = h)!!
        assertEquals("Notifications · Off for Navilife in System Settings", line.text)
        assertEquals(MacHeaderOpens.HEALTH, line.opens)
        assertTrue(line.critical)
        assertEquals(null, r.setupLine(setupShown = false, stepsLeft = 0, health = health()))
        assertEquals(null, r.setupLine(setupShown = true, stepsLeft = 0, health = null))
    }

    @Test
    fun noTickerBesideTheNewsListAndSidebarWhenWide() {
        assertTrue(r.tickerInToday(CommandLayout.SINGLE))
        assertFalse(r.tickerInToday(CommandLayout.TWO))
        assertFalse(r.tickerInToday(CommandLayout.THREE))
        assertFalse(r.sidebarOnOpen(1000f))
        assertTrue(r.sidebarOnOpen(1200f))
        assertTrue(r.sidebarOnOpen(1440f))
    }
}

/** Mac presentation pass, item 9: the columns ease in with Today's opening, once. */
class MacColumnEntranceTest {
    private val r = MacCommandCentreRules
    private val three = listOf(CommandColumn.TODAY, CommandColumn.NEEDS_YOU, CommandColumn.COMING_UP)

    @Test
    fun columnsFollowTodayOneStepApartSlidingInFromTheRight() {
        val today = r.entrance(three, CommandColumn.TODAY, opening = true, reduced = false)
        val needs = r.entrance(three, CommandColumn.NEEDS_YOU, opening = true, reduced = false)
        val coming = r.entrance(three, CommandColumn.COMING_UP, opening = true, reduced = false)
        assertEquals(listOf(0, 1, 2), listOf(today.step, needs.step, coming.step))
        assertEquals(0f, today.slidePt)
        assertEquals(r.COLUMN_SLIDE_PT, needs.slidePt)
        assertEquals(r.COLUMN_SLIDE_PT, coming.slidePt)
        assertTrue(needs.staggers && coming.staggers)
    }

    @Test
    fun theDetailTakesNeedsYousStep() {
        val cols = r.columns(CommandLayout.TWO, taskOpen = true)
        assertEquals(1, r.entrance(cols, CommandColumn.DETAIL, opening = true, reduced = false).step)
    }

    @Test
    fun outsideTheOpeningNothingStaggersAgain() {
        // Closing a task brings Needs you back: it cross-fades in whole, its cards don't replay the opening.
        val back = r.entrance(three, CommandColumn.NEEDS_YOU, opening = false, reduced = false)
        assertFalse(back.staggers)
    }

    @Test
    fun reduceMotionNeverSlides() {
        val e = r.entrance(three, CommandColumn.COMING_UP, opening = true, reduced = true)
        assertEquals(0f, e.slidePt)
        assertEquals(2, e.step)
    }
}
