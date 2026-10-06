package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MorningBriefTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private val hourMs = 3_600_000L
    private val minMs = 60_000L
    private var n = 0
    private fun ids(): String = "B${n++}"

    private fun tasks(d: Device) = Tasks(d.replica, ::ids, { world.clock.nowMs })
    private fun brief(d: Device) = MorningBrief(d.replica, { world.clock.nowMs })
    private fun lists(d: Device) = Lists(d.replica, ::ids, { world.clock.nowMs })

    private val a = world.device("android")
    private val m = world.device("mac")
    private val ta = tasks(a)
    private val ba = brief(a)
    private val la = lists(a)
    private val ra = Renewals(a.replica, ::ids, { world.clock.nowMs })
    private val schedule = WorkSchedule.DEFAULT // Mon–Fri 09:00–17:30

    init {
        // Start every test on a Tuesday at 07:30 UTC.
        val today = world.clock.nowMs.floorDiv(dayMs)
        val toTuesday = (9 - CivilDate.isoDayOfWeek(today)) % 7
        world.clock.nowMs = (today + toTuesday) * dayMs + 7 * hourMs + 30 * minMs
    }

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun window(day: Long = today()) = DayWindow(day * dayMs, (day + 1) * dayMs)
    private fun at(day: Long, h: Int, min: Int = 0) = day * dayMs + h * hourMs + min * minMs
    private fun view(
        b: MorningBrief = ba, t: Tasks = ta, events: List<CalendarEvent> = emptyList(), quiet: QuietHours = QuietHours.DEFAULT,
        listsView: ListsView = la.view(ta.all(), ra.view()), goals: GoalsView = GoalsView.EMPTY, fasting: FastingView = FastingView.EMPTY,
        sched: WorkSchedule = schedule,
    ) = b.view(t.all(), events, sched, quiet, listsView, goals, fasting, window())

    private fun event(id: String, title: String, start: Long, end: Long, allDay: Boolean = false, provider: String = "google") =
        CalendarEvent(id, title, start, end, allDay, null, provider, null, null)

    @Test
    fun theBriefStartsWhenQuietHoursEndAndGoesAtNoon() {
        assertEquals(7 * 60, BriefRules.startMinute(QuietHours.DEFAULT))
        assertEquals(6 * 60 + 30, BriefRules.startMinute(QuietHours(true, 23 * 60, 6 * 60 + 30)))
        // Quiet hours off, or ending at an odd time (a night owl), fall back to 07:00.
        assertEquals(7 * 60, BriefRules.startMinute(QuietHours(false, 23 * 60, 6 * 60)))
        assertEquals(7 * 60, BriefRules.startMinute(QuietHours(true, 2 * 60, 11 * 60)))

        assertTrue(view().offered)
        world.clock.nowMs = at(today(), 6, 59)
        assertFalse(view().offered)
        assertTrue(view(quiet = QuietHours(true, 23 * 60, 6 * 60)).offered)
        world.clock.nowMs = at(today(), 11, 59)
        assertTrue(view().offered)
        world.clock.nowMs = at(today(), 12, 0)
        assertFalse(view().offered)
        assertEquals("Good afternoon", view().greeting)
    }

    @Test
    fun gotItPutsTheCardAwayOnBothDevicesUntilTomorrow() {
        ba.markSeen()
        assertFalse(view().offered)
        assertTrue(view().seenToday)
        a.sync(); m.sync()
        val onMac = view(b = brief(m), t = tasks(m))
        assertTrue(onMac.seenToday)
        assertFalse(onMac.offered)
        world.clock.advance(dayMs)
        assertTrue(view().offered)
        assertFalse(view().seenToday)
    }

    @Test
    fun todayListsEventsAndPlannedTasksInTimeOrderThenTheRest() {
        val d = today()
        ta.create(NewTask("Write the report", scheduledAtMs = at(d, 14)))
        ta.create(NewTask("Pay the plumber", dueAtMs = at(d - 1, 17)))
        ta.create(NewTask("Buy stamps"))
        ta.create(NewTask("Next week's thing", dueAtMs = at(d + 6, 9)))
        val events = listOf(
            event("e1", "Dentist", at(d, 9, 30), at(d, 10, 15)),
            event("e2", "Bank holiday", d * dayMs, (d + 1) * dayMs, allDay = true),
            event("e3", "Barça v Sevilla", at(d, 20), at(d, 22), provider = "fixtures"),
            event("e4", "Tomorrow's call", at(d + 1, 10), at(d + 1, 11)),
        )
        val v = view(events = events)
        assertEquals(
            listOf("Bank holiday", "Dentist", "Write the report", "Barça v Sevilla", "Pay the plumber", "Buy stamps"),
            v.day.map { it.title },
        )
        assertEquals(listOf("All day", "09:30", "14:00", "20:00", null, null), v.day.map { it.time })
        assertEquals("Overdue", v.day.first { it.title == "Pay the plumber" }.detail)
        assertEquals("Fixtures", v.day.first { it.title.startsWith("Barça") }.detail)
        assertEquals("3 events · 3 tasks · first at 09:30", v.daySummary)
        assertEquals(1, v.overdueCount)
        assertEquals("Work 09:00–17:30", v.workLine)
        assertTrue(v.cardLine.startsWith("3 events · 3 tasks · first at 09:30"))
    }

    @Test
    fun anEmptyWeekendMorningSaysSo() {
        world.clock.advance(4 * dayMs) // Saturday
        val v = view()
        assertNull(v.workLine)
        assertTrue(v.day.isEmpty())
        assertEquals("Nothing planned yet", v.daySummary)
        assertEquals("Nothing planned yet", v.cardLine)
        assertNull(v.waitingLine)
        assertNull(v.fastingLine)
    }

    @Test
    fun waitingOnShowsChasesDueFirstAndTheRadarWhatNeedsDoing() {
        la.addWaiting("Deposit back", "Landlord", 0)
        la.addWaiting("Quote", "Builder", 3)
        val d = today()
        ra.add("Car insurance", ObligationKind.INSURANCE, d + 10, RenewalRepeat.YEARLY, "412", 3)
        ra.add("Netflix", ObligationKind.SUBSCRIPTION, d + 60, RenewalRepeat.MONTHLY, "10.99", null)
        val decision = la.recordDecision("Keep the old car", "cheaper", 0)
        val v = view()
        assertEquals(listOf("Deposit back", "Quote"), v.waiting.map { it.title })
        assertEquals("Waiting on 2 things · 1 to chase today", v.waitingLine)
        assertEquals(listOf("Car insurance", "Review: Keep the old car"), v.attention.map { it.title })
        assertEquals("d-$decision", v.attention.last().id)
        assertEquals("Nothing planned yet · 1 to chase · 2 things on your lists", v.cardLine)
    }

    @Test
    fun aRunningFastAndHabitsAreOneLineEach() {
        val d = today()
        val fast = FastingView.EMPTY.copy(current = FastNow("f1", at(d - 1, 20, 5), 16, at(d, 12, 5), false, "Started 20:05 yesterday", ""))
        val goals = GoalsView.EMPTY
        val v = view(fasting = fast, goals = goals)
        assertEquals("Fasting · started 20:05 yesterday · goal at 12:05", v.fastingLine)
        assertNull(v.habitsLine)
    }

    @Test
    fun theMorningBriefNoticeGoesOutWhenQuietHoursEndUntilReadOrNoon() {
        val cal = LocalCalendar.UTC
        val d = today()
        val today = Today(emptyList(), null, emptyList(), emptyList())
        fun notices() = NoticeSources.collect(ListsView.EMPTY, FastingView.EMPTY, ShutdownView.EMPTY, today, world.clock.nowMs, cal, view())
        val n = notices().single { it.source == NoticeSource.BRIEF }
        assertEquals(at(d, 7), n.atMs)
        assertEquals(at(d, 12), n.expiresAtMs)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals(NoticeTarget.TODAY, n.target)
        assertEquals("brief:$d", n.key)
        ba.markSeen()
        assertTrue(notices().none { it.source == NoticeSource.BRIEF })
        world.clock.advance(dayMs)
        world.clock.nowMs = at(today(), 12, 30)
        assertTrue(notices().none { it.source == NoticeSource.BRIEF })
    }
}
