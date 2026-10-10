package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchHomeTest {
    private val cal = LocalCalendar.UTC
    private val hour = 3_600_000L
    private val min = 60_000L

    /** Saturday 10 October 2026, UTC. */
    private val sat = CivilDate.toEpochDay(2026, 10, 10) * CivilDate.DAY_MS
    private val day = DayWindow(sat, sat + 24 * hour, 0)
    private fun at(h: Int, m: Int = 0) = sat + h * hour + m * min

    private fun ev(id: String, title: String, from: Long, to: Long, joinUrl: String? = null) =
        CalendarEvent(id, title, from, to, false, null, "google", null, null, joinUrl = joinUrl)

    private fun task(id: String, title: String, scheduled: Long? = null, estimate: Int? = null) =
        Task(id, title, null, Lifecycle.ACTIVE, null, scheduled, estimate, 0, null, null, 0, null, false)

    private fun home(tasks: List<Task>, events: List<CalendarEvent>, at: Long, fasting: FastingView = FastingView.EMPTY) =
        WatchHomeRules.view(CoverNowRules.now(TodayProjection.project(tasks, at, day, events, cal), at, cal), fasting, at)

    @Test
    fun upNextKeepsDoneAndTomorrowAndLeavesOpenToThePhone() {
        val v = home(listOf(task("inv", "Send the invoice", scheduled = at(14), estimate = 20)), emptyList(), at(13, 50))
        assertEquals("UP NEXT", v.label)
        assertFalse(v.lit)
        assertEquals("Send the invoice", v.title)
        assertEquals("At 14:00 · 20 min", v.line)
        assertEquals(listOf(NowAction.DONE, NowAction.TOMORROW), v.buttons.map { it.action })
        assertEquals(listOf("Done", "Tomorrow"), v.buttons.map { it.label })
        assertEquals(listOf(true, false), v.buttons.map { it.primary })
        assertTrue(v.buttons.all { it.targetId == "inv" })
    }

    @Test
    fun anEventStartingSoonHasNoButtonsOnTheWristButSaysWhatFollows() {
        val v = home(
            listOf(task("inv", "Send the invoice")),
            listOf(ev("c", "Call with Tunde", at(14), at(15), joinUrl = "https://meet.google.com/abc-defg-hij")),
            at(13, 48),
        )
        assertEquals("IN 12 MIN", v.label)
        assertTrue(v.lit)
        assertEquals("Call with Tunde", v.title)
        assertTrue(v.buttons.isEmpty())
        assertEquals("Then: Send the invoice", v.thenLine)
    }

    @Test
    fun aClearDayHasNoButtonsAndARestingFastOffersStart() {
        val v = home(emptyList(), emptyList(), at(16))
        assertEquals(CoverNowRules.CLEAR_LABEL.uppercase(), v.label)
        assertTrue(v.buttons.isEmpty())
        assertFalse(v.fast.running)
        assertEquals(WatchHomeRules.NOT_FASTING, v.fast.title)
        assertEquals(WatchHomeRules.START_FAST, v.fast.button)
        assertEquals("", v.fast.clock)
        assertNull(v.fast.fastId)
        assertEquals(FastingView.EMPTY.plan.line, v.fast.line)
    }

    @Test
    fun aRunningFastShowsHoursAndMinutesItsGoalAndEnd() {
        val started = at(16) - 14 * hour - 5 * min - 9_000
        val fast = FastNow("f", started, 16, started + 16 * hour, false, "Started 01:54", "Goal 16 h · at 17:54")
        val v = home(emptyList(), emptyList(), at(16), FastingView.EMPTY.copy(current = fast))
        assertTrue(v.fast.running)
        assertEquals("Fasting · goal 16 h", v.fast.title)
        assertEquals("14:05", v.fast.clock)
        assertEquals("Goal 16 h · at 17:54", v.fast.line)
        assertEquals(WatchHomeRules.END_FAST, v.fast.button)
        assertEquals("f", v.fast.fastId)
        assertFalse(v.fast.reached)
        assertTrue(v.fast.progress > 0.87f && v.fast.progress < 0.89f)

        val past = WatchHomeRules.fast(FastingView.EMPTY.copy(current = fast), started + 17 * hour)
        assertTrue(past.reached)
        assertEquals(1f, past.progress)
    }

    @Test
    fun anExtendedFastShowsItsDayLineAndTheClockPassesADay() {
        val started = at(16) - 62 * hour
        val fast = FastNow("x", started, 120, started + 120 * hour, false, "", "Goal 5 days · Mon 18:00", extended = true, title = "5-day fast", dayLine = "Day 3 of 5 · 62 h")
        val f = WatchHomeRules.fast(FastingView.EMPTY.copy(current = fast), at(16))
        assertEquals("62:00", f.clock)
        assertEquals("Day 3 of 5 · 62 h", f.line)
        assertEquals("5-day fast", f.title)
    }

    @Test
    fun theWatchLooksAgainOnTheNextMinute() {
        assertEquals(at(16, 1), WatchHomeRules.nextTickMs(at(16) + 1))
        assertEquals(at(16, 1), WatchHomeRules.nextTickMs(at(16)))
        assertEquals("0:00", WatchHomeRules.clock(-5))
    }
}
