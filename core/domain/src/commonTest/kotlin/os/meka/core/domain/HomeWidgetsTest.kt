package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeWidgetsTest {
    private val cal = LocalCalendar.UTC
    private val hour = 3_600_000L
    private val min = 60_000L

    /** Wednesday 7 October 2026, UTC. */
    private val wed = CivilDate.toEpochDay(2026, 10, 7) * CivilDate.DAY_MS
    private val day = DayWindow(wed, wed + 24 * hour, 0)
    private fun at(h: Int, m: Int = 0) = wed + h * hour + m * min

    private fun ev(id: String, title: String, from: Long, to: Long, location: String? = null, allDay: Boolean = false) =
        CalendarEvent(id, title, from, to, allDay, location, "google", null, null)

    private fun task(id: String, title: String, scheduled: Long? = null, estimate: Int? = null, due: Long? = null) =
        Task(id, title, null, Lifecycle.ACTIVE, due, scheduled, estimate, 0, null, null, 0, null, false)

    private fun view(
        tasks: List<Task> = emptyList(),
        events: List<CalendarEvent> = emptyList(),
        at: Long,
        listsDue: Int = 0,
        listsLine: String? = null,
        fasting: FastingView = FastingView.EMPTY,
    ): HomeWidgetsView {
        val today = TodayProjection.project(tasks, at, day, events, cal)
        val now = CoverNowRules.now(today, at, cal)
        val stack = NeedsYouStackRules.build(today, listsLine, at, cal)
        return HomeWidgetRules.view(now, today, listsDue, stack, fasting, at, cal)
    }

    @Test
    fun anEventNotYetStartedCountsDownToItsStart() {
        val v = view(events = listOf(ev("c", "Call with Tunde", at(14), at(15), location = "Room 4")), at = at(13, 48))
        assertEquals(NowKind.EVENT_SOON, v.next.kind)
        assertEquals("Starts in", v.next.label)
        assertTrue(v.next.lit)
        assertEquals("Call with Tunde", v.next.title)
        assertEquals("14:00–15:00 · Room 4", v.next.line)
        assertEquals(at(14), v.next.countdownToMs)
        assertEquals(at(14), v.nextChangeMs) // it starts: the words change
    }

    @Test
    fun aRunningEventSaysWhenItEndsWithNoCountdown() {
        val v = view(events = listOf(ev("c", "Call with Tunde", at(14), at(15))), at = at(14, 4))
        assertEquals(NowKind.EVENT_RUNNING, v.next.kind)
        assertEquals("Now · ends 15:00", v.next.label)
        assertNull(v.next.countdownToMs)
        assertEquals(at(14, 10), v.nextChangeMs) // ten minutes in, Up next takes over
    }

    @Test
    fun theUpNextTaskThenTheEventAfterIt() {
        val v = view(
            tasks = listOf(task("inv", "Send the invoice")),
            events = listOf(ev("s", "Standup", at(15), at(15, 15))),
            at = at(14, 20),
        )
        assertEquals(NowKind.TASK, v.next.kind)
        assertEquals("Up next", v.next.label)
        assertFalse(v.next.lit)
        assertEquals("Send the invoice", v.next.title)
        assertEquals("Then: Standup at 15:00", v.next.thenLine)
        assertNull(v.next.countdownToMs)
        assertEquals(at(14, 45), v.nextChangeMs) // Standup comes into the last quarter hour
    }

    @Test
    fun clearWithNothingGoingOnLooksAgainWithinHalfAnHour() {
        val v = view(at = at(10))
        assertEquals(NowKind.CLEAR, v.next.kind)
        assertEquals("You're clear", v.next.label)
        assertEquals("Nothing else planned today", v.next.title)
        assertEquals(at(10, 30), v.nextChangeMs)
        // Late in the evening, midnight comes first.
        assertEquals(at(24), view(at = at(23, 50)).nextChangeMs)
    }

    @Test
    fun allDayEventsDontSetTheClock() {
        val v = view(events = listOf(ev("h", "Bank holiday", at(0), at(24), allDay = true)), at = at(10))
        assertEquals(at(10, 30), v.nextChangeMs)
    }

    @Test
    fun needsYouCountsLikeTheTabBadgeAndShowsTheTopCard() {
        val none = view(at = at(10)).needsYou
        assertEquals(0, none.count)
        assertEquals("", none.countText)
        assertEquals("Nothing needs you", none.label)
        assertNull(none.top)
        assertNull(none.why)
        assertFalse(none.urgent)

        val overdue = task("old", "Renew the parking permit", due = at(-7))
        val one = view(tasks = listOf(overdue), at = at(10)).needsYou
        assertEquals(1, one.count)
        assertEquals("1", one.countText)
        assertEquals("needs you", one.label)
        assertEquals("Renew the parking permit", one.top)
        assertTrue(one.why!!.startsWith("Overdue"))
        assertTrue(one.urgent)

        // Due chases, reviews and renewals count too; with no task waiting the lists card is on top.
        val lists = view(at = at(10), listsDue = 3, listsLine = "2 to chase · 1 renewal due").needsYou
        assertEquals(3, lists.count)
        assertEquals("need you", lists.label)
        assertEquals("From your lists", lists.top)
        assertFalse(lists.urgent)

        assertEquals("9+", view(at = at(10), listsDue = 12, listsLine = "12 to chase").needsYou.countText)
    }

    @Test
    fun aRunningFastTicksFromItsStartAndLightsAtTheGoal() {
        val world = SyncWorld()
        world.clock.nowMs = at(-4) // 20:00 the evening before
        val f = Fasting(world.device("android").replica, { "F1" }, { world.clock.nowMs })
        f.start()

        world.clock.nowMs = at(8) // 12 h of 16
        var fast = view(at = world.clock.nowMs, fasting = f.view()).fast
        assertTrue(fast.running)
        assertEquals("Fasting · goal 16 h", fast.title)
        assertEquals("Goal at 12:00", fast.line)
        assertEquals(at(-4), fast.startedAtMs)
        assertEquals(75, fast.progressPercent)
        assertFalse(fast.reached)
        assertEquals(at(8, 30), view(at = world.clock.nowMs, fasting = f.view()).nextChangeMs)
        assertEquals(at(12), view(at = at(11, 50), fasting = f.view()).nextChangeMs) // the goal

        world.clock.nowMs = at(12, 30)
        fast = view(at = world.clock.nowMs, fasting = f.view()).fast
        assertEquals("Goal reached at 12:00", fast.line)
        assertEquals(100, fast.progressPercent)
        assertTrue(fast.reached)

        f.end()
        fast = view(at = world.clock.nowMs, fasting = f.view()).fast
        assertFalse(fast.running)
        assertEquals("No fast running", fast.title)
        assertNull(fast.startedAtMs)
        assertEquals(0, fast.progressPercent)
        assertTrue(fast.line!!.isNotBlank())
    }

    @Test
    fun noFastAndNoHistoryHasNoLine() {
        val fast = view(at = at(10)).fast
        assertFalse(fast.running)
        assertNull(fast.line)
    }
}
