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
    fun aNewDayOffersTheBriefAgainEvenWhenItWasReadAfterMidnight() {
        // Fold review 2026-10-08: read late at night (00:30, the day's brief opened from More), the card must still
        // rise in when the morning starts.
        val d = today()
        world.clock.nowMs = at(d, 0, 30)
        ba.markSeen("Fold")
        assertTrue(view().seenToday) // before the morning starts, that read still counts (the pane says Done)
        assertFalse(view().offered)
        world.clock.nowMs = at(d, 7, 0)
        assertTrue(view().offered)
        assertFalse(view().seenToday)
        assertNull(view().readElsewhereLine)
        // Read yesterday morning: today's card comes again; read this morning: it goes until tomorrow.
        world.clock.nowMs = at(d, 7, 20)
        ba.markSeen("Fold")
        assertFalse(view().offered)
        world.clock.nowMs = at(d + 1, 7, 5)
        assertTrue(view().offered)
        // A quiet-hours end of 06:30 moves the start, and with it what counts as this morning's read.
        world.clock.nowMs = at(d + 1, 6, 40)
        ba.markSeen("Fold")
        assertFalse(view(quiet = QuietHours(true, 23 * 60, 6 * 60 + 30)).offered)
        world.clock.nowMs = at(d + 1, 7, 5)
        assertTrue(view().offered) // with the default 07:00 start, a 06:40 read was before the morning
    }

    @Test
    fun readOnTheOtherDeviceTheCardBecomesASlimLineUntilNoon() {
        brief(m).markSeen("Mac")
        m.sync(); a.sync()
        val v = view()
        assertFalse(v.offered)
        assertTrue(v.seenToday)
        assertEquals("Brief read on your Mac", v.readElsewhereLine)
        // Not on the device that read it.
        assertNull(view(b = brief(m), t = tasks(m)).readElsewhereLine)
        world.clock.nowMs = at(today(), 12, 0)
        assertNull(view().readElsewhereLine)
        // Read again on the Fold: the line goes.
        world.clock.nowMs = at(today(), 9, 0)
        ba.markSeen("Fold")
        assertNull(view().readElsewhereLine)
        assertEquals("Brief read on your other device", BriefRules.readElsewhereLine(""))
        assertEquals("Brief read on your other device", BriefRules.readElsewhereLine(null))
    }

    @Test
    fun anOldReadWithNoTimeStillCountsForItsDay() {
        assertTrue(BriefRules.readThisMorning(5, null, 5, 1_000, 2_000))
        assertFalse(BriefRules.readThisMorning(4, 1_500, 5, 1_000, 2_000))
        assertFalse(BriefRules.readThisMorning(5, 500, 5, 1_000, 2_000))
        assertTrue(BriefRules.readThisMorning(5, 500, 5, 1_000, 800))
        assertTrue(BriefRules.readThisMorning(5, 1_000, 5, 1_000, 2_000))
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
        assertEquals("3 events · 3 tasks · Barça v Sevilla today 20:00", v.cardLine)
    }

    @Test
    fun theCardSaysTheWeatherTheDayAndTheNextMatch() {
        // Fold review 2026-10-09 07:26, item 4: "16° · drizzle from 15:00 · 2 tasks · Barça v Getafe tomorrow 17:30".
        val d = today()
        ta.create(NewTask("Buy stamps"))
        ta.create(NewTask("Call the bank"))
        val events = listOf(event("f1", "Barça v Getafe", at(d + 1, 17, 30), at(d + 1, 19, 30), provider = "fixtures"))
        val v = brief(a).view(ta.all(), events, schedule, QuietHours.DEFAULT, la.view(ta.all(), ra.view()), GoalsView.EMPTY,
            FastingView.EMPTY, window(), weatherNow = "16° · drizzle from 15:00")
        assertEquals("16° · drizzle from 15:00 · 2 tasks · Barça v Getafe tomorrow 17:30", v.cardLine)
        // The pane's own summary keeps "first at".
        assertEquals("2 tasks", v.daySummary)
        // No forecast and no match: just the day.
        assertEquals("2 tasks", view().cardLine)
        // Weather on an empty day.
        assertEquals("12° · cloudy, dry today · Nothing planned yet", BriefRules.cardLine("12° · cloudy, dry today", 0, 0, null, 0, 0))
        assertEquals("1 event · 1 task · 1 to chase · 1 thing on your lists", BriefRules.cardLine(" ", 1, 1, null, 1, 1))
    }

    @Test
    fun theMatchIsTodayOrTomorrowOnNowOrToBeConfirmed() {
        val d = today()
        val cal = LocalCalendar.UTC
        fun fx(id: String, title: String, start: Long, allDay: Boolean = false) =
            event(id, title, start, start + 2 * hourMs, allDay = allDay, provider = "fixtures")
        val now = world.clock.nowMs // Tuesday 07:30
        assertNull(BriefRules.fixtureLine(emptyList(), now, cal))
        // Today's match comes before tomorrow's; a calendar event called "Barça" isn't a fixture.
        val both = listOf(fx("b", "Barça v Getafe", at(d + 1, 17, 30)), fx("a", "Barça v Sevilla", at(d, 20)), event("x", "Barça night", at(d, 9), at(d, 10)))
        assertEquals("Barça v Sevilla today 20:00", BriefRules.fixtureLine(both, now, cal))
        // Started and not over: on now; over: tomorrow's.
        assertEquals("Barça v Sevilla on now", BriefRules.fixtureLine(both, at(d, 20, 15), cal))
        assertEquals("Barça v Getafe tomorrow 17:30", BriefRules.fixtureLine(both, at(d, 22, 1), cal))
        // The day after tomorrow is too far; all-day entries don't count.
        assertNull(BriefRules.fixtureLine(listOf(fx("c", "Barça v Betis", at(d + 2, 18))), now, cal))
        assertNull(BriefRules.fixtureLine(listOf(fx("c", "Barça v Betis", d * dayMs, allDay = true)), now, cal))
        // Kick-off to be confirmed: the day only.
        assertEquals("Barça v Betis tomorrow", BriefRules.fixtureLine(listOf(fx("c", "Barça v Betis (kick-off TBC)", at(d + 1, 0))), now, cal))
        // A long title is shortened at a word.
        val long = BriefRules.fixtureLine(listOf(fx("c", "Barça v Borussia Mönchengladbach Champions League", at(d, 20))), now, cal)!!
        assertEquals("Barça v Borussia Mönchengladbach… today 20:00", long)
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

    @Test
    fun dayLinesPutEveryTitleOnOneEdgeWithTheTimeInTheCaption() {
        // Fold review 2026-10-09 07:26, item 7: no time column; tasks get a tick circle, events a dot.
        val event = BriefRules.dayLine(TomorrowRow("e-ev1", "09:30", "Standup", true, "Room 4"))
        assertNull(event.taskId)
        assertEquals("09:30 · Room 4", event.caption)
        assertFalse(event.lit)
        assertEquals("Event, Standup, 09:30 · Room 4", event.spoken)

        val allDay = BriefRules.dayLine(TomorrowRow("e-ev2", "All day", "School inset day", true, null))
        assertEquals("All day", allDay.caption)

        val planned = BriefRules.dayLine(TomorrowRow("t-task-7", "14:00", "Call the garage", false, "↻ Daily"))
        assertEquals("task-7", planned.taskId)
        assertEquals("14:00 · ↻ Daily", planned.caption)
        assertFalse(planned.lit)

        val overdue = BriefRules.dayLine(TomorrowRow("t-t2", null, "Pay the nursery", false, "Overdue · ↻ Monthly"))
        assertEquals("t2", overdue.taskId)
        assertEquals("Overdue · ↻ Monthly", overdue.caption)
        assertTrue(overdue.lit)
        assertEquals("Task, Pay the nursery, Overdue · ↻ Monthly", overdue.spoken)

        val bare = BriefRules.dayLine(TomorrowRow("t-t3", null, "Milk", false, null))
        assertNull(bare.caption)
        assertEquals("Task, Milk", bare.spoken)
    }

    @Test
    fun theBriefsDayLinesFollowItsRows() {
        val v = MorningBriefView.EMPTY.copy(day = listOf(
            TomorrowRow("e-1", "09:00", "Standup", true, null),
            TomorrowRow("t-2", null, "Milk", false, "Due 17:00"),
        ))
        assertEquals(listOf("e-1", "t-2"), v.dayLines.map { it.id })
        assertEquals(listOf(null, "2"), v.dayLines.map { it.taskId })
    }
}
