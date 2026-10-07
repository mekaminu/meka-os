package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OngoingTest {
    private val cal = LocalCalendar.UTC
    private val hour = 3_600_000L
    private val min = 60_000L

    /** Wednesday 7 October 2026, UTC. */
    private val wed = CivilDate.toEpochDay(2026, 10, 7) * CivilDate.DAY_MS
    private val day = DayWindow(wed, wed + 24 * hour, 0)
    private fun at(h: Int, m: Int = 0) = wed + h * hour + m * min

    private fun ev(
        id: String, title: String, from: Long, to: Long, location: String? = null, joinUrl: String? = null,
        allDay: Boolean = false, provider: String = "google",
    ) = CalendarEvent(id, title, from, to, allDay, location, provider, null, null, joinUrl = joinUrl)

    private fun view(events: List<CalendarEvent>, at: Long, fasting: FastingView = FastingView.EMPTY): OngoingView =
        OngoingRules.view(TodayProjection.project(emptyList(), at, day, events, cal), fasting, at, cal)

    @Test
    fun nothingGoingOnMeansNoItemsAndALookAgainAtMidnight() {
        val v = view(emptyList(), at(10))
        assertTrue(v.items.isEmpty())
        assertNull(v.menuBar)
        assertEquals(at(24), v.nextChangeMs)
    }

    @Test
    fun theNextEventCountsDownFromHalfAnHourBefore() {
        val call = ev("c", "Call with Tunde", at(14), at(15), location = "Room 4", joinUrl = "https://meet.google.com/abc-defg-hij")
        val early = view(listOf(call), at(13, 20))
        assertTrue(early.items.isEmpty())
        assertEquals(at(13, 30), early.nextChangeMs)

        val v = view(listOf(call), at(13, 48))
        val item = v.items.single()
        assertEquals(OngoingKind.MEETING, item.kind)
        assertEquals("meeting-c", item.key)
        assertEquals("Call with Tunde", item.title)
        assertEquals("Starts 14:00 · Room 4", item.text)
        assertEquals("Next event", item.publicTitle)
        assertEquals(at(14), item.clockBaseMs)
        assertTrue(item.countDown)
        assertTrue(item.lit)
        assertEquals("https://meet.google.com/abc-defg-hij", item.join?.url)
        assertEquals("12 min", item.short)
        assertEquals("12 min", v.menuBar)
        assertEquals(at(14), v.nextChangeMs)
    }

    @Test
    fun aStartedEventStaysTenMinutesCountingUpThenGoes() {
        val call = ev("c", "Call with Tunde", at(14), at(15))
        val v = view(listOf(call), at(14, 4))
        val item = v.items.single()
        assertEquals("Started 14:00 · ends 15:00", item.text)
        assertFalse(item.countDown)
        assertEquals(at(14), item.clockBaseMs)
        assertEquals("Now", v.menuBar)
        assertEquals(at(14, 10), v.nextChangeMs)

        assertTrue(view(listOf(call), at(14, 10)).items.isEmpty())
    }

    @Test
    fun theSoonestUpcomingEventWinsOverOneThatJustStarted() {
        val events = listOf(
            ev("a", "Standup", at(9), at(9, 15)),
            ev("b", "Design review", at(9, 20), at(10)),
        )
        val v = view(events, at(9, 5))
        assertEquals("meeting-b", v.items.single().key)
        assertEquals("15 min", v.menuBar)
    }

    @Test
    fun allDayEventsNeverCountDown() {
        val v = view(listOf(ev("h", "Bank holiday", at(0), at(24), allDay = true)), at(23, 50))
        assertTrue(v.items.isEmpty())
    }

    @Test
    fun aFixtureSaysKickOffOnTheLockScreen() {
        val v = view(listOf(ev("f", "Barcelona v Sevilla", at(20), at(22), provider = "fixtures")), at(19, 45))
        assertEquals("Kick-off", v.items.single().publicTitle)
    }

    @Test
    fun aRunningFastCountsUpWithItsProgressAndLightsAtTheGoal() {
        val world = SyncWorld()
        world.clock.nowMs = at(20)
        val f = Fasting(world.device("android").replica, { "F1" }, { world.clock.nowMs })
        f.start()

        world.clock.nowMs = at(24) // 4 h in, of 16
        var v = view(emptyList(), world.clock.nowMs, f.view())
        var item = v.items.single()
        assertEquals(OngoingKind.FAST, item.kind)
        assertEquals("fast-F1", item.key)
        assertEquals("Fasting · goal 16 h", item.title)
        assertEquals("Goal at 12:00 · started 20:00 yesterday", item.text)
        assertEquals("Fasting", item.publicTitle)
        assertEquals(at(20), item.clockBaseMs)
        assertFalse(item.countDown)
        assertEquals(25, item.progressPercent)
        assertFalse(item.lit)
        assertEquals("4 h 00 m", v.menuBar)
        assertEquals(at(36), v.nextChangeMs) // the goal, before midnight comes round again

        world.clock.nowMs = at(36, 30)
        v = view(emptyList(), world.clock.nowMs, f.view())
        item = v.items.single()
        assertEquals("Goal reached at 12:00 · started 20:00 yesterday", item.text)
        assertEquals(100, item.progressPercent)
        assertTrue(item.lit)

        f.end()
        assertTrue(view(emptyList(), world.clock.nowMs, f.view()).items.isEmpty())
    }

    @Test
    fun anEventComesBeforeTheFastAndTakesTheMenuBar() {
        val world = SyncWorld()
        world.clock.nowMs = at(8)
        val f = Fasting(world.device("android").replica, { "F1" }, { world.clock.nowMs })
        f.start()
        world.clock.nowMs = at(13, 50)
        val v = view(listOf(ev("c", "Call", at(14), at(15))), world.clock.nowMs, f.view())
        assertEquals(listOf(OngoingKind.MEETING, OngoingKind.FAST), v.items.map { it.kind })
        assertEquals("10 min", v.menuBar)
    }

    @Test
    fun anExtendedFastLeadsWithItsDayAndLooksAgainWhenTheDayTurns() {
        val world = SyncWorld()
        world.clock.nowMs = at(20)
        val f = Fasting(world.device("android").replica, { "F1" }, { world.clock.nowMs })
        f.startExtended(120)
        world.clock.nowMs = at(20 + 50) // Friday 22:00, day 3
        val v = view(emptyList(), world.clock.nowMs, f.view())
        val item = v.items.single()
        assertEquals("5-day fast · Day 3 of 5", item.title)
        assertEquals("Goal at Mon 20:00 · started Wed 20:00", item.text)
        assertEquals(41, item.progressPercent)
        assertEquals(at(72), v.nextChangeMs) // midnight comes before day 4 (Saturday 20:00)
        world.clock.nowMs = at(72, 30)
        assertEquals(at(20 + 72), view(emptyList(), world.clock.nowMs, f.view()).nextChangeMs) // then day 4
    }
}
