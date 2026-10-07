package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverNowTest {
    private val cal = LocalCalendar.UTC
    private val hour = 3_600_000L
    private val min = 60_000L

    /** Wednesday 7 October 2026, UTC. */
    private val wed = CivilDate.toEpochDay(2026, 10, 7) * CivilDate.DAY_MS
    private val day = DayWindow(wed, wed + 24 * hour, 0)
    private fun at(h: Int, m: Int = 0) = wed + h * hour + m * min

    private fun ev(
        id: String, title: String, from: Long, to: Long, location: String? = null, joinUrl: String? = null,
    ) = CalendarEvent(id, title, from, to, false, location, "google", null, null, joinUrl = joinUrl)

    private fun task(id: String, title: String, scheduled: Long? = null, estimate: Int? = null, due: Long? = null) =
        Task(id, title, null, Lifecycle.ACTIVE, due, scheduled, estimate, 0, null, null, 0, null, false)

    private fun now(tasks: List<Task>, events: List<CalendarEvent>, at: Long): NowView =
        CoverNowRules.now(TodayProjection.project(tasks, at, day, events, cal), at, cal)

    @Test
    fun anEventStartingSoonComesFirstWithJoinAndTheTaskAfterIt() {
        val v = now(
            tasks = listOf(task("inv", "Send the invoice")),
            events = listOf(ev("c", "Call with Tunde", at(14), at(15), joinUrl = "https://meet.google.com/abc-defg-hij")),
            at = at(13, 48),
        )
        assertEquals(NowKind.EVENT_SOON, v.kind)
        assertEquals("In 12 min", v.label)
        assertTrue(v.lit)
        assertEquals("Call with Tunde", v.title)
        assertEquals("14:00–15:00", v.line)
        assertEquals(listOf(NowAction.JOIN, NowAction.OPEN_EVENT), v.actions)
        assertEquals("https://meet.google.com/abc-defg-hij", v.join?.url)
        assertEquals("Then: Send the invoice", v.thenLine)
        assertEquals("inv", v.thenTask?.id)
    }

    @Test
    fun aTaskUpNextBeatsAnEventLaterInTheHour() {
        val v = now(
            tasks = listOf(task("inv", "Send the invoice", scheduled = at(14), estimate = 20)),
            events = listOf(ev("d", "Dentist", at(14, 30), at(15), location = "High St Surgery")),
            at = at(13, 50),
        )
        assertEquals(NowKind.TASK, v.kind)
        assertEquals("Up next", v.label)
        assertFalse(v.lit)
        assertEquals("Send the invoice", v.title)
        assertEquals("At 14:00 · 20 min", v.line)
        assertEquals(listOf(NowAction.DONE, NowAction.TOMORROW, NowAction.OPEN_TASK), v.actions)
        assertEquals("Then: Dentist at 14:30", v.thenLine)
        assertEquals("d", v.thenEvent?.id)
    }

    @Test
    fun anEventWithinTheHourAndNoTaskOffersMapsForItsPlace() {
        val v = now(emptyList(), listOf(ev("d", "Dentist", at(14, 30), at(15), location = "High St Surgery")), at(13, 50))
        assertEquals(NowKind.EVENT_SOON, v.kind)
        assertEquals("In 40 min", v.label)
        assertEquals("14:30–15:00 · High St Surgery", v.line)
        assertEquals(listOf(NowAction.MAPS, NowAction.OPEN_EVENT), v.actions)
        assertEquals("High St Surgery", v.mapsQuery)
        assertNull(v.thenLine)
    }

    @Test
    fun anEventThatJustStartedComesBeforeTheTaskThenALongOneStepsAside() {
        val events = listOf(ev("w", "Workshop", at(13), at(17), joinUrl = "https://teams.microsoft.com/l/meetup-join/x"))
        val tasks = listOf(task("inv", "Send the invoice"))
        val early = now(tasks, events, at(13, 6))
        assertEquals(NowKind.EVENT_RUNNING, early.kind)
        assertEquals("Now · ends 17:00", early.label)
        assertEquals(NowAction.JOIN, early.actions.first())
        assertEquals("Then: Send the invoice", early.thenLine)
        // Twenty minutes in, the task comes first and the workshop is the "then".
        val later = now(tasks, events, at(13, 20))
        assertEquals(NowKind.TASK, later.kind)
        assertEquals("Anytime today", later.line)
        assertEquals("Then: Workshop until 17:00", later.thenLine)
        // With no task, the running event stays.
        val alone = now(emptyList(), events, at(15))
        assertEquals(NowKind.EVENT_RUNNING, alone.kind)
        assertEquals("Workshop", alone.title)
    }

    @Test
    fun aLocationThatIsOnlyALinkIsNeitherAPlaceNorMaps() {
        val v = now(emptyList(), listOf(ev("z", "Sync", at(14), at(14, 30), location = "https://zoom.us/j/123")), at(13, 55))
        assertEquals("14:00–14:30", v.line)
        assertEquals(listOf(NowAction.JOIN, NowAction.OPEN_EVENT), v.actions)
        assertNull(v.mapsQuery)
    }

    @Test
    fun clearSaysSoAndCountsWhatNeedsYou() {
        val v = now(emptyList(), emptyList(), at(16))
        assertEquals(NowKind.CLEAR, v.kind)
        assertEquals(CoverNowRules.CLEAR_LABEL, v.label)
        assertEquals(CoverNowRules.NOTHING, v.title)
        assertTrue(v.actions.isEmpty())
        assertNull(v.needsYouLine)

        val overdue = listOf(task("a", "Renew passport", due = at(9)), task("b", "Pay the plumber", due = at(10)))
        val needs = now(overdue, emptyList(), at(16))
        assertEquals("2 need you", needs.needsYouLine)
        assertEquals("1 needs you", now(overdue.take(1), emptyList(), at(16)).needsYouLine)
    }

    @Test
    fun anEndedEventIsNeverNow() {
        val v = now(emptyList(), listOf(ev("s", "Standup", at(9, 30), at(9, 45))), at(10))
        assertEquals(NowKind.CLEAR, v.kind)
    }
}
