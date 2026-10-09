package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Living Today, slice 3: the ring carries the day (work band, gym, the arc on now, a running fast, tapping an arc). */
class DayRingDayTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    // Tue 6 Oct 2026 in London (BST, +1 h): local midnight = 5 Oct 23:00 UTC.
    private val start = 1_791_244_800_000L - hour
    private val day = DayWindow(startMs = start, endMs = start + 24 * hour, utcOffsetMs = hour)
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun at(h: Int, m: Int = 0) = start + h * hour + m * min

    private fun near(expected: Float, actual: Float) =
        assertTrue(kotlin.math.abs(expected - actual) < 0.01f, "expected $expected, was $actual")

    private fun ev(id: String, from: Long, to: Long) = CalendarEvent(id, id, from, to, false, null, "google", null, null)

    private fun build(
        events: List<CalendarEvent> = emptyList(),
        sessions: List<BookedSession> = emptyList(),
        work: List<WorkBlock> = emptyList(),
        now: Long,
    ) = DayRingRules.build(emptyList(), events, sessions, 0, now, day, cal, work)

    @Test
    fun theArcOnNowIsCurrentAndTheGymIsHighlighted() {
        val gym = BookedSession("gym", "Gym", "Push", day.epochDay, at(17, 45), at(18, 45), "Today 17:45")
        val r = build(listOf(ev("standup", at(9, 30), at(9, 45)), ev("call", at(10), at(11))), listOf(gym), now = at(10, 15))
        assertEquals(listOf(false, true, false), r.arcs.map { it.current })
        assertEquals(listOf(false, false, true), r.arcs.map { it.highlighted })
        // At the very end an arc is over, not on.
        val after = build(listOf(ev("call", at(10), at(11))), now = at(11))
        assertFalse(after.arcs.single().current)
        assertTrue(after.arcs.single().past)
    }

    @Test
    fun workHoursAreAFaintBandNotAnArc() {
        val work = WorkBlock(at(9), at(17, 30), 9 * 60, 17 * 60 + 30)
        val r = build(work = listOf(work), now = at(10))
        assertTrue(r.arcs.isEmpty())
        val band = r.work.single()
        assertEquals(9 * 60, band.startMinute)
        assertEquals(17 * 60 + 30, band.endMinute)
        assertEquals(135f, band.startDegrees)
        assertEquals(127.5f, band.sweepDegrees)
        assertTrue(band.current)
        assertFalse(build(work = listOf(work), now = at(18)).work.single().current)
        // A night shift's tail from yesterday is clipped at midnight.
        val tail = build(work = listOf(WorkBlock(at(-3), at(6), 21 * 60, 6 * 60)), now = at(5)).work.single()
        assertEquals(0, tail.startMinute)
        assertEquals(6 * 60, tail.endMinute)
        // No work today: no band.
        assertTrue(build(now = at(10)).work.isEmpty())
    }

    @Test
    fun aRunningFastIsAnInnerArcAtItsOwnClockTimes() {
        val started = at(-4) + 5 * min // 20:05 yesterday
        val fast = FastNow("f", started, 16, started + 16 * hour, false, "Started 20:05 yesterday", "Goal 16 h · at 12:05")
        val arc = assertNotNull(DayRingRules.fastArc(fast, at(8, 5), cal))
        near((20 * 60 + 5) * 360f / 1440, arc.startDegrees)
        near(240f, arc.sweepDegrees) // 16 h of the 24-hour dial
        near(0.75f, arc.progress) // 12 of 16 h
        near(180f, arc.filledDegrees)
        assertFalse(arc.reachedGoal)
        val done = assertNotNull(DayRingRules.fastArc(fast, at(13), cal))
        assertTrue(done.reachedGoal)
        near(240f, done.filledDegrees)
        // Not running, or not started yet: nothing.
        assertNull(DayRingRules.fastArc(null, at(8), cal))
        assertNull(DayRingRules.fastArc(fast, started - min, cal))
    }

    @Test
    fun anExtendedFastFillsTheWholeInnerCircleByItsProgress() {
        val started = at(-24 * 2)
        val fast = FastNow("f", started, 120, started + 120 * hour, false, "", "", extended = true)
        val arc = assertNotNull(DayRingRules.fastArc(fast, at(0), cal))
        assertEquals(0f, arc.startDegrees)
        assertEquals(360f, arc.sweepDegrees)
        near(0.4f, arc.progress) // 48 of 120 h
        near(144f, arc.filledDegrees)
    }

    @Test
    fun aTapOnTheRingFindsItsAngleAndTheCentreIsLeftClear() {
        near(0f, DayRingRules.tapDegrees(0f, -100f, 100f)!!) // straight up: midnight
        near(90f, DayRingRules.tapDegrees(100f, 0f, 100f)!!) // right: 06:00
        near(180f, DayRingRules.tapDegrees(0f, 100f, 100f)!!) // down: noon
        near(270f, DayRingRules.tapDegrees(-110f, 0f, 100f)!!) // left: 18:00
        assertNull(DayRingRules.tapDegrees(0f, -40f, 100f)) // the centre's text
        assertNull(DayRingRules.tapDegrees(0f, -130f, 100f)) // outside the ring
        assertNull(DayRingRules.tapDegrees(0f, -100f, 0f))
    }

    @Test
    fun tappingAnArcOpensItAndFreeTimeOpensNothing() {
        val gym = BookedSession("gym", "Gym", "Push", day.epochDay, at(17, 45), at(18, 45), "Today 17:45")
        val r = build(
            listOf(ev("standup", at(9, 30), at(9, 35)), ev("call", at(10), at(11)), ev("lunch", at(12), at(13))),
            listOf(gym), now = at(10, 30),
        )
        fun deg(h: Int, m: Int = 0) = (h * 60 + m) * 360f / 1440
        assertEquals("e-lunch", DayRingRules.arcAt(r, deg(12, 30))?.id)
        assertEquals("s-gym", DayRingRules.arcAt(r, deg(18))?.id)
        // A 5-minute stand-up is only 1.25° long: a tap a little beside it still opens it.
        assertEquals("e-standup", DayRingRules.arcAt(r, deg(9, 20))?.id)
        // Between the stand-up and the call, both are in reach: the one on now wins.
        assertEquals("e-call", DayRingRules.arcAt(r, deg(9, 50))?.id)
        assertNull(DayRingRules.arcAt(r, deg(15)))
        assertNull(DayRingRules.arcAt(DayRing.EMPTY, deg(12)))
    }

    @Test
    fun aTapJustBeforeMidnightReachesAnArcThatStartsAtTheTop() {
        val r = build(listOf(ev("night", at(-1), at(1))), now = at(0, 30))
        assertEquals("e-night", DayRingRules.arcAt(r, 358f)?.id)
    }

    private fun shut(done: Boolean, first: TomorrowRow?) = ShutdownView.EMPTY.copy(
        doneToday = done,
        tomorrow = ShutdownView.EMPTY.tomorrow.copy(first = first),
    )

    @Test
    fun afterShutDownTheRingLooksAheadToTomorrowsFirstThing() {
        val standup = TomorrowRow("e-standup", "09:30", "Standup", true, null)
        // Not shut down yet: the ring shows today.
        assertNull(DayRingRules.tomorrow(shut(false, standup)))
        val t = assertNotNull(DayRingRules.tomorrow(shut(true, standup)))
        assertEquals(570, t.minute)
        near(142.5f, t.degrees!!)
        assertEquals("Tomorrow 09:30", t.headline)
        assertEquals("Standup", t.caption)
        assertEquals("Day shut down. Tomorrow: first thing 09:30 Standup.", t.spokenLine)

        // The centre and the spoken line follow it; without it the ring reads as today.
        val ring = build(now = at(20)).copy(tomorrow = t)
        assertEquals("Tomorrow 09:30", ring.centreLine(0))
        assertEquals("Standup", ring.centreCaption(0))
        assertEquals(t.spokenLine, ring.spokenLine)
        val today = build(now = at(22, 30))
        assertEquals("Evening", today.centreLine(0))
        assertEquals("Nothing to do", today.centreCaption(0))

        // Nothing timed tomorrow: no mark, a quiet line.
        val none = assertNotNull(DayRingRules.tomorrow(shut(true, null)))
        assertNull(none.degrees)
        assertEquals("Tomorrow", none.headline)
        assertEquals("Nothing booked yet", none.caption)
        assertEquals("Day shut down. Tomorrow: nothing booked yet.", none.spokenLine)

        // A long title is shortened like the evening glance.
        val long = TomorrowRow("e-x", "08:00", "Quarterly planning with the whole regional leadership team", true, null)
        assertTrue(DayRingRules.tomorrow(shut(true, long))!!.caption.endsWith("…"))
    }

    @Test
    fun onlyAClockTimeGivesAMinute() {
        assertEquals(570, DayRingRules.clockMinute("09:30"))
        assertEquals(0, DayRingRules.clockMinute("00:00"))
        assertEquals(1439, DayRingRules.clockMinute(" 23:59 "))
        assertNull(DayRingRules.clockMinute(null))
        assertNull(DayRingRules.clockMinute("All day"))
        assertNull(DayRingRules.clockMinute("Until 01:00"))
        assertNull(DayRingRules.clockMinute("24:00"))
    }
}
