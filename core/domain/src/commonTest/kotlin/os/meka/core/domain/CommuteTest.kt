package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The commute counts everywhere, slice 1: the setting, office days and the wake alarm. */
class CommuteTest {
    private val day = CivilDate.DAY_MS
    private val min = 60_000L
    private val cal = LocalCalendar.UTC
    private val thu = 20734L // Thursday 8 October 2026 (the short day, 09:00–15:30)
    private val fri = thu + 1
    private val sat = thu + 2
    private val work = WorkHours(WorkSchedule.DEFAULT)

    private fun ms(d: Long, minute: Int) = d * day + minute * min
    private fun ev(title: String, d: Long, minute: Int) =
        CalendarEvent(title, title, ms(d, minute), ms(d, minute + 30), false, null, "google", null, null)
    private fun wake(d: Long, hours: WorkHours, events: List<CalendarEvent> = emptyList(), buffer: Int = 60) =
        AlarmRules.suggest(AlarmRules.firstCommitment(d, events, hours, cal), buffer)

    @Test
    fun theSettingReadsDefaultsAndRefusesOddTimes() {
        assertEquals(CommuteSetting.DEFAULT, CommuteRules.read(null))
        assertEquals(80, CommuteSetting.DEFAULT.toWorkMin)
        assertEquals("About 1 h 20 each way · change it below", CommuteRules.summary(CommuteSetting.DEFAULT))
        val own = CommuteRules.read(mapOf(CommuteFields.TO_WORK to FieldValue.Int64(75), CommuteFields.HOME to FieldValue.Int64(90)))
        assertEquals(CommuteSetting(75, 90, chosen = true), own)
        assertEquals("1 h 15 there · 1 h 30 home", CommuteRules.summary(own))
        assertEquals("1 h each way", CommuteRules.summary(CommuteSetting(60, 60, chosen = true)))
        // Out of range or not in fives reads as the default.
        assertEquals(80, CommuteRules.read(mapOf(CommuteFields.TO_WORK to FieldValue.Int64(7))).toWorkMin)
        assertEquals(80, CommuteRules.read(mapOf(CommuteFields.HOME to FieldValue.Int64(83))).homeMin)
        assertEquals(listOf(45, 60, 70, 75, 80, 90, 100, 120), CommuteRules.choices(75))
        assertEquals(CommuteRules.CHOICES, CommuteRules.choices(80))
    }

    @Test
    fun onlyOfficeDaysHaveACommute() {
        assertEquals(80, CommuteRules.toWorkOn(work, fri))
        assertEquals(80, CommuteRules.homeOn(work, thu))
        assertNull(CommuteRules.toWorkOn(work, sat), "a day off")
        assertNull(CommuteRules.toWorkOn(WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(fri)), fri), "from home")
        assertNull(CommuteRules.toWorkOn(WorkHours(WorkSchedule.DEFAULT, leaveDays = setOf(fri)), fri), "annual leave")
        assertNull(CommuteRules.toWorkOn(WorkHours(WorkSchedule.DEFAULT, HolidayCalendar(mapOf(fri to "Bank holiday"))), fri))
    }

    @Test
    fun theWakeAlarmCountsTheCommuteOnOfficeDays() {
        // Work at 09:00 − 1 h 20 − 1 h = 06:40, and the line says why.
        val office = wake(fri, work)!!
        assertEquals(6 * 60 + 40, office.minute)
        assertEquals("Work at 09:00 · 1 h 20 commute · 1 h to get ready", office.line)
        // Thursday's short day still starts at 09:00.
        assertEquals(6 * 60 + 40, wake(thu, work)!!.minute)
        // From home: no commute.
        val home = wake(fri, WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(fri)))!!
        assertEquals(8 * 60, home.minute)
        assertEquals("Work at 09:00 · 1 h to get ready", home.line)
        // His own times.
        val longer = WorkHours(WorkSchedule.DEFAULT, commute = CommuteSetting(95, 80, chosen = true))
        assertEquals(6 * 60 + 25, wake(fri, longer)!!.minute)
        // An event at 08:30 at home comes after he must leave (07:40): work still decides.
        assertEquals("Work at 09:00 · 1 h 20 commute · 1 h to get ready", wake(fri, work, listOf(ev("School run", fri, 8 * 60 + 30)))!!.line)
        // An event before he'd leave wins, with no commute in its line.
        val early = wake(fri, work, listOf(ev("Gym", fri, 6 * 60 + 30)))!!
        assertEquals(5 * 60 + 30, early.minute)
        assertEquals("Gym at 06:30 · 1 h to get ready", early.line)
        // Never before 04:00.
        assertEquals(4 * 60, wake(fri, WorkHours(WorkSchedule.DEFAULT, commute = CommuteSetting(240, 80, chosen = true)), buffer = 180)!!.minute)
    }

    @Test
    fun theSetAlarmSaysTheCommuteToo() {
        val world = SyncWorld()
        val fold = world.device("android")
        world.clock.nowMs = ms(thu, 21 * 60)
        val a = Alarms(fold.replica, { world.clock.nowMs }, cal)
        assertEquals("Suggested from Work at 09:00 · 1 h 20 commute · 1 h to get ready", a.wakeView(emptyList(), work).line)
        assertTrue(a.setWake(6 * 60 + 40, "Work at 09:00"))
        assertEquals("Alarm set · Work at 09:00 · 1 h 20 commute, 1 h to get ready", a.wakeView(emptyList(), work).line)
        assertTrue(a.setWake(7 * 60, "Work at 09:00"))
        val later = a.wakeView(emptyList(), work)
        assertTrue(later.suggestionEarlier)
        assertEquals("Alarm set · Work at 09:00 now, so 06:40 gives you 1 h and the 1 h 20 commute", later.line)
    }

    @Test
    fun theCommuteSyncsAndRidesOnTheWorkState() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        world.clock.nowMs = ms(thu, 21 * 60)
        val foldWork = WorkMode(fold.replica, nowMs = { world.clock.nowMs })
        val macWork = WorkMode(mac.replica, nowMs = { world.clock.nowMs })
        assertFalse(foldWork.commute().chosen)
        assertFalse(foldWork.setCommute(83, 80), "not in fives")
        assertFalse(foldWork.setCommute(80, 300), "over 4 h")
        assertTrue(foldWork.setCommute(90, 70))
        assertFalse(foldWork.setCommute(90, 70), "no change")
        fold.sync(); mac.sync()
        assertEquals(CommuteSetting(90, 70, chosen = true), macWork.commute())
        val clock = LocalClock(4, 21 * 60)
        assertEquals(CommuteSetting(90, 70, chosen = true), macWork.state(clock, thu).commute)
        // Work hours built from the state carry it, so the wake alarm on the Mac counts 1 h 30.
        assertEquals(90, CommuteRules.toWorkOn(macWork.hours(clock, thu), fri))
        // Setting the default explicitly still counts as set.
        assertTrue(macWork.setCommute(80, 80))
        assertTrue(macWork.commute().chosen)
    }

    // ---- Slice 2: Today's commute bands and the Calendar's line ----

    private fun timeline(now: Long, d: Long, hours: WorkHours?, events: List<CalendarEvent> = emptyList()) =
        TodayProjection.project(emptyList(), now, CalendarAgenda.window(d, cal), events, cal, work = hours).timeline

    private fun rows(tl: DayTimeline) = tl.rows.map {
        when {
            it.kind == TimelineKind.GAP -> "gap:${it.title}"
            it.kind == TimelineKind.NOW && it.detail != null -> "now:${it.detail}"
            else -> it.id
        }
    }

    @Test
    fun anOfficeDayHasABandEachSideOfWork() {
        val bands = CommuteRules.bands(work, fri, cal)
        assertEquals(listOf("07:40–09:00", "17:30–18:50"), bands.map { it.label })
        assertTrue(bands[0].toWork)
        assertEquals(ms(fri, 7 * 60 + 40), bands[0].startMs)
        assertEquals(ms(fri, 18 * 60 + 50), bands[1].endMs)
        // Thursday's short day: home about 16:50.
        assertEquals("15:30–16:50", CommuteRules.bands(work, thu, cal)[1].label)
        // No bands from home, on leave, on a bank holiday or at the weekend.
        assertTrue(CommuteRules.bands(WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(fri)), fri, cal).isEmpty())
        assertTrue(CommuteRules.bands(WorkHours(WorkSchedule.DEFAULT, leaveDays = setOf(fri)), fri, cal).isEmpty())
        assertTrue(CommuteRules.bands(WorkHours(WorkSchedule.DEFAULT, HolidayCalendar(mapOf(fri to "Bank holiday"))), fri, cal).isEmpty())
        assertTrue(CommuteRules.bands(work, sat, cal).isEmpty())
        // His own times move them.
        val own = WorkHours(WorkSchedule.DEFAULT, commute = CommuteSetting(60, 95, chosen = true))
        assertEquals(listOf("08:00–09:00", "17:30–19:05"), CommuteRules.bands(own, fri, cal).map { it.label })
        // A shift late in the day: the commute home is cut at midnight; an early one's commute starts at midnight.
        val late = WorkHours(WorkSchedule(setOf(5), 15 * 60, 23 * 60))
        val lateBands = CommuteRules.bands(late, fri, cal)
        assertEquals(ms(fri + 1, 0), lateBands[1].endMs)
        assertEquals("23:00–00:20", lateBands[1].label)
        val early = WorkHours(WorkSchedule(setOf(5), 60, 9 * 60))
        assertEquals(ms(fri, 0), CommuteRules.bands(early, fri, cal)[0].startMs)
        // A night shift has no commute home on the day it starts.
        val night = WorkHours(WorkSchedule(setOf(5), 22 * 60, 6 * 60))
        assertEquals(listOf(true), CommuteRules.bands(night, fri, cal).map { it.toWork })
    }

    @Test
    fun todaysTimelineShowsTheCommuteAsBandsNotEvents() {
        // 06:30 on an office day: free until he leaves, the commute, work, the commute home.
        val morning = timeline(ms(fri, 6 * 60 + 30), fri, work)
        assertEquals(listOf("now:1 h 10 free before you leave", "c-to", "w-540", "c-home"), rows(morning))
        val to = morning.rows[1]
        assertEquals(TimelineKind.COMMUTE, to.kind)
        assertEquals("07:40–09:00", to.time)
        assertEquals("Commute", to.title)
        assertNull(to.detail)
        val home = morning.rows.last()
        assertEquals("Commute home", home.title)
        assertEquals("17:30–18:50", home.time)
        assertEquals("Home about 18:50", home.detail)
        // Not an event ahead, not Up next, not in the section's summary.
        assertFalse(morning.hasEventsAhead)
        assertNull(morning.nextEvent)
        assertEquals("Work 09:00–17:30", morning.summary)
        // On the train: lit, with when he gets in.
        val onTheWay = timeline(ms(fri, 8 * 60), fri, work)
        assertEquals("c-to", onTheWay.rows.first().id)
        assertTrue(onTheWay.rows.first().running)
        assertEquals("Now · at work about 09:00", onTheWay.rows.first().detail)
        // Training at 19:30: the gap after the commute home is time at home.
        val training = ev("Training", fri, 19 * 60 + 30)
        assertEquals(listOf("w-540", "now", "c-home", "gap:40 min free at home", "e-Training"), rows(timeline(ms(fri, 11 * 60), fri, work, listOf(training))))
        // On the way home: lit, and the morning band has gone (not folded into "earlier").
        val evening = timeline(ms(fri, 18 * 60), fri, work)
        assertEquals("c-home", evening.rows.first().id)
        assertEquals("Now · home about 18:50", evening.rows.first().detail)
        assertTrue(evening.earlier.isEmpty())
        // Home: nothing left on the timeline.
        assertTrue(timeline(ms(fri, 19 * 60), fri, work).rows.isEmpty())
        // From home: no bands.
        val wfh = timeline(ms(fri, 6 * 60 + 30), fri, WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(fri)))
        assertEquals(listOf("now:2 h 30 free until Work from home", "w-540"), rows(wfh))
    }

    @Test
    fun theCalendarSaysWhenHeLeavesAndGetsHome() {
        val v = CalendarAgenda.build(emptyList(), emptyList(), ms(thu, 6 * 60), cal, days = 4, work = work)
        assertEquals("Leave 07:40 · home about 16:50", v.sections[0].commuteLine) // Thursday's short day
        assertEquals("Leave 07:40 · home about 18:50", v.sections[1].commuteLine)
        assertNull(v.sections[2].commuteLine) // the weekend
        val wfh = CalendarAgenda.build(emptyList(), emptyList(), ms(thu, 6 * 60), cal, days = 2,
            work = WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(fri)))
        assertNull(wfh.sections[1].commuteLine)
        // Only the commute home when the morning's is cut off by midnight.
        assertEquals("Home about 09:20", CommuteRules.calendarLine(WorkHours(WorkSchedule(setOf(5), 0, 8 * 60)), fri, cal))
    }

    // ---- Slice 3: clashes against arrival home ----

    private fun timed(id: String, title: String, d: Long, from: Int, to: Int) =
        CalendarEvent(id, title, ms(d, from), ms(d, to), false, null, "google", null, null)

    @Test
    fun anEventBeforeHesHomeClashesWithTheCommute() {
        val training = timed("t", "Training", fri, 18 * 60, 19 * 60)
        val bands = CommuteRules.bands(work, fri, cal)
        val c = WorkClashRules.clash(training, work.blocks(fri, cal), cal, commute = bands)!!
        assertEquals(ClashWith.HOME, c.against)
        assertEquals("You're home about 18:50 · who's taking?", c.mark)
        assertEquals("Training at 18:00 — you're home about 18:50 · who's taking?", c.line)
        assertEquals(
            "Heads up: Training at 6 o'clock, before you're home at about 6:50. Who's taking?",
            c.spoken("Training") { BriefScriptRules.clock(it) },
        )
        // Something else just says when he's home.
        val dinner = WorkClashRules.clash(timed("d", "Dinner with Sam", fri, 18 * 60 + 30, 20 * 60), emptyList(), cal, commute = bands)!!
        assertEquals("Before you're home · about 18:50", dinner.mark)
        assertEquals("Dinner with Sam at 18:30 — you're home about 18:50", dinner.line)
        // After he's home, nothing; during work, work wins.
        assertNull(WorkClashRules.clash(timed("l", "Late", fri, 18 * 60 + 50, 20 * 60), emptyList(), cal, commute = bands))
        assertEquals(ClashWith.WORK, WorkClashRules.clash(timed("o", "Overlap", fri, 17 * 60, 18 * 60), work.blocks(fri, cal), cal, commute = bands)!!.against)
        // Thursday's short day: home about 16:50.
        val thuClash = WorkClashRules.clash(timed("s", "Swimming", thu, 16 * 60, 17 * 60), work.blocks(thu, cal), cal, commute = CommuteRules.bands(work, thu, cal))!!
        assertEquals("Swimming at 16:00 — you're home about 16:50", thuClash.line)
    }

    @Test
    fun anEventAfterHesLeftClashesWithTheMorningCommute() {
        val c = WorkClashRules.clash(timed("r", "School run", fri, 8 * 60 + 15, 8 * 60 + 45), work.blocks(fri, cal), cal, commute = CommuteRules.bands(work, fri, cal))!!
        assertEquals(ClashWith.LEAVING, c.against)
        assertEquals("After you leave · about 07:40", c.mark)
        assertEquals("School run at 08:15 — you leave about 07:40", c.line)
        assertEquals(
            "Heads up: School run at quarter past 8, after you've left for work at about 7:40.",
            c.spoken("School run") { BriefScriptRules.clock(it) },
        )
    }

    @Test
    fun noCommuteClashOnWorkFromHomeLeaveOrTheWeekend() {
        val wfh = WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(fri))
        val training = timed("t", "Training", fri, 18 * 60, 19 * 60)
        assertNull(WorkClashRules.clash(training, wfh.blocks(fri, cal), cal, commute = CommuteRules.bands(wfh, fri, cal)))
        val leave = WorkHours(WorkSchedule.DEFAULT, leaveDays = setOf(fri))
        assertTrue(WorkClashRules.clashes(listOf(training), leave.blocks(fri, cal), cal, commute = CommuteRules.bands(leave, fri, cal)).isEmpty())
        val satTraining = timed("s", "Training", sat, 18 * 60, 19 * 60)
        assertTrue(WorkClashRules.clashes(listOf(satTraining), work.blocks(sat, cal), cal, commute = CommuteRules.bands(work, sat, cal)).isEmpty())
    }

    @Test
    fun todayTheCalendarAndTheWeekAheadFlagIt() {
        val training = timed("t", "Training", fri, 18 * 60, 19 * 60)
        val tl = timeline(ms(fri, 7 * 60), fri, work, listOf(training))
        assertEquals("Training at 18:00 — you're home about 18:50 · who's taking?", tl.clashLine)
        assertEquals("You're home about 18:50 · who's taking?", tl.rows.first { it.id == "e-t" }.clash)
        // Working from home that day: no clash.
        assertNull(timeline(ms(fri, 7 * 60), fri, WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(fri)), listOf(training)).clashLine)

        val v = CalendarAgenda.build(emptyList(), listOf(training), ms(thu, 6 * 60), cal, days = 3, work = work)
        assertEquals("You're home about 18:50 · who's taking?", v.sections.first { it.firstDay == fri }.rows.first { it.id == "e-t" }.clash)

        val inputs = WeekAheadInputs(listOf(training), work, HolidayCalendar.NONE)
        val line = WeekAheadRules.lines(inputs, fri, CalendarAgenda.window(fri, cal), listOf(thu, fri, sat), cal).single()
        assertEquals("Training · You're home about 18:50 · who's taking?", line.text)
        assertTrue(line.lit)
    }

    // ---- Slice 4: the planner, Leaving now, the brief and Shut down ----

    @Test
    fun thePlannerNeverBooksIntoTheCommute() {
        val bands = CommuteRules.bands(work, fri, cal)
        val slots = bands.map { DayPlanner.Slot(it.startMs, it.endMs) }
        val task = Task("t", "Call the bank", null, Lifecycle.ACTIVE, null, null, 30, 0, null, null, 0, null, false)
        // From 17:00 the first free half hour is after work and the commute home (17:30–18:50): 19:00.
        val prefs = DayPlanner.Prefs(dayStartMin = 6 * 60, dayEndMin = 22 * 60)
        val work9to5 = listOf(DayPlanner.Slot(ms(fri, 9 * 60), ms(fri, 17 * 60 + 30)))
        val plan = DayPlanner.plan(
            listOf(task), emptyList(), ms(fri, 17 * 60), DayWindow(ms(fri, 0), ms(fri + 1, 0)), prefs,
            commute = slots + work9to5, commuteNote = CommuteRules.plannerNote(bands, ms(fri, 17 * 60)),
        )
        assertEquals(ms(fri, 19 * 60), plan.placements.single().startMs)
        assertEquals("◐ Your commute is kept clear (17:30–18:50). Nothing is planned in it.", plan.keptLine)
        // Early in the morning both bands are ahead; with none left the note goes.
        assertEquals("◐ Your commute is kept clear (07:40–09:00 and 17:30–18:50). Nothing is planned in it.",
            CommuteRules.plannerNote(bands, ms(fri, 6 * 60)))
        assertNull(CommuteRules.plannerNote(bands, ms(fri, 19 * 60)))
        assertNull(CommuteRules.plannerNote(CommuteRules.bands(work, sat, cal), ms(sat, 6 * 60)))
        // From 06:00 a two-hour task doesn't fit before he leaves at 07:40 and never goes inside the commute.
        val early = DayPlanner.plan(
            listOf(task.copy(estimateMinutes = 120)), emptyList(), ms(fri, 6 * 60), DayWindow(ms(fri, 0), ms(fri + 1, 0)), prefs,
            commute = slots + work9to5,
        )
        assertEquals(ms(fri, 19 * 60), early.placements.single().startMs, "it waits for the evening")
    }

    @Test
    fun leavingNowsJourneyHomeIsTheCommuteHome() {
        val s = LeavingRules.withCommute(LeavingSetting(true, "Jordan", LeavingRules.DEFAULT_JOURNEY), CommuteSetting(80, 90, chosen = true))
        assertEquals(90, s.journeyMin)
        assertEquals("On · a draft to Jordan when work ends on office days · about 1 h 30 home", LeavingRules.summary(s))
        assertEquals("Journey home: 1 h 30 · your commute home, set under Commute", LeavingRules.journeyNote(90))
        val office = OfficeWindow(ms(fri, 9 * 60), ms(fri, 17 * 60 + 30))
        val lines = LineStatusSnapshot.EMPTY
        assertEquals("Leaving now, home about 19:00", LeavingRules.notices(s, office, lines, ms(fri, 17 * 60 + 30), cal).single().text)
    }

    @Test
    fun theBriefSaysWhenToLeaveAndWhenHesHome() {
        assertEquals("Leave about 7:40, home about 6:50.", CommuteRules.briefLine(7 * 60 + 40, 18 * 60 + 50, BriefScriptRules::clock))
        assertEquals("Leave about 7:40.", CommuteRules.briefLine(7 * 60 + 40, null, BriefScriptRules::clock))
        assertNull(CommuteRules.briefLine(null, null, BriefScriptRules::clock))
        val story = BriefStory(epochDay = fri, officeDay = true, workEndMin = 17 * 60 + 30, leaveMin = 7 * 60 + 40, homeMin = 18 * 60 + 50,
            route = "South Rail, Metro line, good service")
        val script = BriefScriptRules.script(MorningBriefView.EMPTY.copy(dateLabel = "Fri 9 Oct", workLine = "Work 09:00–17:30", story = story))
        assertTrue("an office day, finishing at half past 5. Leave about 7:40, home about 6:50. On the trains:" in script, script)
        // Working from home: not said.
        val wfh = BriefScriptRules.script(MorningBriefView.EMPTY.copy(dateLabel = "Fri 9 Oct", workLine = "Work 09:00–17:30",
            story = story.copy(officeDay = false)))
        assertFalse("Leave about" in wfh, wfh)
    }

    @Test
    fun shutDownsTomorrowLineAddsTheCommute() {
        assertEquals("Work 09:00–17:30 · leave 07:40 · home about 18:50", CommuteRules.withDayLine("Work 09:00–17:30", work, fri, cal))
        assertEquals("Work 09:00–15:30 · leave 07:40 · home about 16:50", CommuteRules.withDayLine("Work 09:00–15:30", work, thu, cal))
        val home = WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(fri))
        assertEquals("Work 09:00–17:30", CommuteRules.withDayLine("Work 09:00–17:30", home, fri, cal), "from home")
        assertEquals("Annual leave · no work", CommuteRules.withDayLine("Annual leave · no work", work, fri, cal))
        assertNull(CommuteRules.withDayLine(null, work, sat, cal))
    }

    // ---- Slice 6: one-off times for a single day ----

    @Test
    fun oneDaysOwnTimesSyncAndOnlyThatDayUsesThem() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        world.clock.nowMs = ms(thu, 12 * 60)
        val foldWork = WorkMode(fold.replica, nowMs = { world.clock.nowMs })
        val macWork = WorkMode(mac.replica, nowMs = { world.clock.nowMs })
        assertFalse(foldWork.setCommuteDay(fri, 83, 80, thu), "not in fives")
        assertFalse(foldWork.setCommuteDay(thu - 1, 105, 80, thu), "a day gone by")
        assertFalse(foldWork.setCommuteDay(fri, 80, 80, thu), "the usual times: nothing to change")
        assertTrue(foldWork.setCommuteDay(fri, 105, 80, thu))
        assertFalse(foldWork.setCommuteDay(fri, 105, 80, thu), "no change")
        fold.sync(); mac.sync()
        val clock = LocalClock(4, 12 * 60)
        val hours = macWork.hours(clock, thu)
        // Friday leaves 25 minutes earlier; Thursday and next Monday keep the usual times.
        assertEquals(105, CommuteRules.toWorkOn(hours, fri))
        assertEquals(listOf("07:15–09:00", "17:30–18:50"), CommuteRules.bands(hours, fri, cal).map { it.label })
        assertEquals(80, CommuteRules.toWorkOn(hours, thu))
        assertEquals(80, CommuteRules.toWorkOn(hours, fri + 3))
        assertEquals("Leave 07:15 · home about 18:50 · just this day", CommuteRules.calendarLine(hours, fri, cal))
        assertEquals("Leave 07:40 · home about 16:50", CommuteRules.calendarLine(hours, thu, cal))
        // The usual setting isn't touched by a one-off.
        assertFalse(macWork.commute().chosen)
        // The wake alarm on Friday counts it: 09:00 − 1 h 45 − 1 h = 06:15.
        assertEquals(6 * 60 + 15, wake(fri, hours)?.minute)
        // Work mode's rows: the coming week's office days (no weekend), Friday marked.
        val rows = macWork.state(clock, thu).commuteDays
        assertEquals(listOf(thu, fri, fri + 3, fri + 4, fri + 5), rows.map { it.epochDay })
        assertEquals("Fri 9 Oct · 1 h 45 there · 1 h 20 home · just this day", rows[1].line)
        assertEquals("Thu 8 Oct · 1 h 20 each way", rows[0].line)
        assertTrue(rows[1].changed)
        // Back to the usual from the Mac; the Fold follows.
        assertTrue(macWork.setCommuteDay(fri, null, null, thu))
        mac.sync(); fold.sync()
        assertEquals(80, CommuteRules.toWorkOn(foldWork.hours(clock, thu), fri))
    }

    @Test
    fun oneOffTimesAreKeptTidy() {
        val usual = CommuteSetting(80, 80, chosen = true)
        // Past days drop as it writes; one entry per day; the usual times clear it.
        val days = listOf(CommuteDay(thu - 2, 100, 80), CommuteDay(fri, 90, 90))
        assertEquals(listOf(CommuteDay(fri, 120, 80)), CommuteRules.withDay(days, usual, fri, 120, 80, thu))
        assertEquals(emptyList(), CommuteRules.withDay(days, usual, fri, 80, 80, thu))
        // Read: garbage and out-of-range entries are skipped, the last entry for a day wins, ascending.
        val read = CommuteRules.decodeDays("20736:90:90, x, 20735:95:300, 20735:100:80, 20734:105:85")
        assertEquals(listOf(CommuteDay(20734, 105, 85), CommuteDay(20735, 100, 80), CommuteDay(20736, 90, 90)), read)
        assertEquals("20734:105:85,20735:100:80,20736:90:90", CommuteRules.encodeDays(read))
        val withDays = CommuteRules.read(mapOf(CommuteFields.DAYS to FieldValue.Text("20735:100:80")))
        assertFalse(withDays.chosen)
        assertEquals(CommuteSetting(100, 80, chosen = true), withDays.forDay(20735))
        assertEquals(CommuteSetting.DEFAULT, withDays.forDay(20736))
        // Leaving now's journey home on the day follows it.
        assertEquals(100, LeavingRules.withCommute(LeavingSetting.DEFAULT, CommuteSetting(80, 80, days = listOf(CommuteDay(thu, 80, 100))).forDay(thu)).journeyMin)
        // Ask's day line says so.
        val hours = WorkHours(WorkSchedule.DEFAULT, commute = CommuteSetting(80, 80, days = listOf(CommuteDay(fri, 105, 80))))
        assertEquals(
            "office day · leave 07:15 · work 09:00–17:30 · home about 18:50 · one-off commute 1 h 45 there · 1 h 20 home, just this day",
            CommuteAskRules.dayLine(hours, fri, cal),
        )
    }
}
