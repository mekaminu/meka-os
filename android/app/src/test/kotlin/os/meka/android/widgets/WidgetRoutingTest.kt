package os.meka.android.widgets

import os.meka.android.shell.ShellDestination
import os.meka.core.domain.HomeWidgetsView
import os.meka.core.domain.NowKind
import os.meka.core.domain.WidgetFast
import os.meka.core.domain.WidgetNeedsYou
import os.meka.core.domain.WidgetNext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WidgetRoutingTest {
    private val next = WidgetNext(NowKind.EVENT_SOON, "Starts in", true, "Call with Tunde", "14:00–15:00 · Room 4", 1_000L, "Then: Send the invoice")
    private val needs = WidgetNeedsYou(3, "3", "need you", "Renew the permit", "Overdue · was due yesterday 17:00", true)
    private val fast = WidgetFast(true, "Fasting · goal 16 h", "Goal at 12:00", 500L, 40, false)

    @Test
    fun eachWidgetOpensItsPlace() {
        assertEquals(ShellDestination.TODAY, WidgetRouting.destination(HomeWidget.NEXT_UP))
        assertEquals(ShellDestination.NEEDS_YOU, WidgetRouting.destination(HomeWidget.NEEDS_YOU))
        assertEquals(ShellDestination.GOALS, WidgetRouting.destination(HomeWidget.FAST))
        assertEquals(3, HomeWidget.entries.map { WidgetRouting.requestCode(it) }.toSet().size)
        HomeWidget.entries.forEach { assertTrue(WidgetRouting.requestCode(it) !in setOf(1, 2)) } // the capture widget's
        // The Talk widget's tap (Talk without tapping the mic, slice 2) opens the same screen, so its code stays apart.
        val talk = os.meka.android.ask.TalkAutoListen.WIDGET_REQUEST_CODE
        assertTrue(talk !in HomeWidget.entries.map { WidgetRouting.requestCode(it) } + listOf(1, 2, 400, 401, 410, 411))
    }

    @Test
    fun theChronometerBaseMovesTheWallClockTargetOntoElapsedTime() {
        // 12 minutes ahead on the wall clock is 12 minutes ahead on the elapsed clock.
        assertEquals(50_000L + 12 * 60_000L, WidgetRouting.chronometerBase(1_000_000L + 12 * 60_000L, 1_000_000L, 50_000L))
        // A fast started 4 hours ago counts up from 4 hours before now.
        assertEquals(20_000_000L - 4 * 3_600_000L, WidgetRouting.chronometerBase(10L * 3_600_000L, 14L * 3_600_000L, 20_000_000L))
    }

    @Test
    fun theClockAloneDoesNotRedraw() {
        val a = HomeWidgetsView(next, needs, fast, 5L)
        val b = HomeWidgetsView(next, needs, fast, 6L)
        assertEquals(WidgetRouting.signature(a), WidgetRouting.signature(b))
        assertNotEquals(WidgetRouting.signature(a), WidgetRouting.signature(a.copy(needsYou = needs.copy(count = 2, countText = "2"))))
        assertNotEquals(WidgetRouting.signature(a), WidgetRouting.signature(a.copy(fast = fast.copy(progressPercent = 41))))
    }

    @Test
    fun screenReadersHearWhatTheWidgetSays() {
        assertEquals("Next up. Call with Tunde. 14:00–15:00 · Room 4. Then: Send the invoice", WidgetRouting.describe(next))
        assertEquals("Next up. Up next. Send the invoice", WidgetRouting.describe(WidgetNext(NowKind.TASK, "Up next", false, "Send the invoice", null, null, null)))
        assertEquals("3 need you. Renew the permit. Overdue · was due yesterday 17:00", WidgetRouting.describe(needs))
        assertEquals("Nothing needs you", WidgetRouting.describe(WidgetNeedsYou(0, "", "Nothing needs you", null, null, false)))
        assertEquals("Fasting · goal 16 h. Goal at 12:00", WidgetRouting.describe(fast))
    }
}
