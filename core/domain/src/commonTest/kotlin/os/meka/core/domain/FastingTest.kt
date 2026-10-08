package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FastingTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private val hourMs = 3_600_000L
    private var n = 0
    private fun ids(): String = "F${n++}"
    private fun fasting(d: Device) = Fasting(d.replica, ::ids, { world.clock.nowMs })

    private val d = world.device("android")
    private val f = fasting(d)

    init {
        // Start every test on a Monday at 20:00 UTC.
        val today = world.clock.nowMs.floorDiv(dayMs)
        val toMonday = (8 - CivilDate.isoDayOfWeek(today)) % 7
        world.clock.nowMs = (today + toMonday) * dayMs + 20 * hourMs
    }

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun hours(h: Int) = world.clock.advance(h * hourMs)
    private fun window(day: Long = today()) = DayWindow(day * dayMs, (day + 1) * dayMs)

    @Test
    fun theDefaultPlanIsSixteenEightWithLunchToEightPm() {
        val v = f.view()
        assertEquals(FastingRules.DEFAULT_PLAN, v.plan)
        assertEquals("16:8 · eating 12:00–20:00", v.plan.line)
        assertFalse(v.isFasting)
        assertEquals("Eating window closed at 20:00", v.windowLine)
        assertNull(v.weekLine)
        assertEquals(7, v.week.size)
        assertTrue(v.week.last().isToday)
        assertEquals("Mon", v.week.last().label)
    }

    @Test
    fun aFastRunsToItsGoalAndEndsIntoHistory() {
        val id = f.start()
        var cur = assertNotNull(f.view().current)
        assertEquals(id, cur.id)
        assertEquals(16, cur.targetHours)
        assertEquals("Started 20:00", cur.startedLine)
        assertEquals("Goal 16 h · at 12:00 tomorrow", cur.goalLine)
        assertFalse(cur.reachedGoal)
        assertFailsWith<ValidationException> { f.start() }

        hours(17) // Tuesday 13:00
        cur = assertNotNull(f.view().current)
        assertTrue(cur.reachedGoal)
        assertEquals("Goal reached at 12:00", cur.goalLine)
        assertEquals("Started 20:00 yesterday", cur.startedLine)

        f.end()
        val v = f.view()
        assertNull(v.current)
        val last = assertNotNull(v.last)
        assertEquals("Last fast 17 h 00 m · goal reached", last.line)
        assertTrue(last.canResume)
        assertEquals(17.0, v.week.last().hours)
        assertTrue(v.week.last().reachedGoal)
        assertEquals("1 fast in 7 days · average 17 h 00 m · 1 reached the goal", v.weekLine)
        assertEquals("Eating window open until 20:00", v.windowLine)
    }

    @Test
    fun aMistakenEndCanBeUndoneForAFewMinutes() {
        val id = f.start()
        hours(2)
        f.end()
        f.resume(id)
        assertEquals(id, f.view().current?.id)
        f.end()
        world.clock.advance(FastingRules.RESUME_WINDOW_MS + 1)
        assertFalse(f.view().last!!.canResume)
        assertFailsWith<ValidationException> { f.resume(id) }
    }

    @Test
    fun startCanBeBackdatedAndNudgedButNeverIntoTheFutureOrTheLastFast() {
        f.start(startedMinutesAgo = 60)
        assertEquals("Started 19:00", f.view().current!!.startedLine)
        f.moveStart(-30)
        assertEquals("Started 18:30", f.view().current!!.startedLine)
        assertFailsWith<ValidationException> { f.moveStart(120) }
        f.setTarget(18)
        assertEquals("Goal 18 h · at 12:30 tomorrow", f.view().current!!.goalLine)
        hours(1)
        f.end() // 21:00
        assertFailsWith<ValidationException> { f.start(startedMinutesAgo = 30) }
        f.start()
        assertFailsWith<ValidationException> { f.moveStart(-30) }
        assertFailsWith<ValidationException> { f.start(startedMinutesAgo = 0) }
    }

    @Test
    fun discardLeavesNoHistory() {
        f.start()
        hours(1)
        f.discard()
        val v = f.view()
        assertNull(v.current)
        assertNull(v.last)
        assertFailsWith<ValidationException> { f.end() }
    }

    @Test
    fun choosingAPlanSetsTheGoalAndWindowAndTheNextFastUsesIt() {
        f.choosePlan(2) // 18:6
        assertEquals(FastingPlan(18, 13 * 60, 19 * 60), f.view().plan)
        assertEquals("18:6", FastingRules.planLabel(f.view().plan))
        f.start()
        assertEquals(18, f.view().current!!.targetHours)
        assertFailsWith<ValidationException> { f.setPlan(16, 12 * 60, 12 * 60) }
        assertFailsWith<ValidationException> { f.setTarget(0) }
    }

    @Test
    fun windowLinesCoverBeforeDuringAfterAndAcrossMidnight() {
        val p = FastingRules.DEFAULT_PLAN
        assertEquals("Eating window opens at 12:00", FastingRules.windowLine(9 * 60, p))
        assertEquals("Eating window open until 20:00", FastingRules.windowLine(12 * 60, p))
        assertEquals("Eating window closed at 20:00", FastingRules.windowLine(20 * 60, p))
        val late = FastingPlan(16, 22 * 60, 2 * 60)
        assertTrue(FastingRules.inWindow(23 * 60, late.eatingStartMin, late.eatingEndMin))
        assertTrue(FastingRules.inWindow(60, late.eatingStartMin, late.eatingEndMin))
        assertEquals("Eating window opens at 22:00", FastingRules.windowLine(3 * 60, late))
    }

    @Test
    fun timerTextAndProgress() {
        assertEquals("14:05:09", FastingRules.clock(14 * hourMs + 5 * 60_000 + 9_000))
        assertEquals("26:00:00", FastingRules.clock(26 * hourMs))
        assertEquals("45 m", FastingRules.duration(45 * 60_000L))
        assertEquals("16 h 05 m", FastingRules.duration(16 * hourMs + 5 * 60_000))
        assertEquals(0.5f, FastingRules.progress(0, 16, 8 * hourMs))
        assertEquals(1f, FastingRules.progress(0, 16, 20 * hourMs))
        assertEquals("1 h ago", FastingRules.startedAgoLabel(60))
        assertEquals("−30 min", FastingRules.moveLabel(-30))
    }

    @Test
    fun twoDevicesStartingOfflineCountOnceAndEndingEndsBoth() {
        val mac = world.device("mac")
        val m = fasting(mac)
        f.start(startedMinutesAgo = 30)
        m.start()
        d.sync(); mac.sync(); d.sync()
        // The earlier start counts on both devices.
        assertEquals("Started 19:30", f.view().current!!.startedLine)
        assertEquals("Started 19:30", m.view().current!!.startedLine)
        hours(16)
        m.end()
        d.sync(); mac.sync(); d.sync()
        assertNull(f.view().current)
        val v = f.view()
        assertEquals("1 fast in 7 days · average 16 h 30 m · 1 reached the goal", v.weekLine)
    }

    @Test
    fun historyCountsTheLastSevenDaysOnTheDayEachEnded() {
        // Eight fasts, 20:00 to 12:00, ending on eight days in a row: the oldest falls outside the week.
        repeat(8) { f.start(); hours(16); f.end(); hours(8) }
        val v = f.view()
        assertEquals(7, v.week.size)
        assertTrue(v.week.all { it.hours == 16.0 && it.reachedGoal })
        assertEquals("7 fasts in 7 days · average 16 h 00 m · 7 reached the goal", v.weekLine)
    }

    @Test
    fun thePlannerKeepsMealsFreeAndPlansAroundThem() {
        // Tuesday 08:00, fasting since Monday 20:00: the goal (12:00) lands today.
        f.start()
        hours(12)
        val day = window()
        val meals = f.plannerMeals(day)
        assertEquals(listOf("Break your fast", "Last meal before your fast"), meals.map { it.title })
        assertEquals(day.startMs + 12 * hourMs, meals[0].startMs)
        assertEquals(day.startMs + 20 * hourMs, meals[1].endMs)

        val tasks = (0 until 13).map { i ->
            Task(
                id = "t$i", title = "Task $i", notes = null, lifecycle = Lifecycle.ACTIVE, priority = 0, dueAtMs = null,
                scheduledAtMs = null, estimateMinutes = 30, goalId = null, somedayKind = null, createdAtMs = i.toLong(),
                completedAtMs = null, hasConflict = false,
            )
        }
        val plan = DayPlanner.plan(tasks, emptyList(), world.clock.nowMs, day, meals = meals)
        assertEquals(meals, plan.meals)
        plan.placements.forEach { p ->
            meals.forEach { m -> assertTrue(p.endMs <= m.startMs || p.startMs >= m.endMs, "${p.task.title} overlaps ${m.title}") }
        }

        // Not fasting, window still ahead: only the last meal; after it closes, nothing.
        f.end()
        assertEquals(listOf("Last meal before your fast"), f.plannerMeals(day).map { it.title })
        hours(12) // 20:00
        assertTrue(f.plannerMeals(day).isEmpty())
    }

    // ---- Fasting v2: extended fasts and history ----

    @Test
    fun aFiveDayFastCountsDaysAndHours() {
        f.startExtended(120)
        var cur = assertNotNull(f.view().current)
        assertTrue(cur.extended)
        assertEquals("5-day fast", cur.title)
        assertEquals("Day 1 of 5 · 0 h", cur.dayLine)
        assertEquals("Goal 5 days · Sat 20:00", cur.goalLine)
        assertEquals("Sat 20:00", cur.goalWhen)
        hours(62) // Thursday 10:00
        cur = assertNotNull(f.view().current)
        assertEquals("Day 3 of 5 · 62 h", cur.dayLine)
        assertEquals(62f / 120f, cur.progress(world.clock.nowMs))
        hours(60) // Saturday 22:00
        cur = assertNotNull(f.view().current)
        assertTrue(cur.reachedGoal)
        assertEquals("Day 6 · 122 h · goal reached", cur.dayLine)
        assertEquals("Goal reached at Sat 20:00", cur.goalLine)
        // A fast of days isn't the daily window: no meals planned around it until its goal day.
        f.end()
        val v = f.view()
        assertEquals("Last fast 5 d 02 h · goal reached", v.last!!.line)
        val rec = v.history.fasts.single()
        assertEquals("5-day fast", rec.title)
        assertEquals("5 d 02 h · goal reached", rec.resultLine)
        assertEquals(1, v.history.streak)
    }

    @Test
    fun endingEarlyIsRecordedAsIsAndBreaksTheStreak() {
        f.start(); hours(16); f.end(); hours(8) // Tuesday 12:00 → 20:00
        f.start(); hours(17); f.end(); hours(7)
        assertEquals("2 fasts in a row reached the goal", f.view().history.streakLine)
        f.startExtended(72) // Wednesday 20:00
        hours(50)
        f.end()
        val h = f.view().history
        assertEquals(0, h.streak)
        assertNull(h.streakLine)
        val latest = h.fasts.first()
        assertEquals("3-day fast", latest.title)
        assertFalse(latest.reachedGoal)
        assertEquals("2 d 02 h of 3 days · ended early", latest.resultLine)
        assertEquals(72 * hourMs, latest.plannedMs)
        assertEquals(50 * hourMs, latest.actualMs)
        assertEquals("3 fasts · 2 reached the goal · longest 2 d 02 h", h.totalsLine)
        assertEquals(listOf("3-day fast", "16 h fast", "16 h fast"), h.fasts.map { it.title })
    }

    @Test
    fun aFastUntilAMomentUsesThatMoment() {
        val v0 = f.view()
        assertEquals(6, v0.untilChoices.size)
        assertEquals("Until Tue 18:00", v0.untilChoices.first().label)
        val fri = v0.untilChoices[3] // Friday 18:00
        assertEquals("Until Fri 18:00", fri.label)
        f.startUntil(fri.untilMs)
        val cur = assertNotNull(f.view().current)
        assertEquals("Fast until Fri 18:00", cur.title)
        assertEquals("Goal Fri 18:00", cur.goalLine)
        assertEquals(94, cur.targetHours) // Monday 20:00 → Friday 18:00 is 94 h
        assertEquals("Day 1 of 4 · 0 h", cur.dayLine)
        // Changing the goal to hours replaces the moment.
        f.setTarget(120)
        assertEquals("Goal 5 days · Sat 20:00", f.view().current!!.goalLine)
        f.discard()
        assertFailsWith<ValidationException> { f.startUntil(world.clock.nowMs + 2 * hourMs) }
        assertFailsWith<ValidationException> { f.startUntil(world.clock.nowMs + 11 * dayMs) }
        assertFailsWith<ValidationException> { f.startExtended(241) }
    }

    @Test
    fun aCustomUntilDayAndTimeStartsAFastUntilThen() {
        // Monday 20:00: 12 hours on is Tuesday 08:00, ten days on is Thursday week 20:00.
        val v = f.view()
        assertEquals("Tomorrow", v.untilDays.first().label)
        assertEquals(today() + 1, v.untilDays.first().epochDay)
        assertEquals(10, v.untilDays.size)
        assertEquals(today() + 10, v.untilDays.last().epochDay)
        val sat = v.untilDays[4]
        assertEquals(CivilDate.shortLabel(today() + 5).substringBeforeLast(' '), sat.label)
        assertTrue(sat.label.startsWith("Sat "))
        val untilMs = v.untilAt(sat, 14 * 60 + 30)
        assertEquals((today() + 5) * dayMs + (14 * 60 + 30) * 60_000L, untilMs)
        assertEquals(FastUntilPick(true, "Goal 4 d 18 h · starts now"), FastingRules.untilPick(world.clock.nowMs, untilMs))
        // Too soon (Tuesday 07:30) or past ten days (Thursday week 20:30) is said, not started.
        val early = v.untilAt(v.untilDays.first(), 7 * 60 + 30)
        assertEquals(FastUntilPick(false, "Pick an end at least 12 hours away"), FastingRules.untilPick(world.clock.nowMs, early))
        val late = v.untilAt(v.untilDays.last(), 20 * 60 + 30)
        assertEquals(FastUntilPick(false, "Pick an end within ten days"), FastingRules.untilPick(world.clock.nowMs, late))
        assertEquals("Pick an end at least 12 hours away", assertFailsWith<ValidationException> { f.startUntil(early) }.message)
        assertTrue(FastingRules.untilPick(world.clock.nowMs, v.untilAt(v.untilDays.last(), 20 * 60)).ok)
        f.startUntil(untilMs)
        val cur = assertNotNull(f.view().current)
        assertEquals("Fast until Sat 14:30", cur.title)
        assertEquals(115, cur.targetHours) // 114 h 30 m, rounded up
        assertEquals("Day 1 of 5 · 0 h", cur.dayLine)
    }

    @Test
    fun lateInTheEveningTodayIsNotADayAndTimeSteps() {
        world.clock.nowMs = (today() * dayMs) + 9 * hourMs // Monday 09:00: 21:00 today is 12 h on
        val v = f.view()
        assertEquals("Today", v.untilDays.first().label)
        assertEquals("Tomorrow", v.untilDays[1].label)
        assertEquals(11, v.untilDays.size) // Monday → Thursday week (09:00 is ten days on)
        assertEquals(18 * 60 + 30, FastingRules.stepUntilTime(18 * 60, 1))
        assertEquals(17 * 60 + 30, FastingRules.stepUntilTime(18 * 60, -1))
        assertEquals(0, FastingRules.stepUntilTime(23 * 60 + 30, 1))
        assertEquals(23 * 60 + 30, FastingRules.stepUntilTime(0, -1))
        assertEquals(18 * 60, FastingRules.stepUntilTime(18 * 60 + 10, 0)) // snaps to the half hour
        assertEquals("Start a 5-day fast", FastingRules.startTitle(120))
        assertEquals("Start a 36 h fast", FastingRules.startTitle(36))
    }

    @Test
    fun theHeatStripCountsHoursFastedEachDay() {
        f.start(); hours(16); f.end() // Monday 20:00 → Tuesday 12:00
        val h = f.view().history
        assertEquals(FastingRules.HEAT_WEEKS, h.heat.size)
        assertTrue(h.heat.all { it.size == 7 })
        val days = h.heat.flatten()
        val today = days.single { it.isToday } // Tuesday
        assertEquals(12.0, today.hours)
        assertEquals(2, today.level)
        val monday = days[days.indexOf(today) - 1]
        assertEquals(4.0, monday.hours)
        assertEquals(1, monday.level)
        assertTrue(days.last().isFuture) // Sunday
        assertEquals(0, days.last().level)
        // A running fast of days fills whole days.
        hours(8); f.startExtended(72); hours(52) // Tuesday 20:00 → Thursday 24:00
        val d2 = f.view().history.heat.flatten()
        val wed = d2.single { it.isToday }.epochDay - 1
        assertEquals(4, d2.single { it.epochDay == wed }.level)
    }

    @Test
    fun anExtendedFastSyncsWithItsKindAndGoal() {
        val mac = world.device("mac")
        val m = fasting(mac)
        f.startExtended(120)
        d.sync(); mac.sync()
        val cur = assertNotNull(m.view().current)
        assertEquals("5-day fast", cur.title)
        assertTrue(cur.extended)
        hours(30)
        m.moveStart(-30) // nudged on the Mac, seen on the Fold
        d.sync(); mac.sync(); d.sync()
        assertEquals("Day 2 of 5 · 30 h", f.view().current!!.dayLine)
    }

    // ---- Fasting v2 slice 2: check-ins and "you did it" ----

    private fun notices(nowMs: Long = world.clock.nowMs) = NoticeSources.collect(
        ListsView.EMPTY, f.view(), ShutdownView.EMPTY.copy(doneToday = true), Today(emptyList(), null, emptyList(), emptyList()),
        nowMs, LocalCalendar.UTC,
    ).filter { it.source == NoticeSource.FAST_CHECK_IN || it.source == NoticeSource.FAST_GOAL }

    @Test
    fun anExtendedFastChecksInOnceADayAndSaysYouDidItAtTheGoal() {
        val start = world.clock.nowMs // Monday 20:00
        f.startExtended(120)
        val ns = notices()
        val checkIns = ns.filter { it.source == NoticeSource.FAST_CHECK_IN }
        // Days 2–5 begin at 24, 48, 72 and 96 h; the goal (120 h) says "you did it" instead of a sixth check-in.
        assertEquals((1..4).map { start + it * dayMs }, checkIns.map { it.atMs })
        assertEquals("5-day fast · Day 3 of 5", checkIns[1].title)
        assertEquals("48 h so far · goal Sat 20:00 · ending early is fine", checkIns[1].text)
        assertEquals(start + 3 * dayMs, checkIns[1].expiresAtMs) // stands until the next one
        assertEquals(start + 5 * dayMs, checkIns.last().expiresAtMs) // the last one until the goal
        assertEquals(NoticeTarget.GOALS, checkIns[0].target)
        val goal = ns.single { it.source == NoticeSource.FAST_GOAL }
        assertEquals("You did it · 5 days", goal.title)
        assertEquals("5-day fast · end it whenever you're ready", goal.text)
        assertEquals(start + 5 * dayMs, goal.atMs)
        // Keys are per fast and day, so each posts once.
        assertEquals(4, checkIns.map { it.key }.toSet().size)

        // Through the governor: Tuesday 20:00 (the 18:00 digest already out) posts Day 2 at once.
        hours(24)
        val r = Governor.evaluate(notices(), NotificationSettings.DEFAULT, DeviceAlerts.ALL, GovernorState(lastDigestSlotMs = world.clock.nowMs), world.clock.nowMs, LocalCalendar.UTC)
        assertEquals(listOf("5-day fast · Day 2 of 5"), r.post.map { it.title })
        assertNull(f.view().current!!.doneLine)

        // At the goal the card has its "you did it" line; ending it then is recorded as reached.
        hours(96)
        assertEquals("You did it · 5 days", f.view().current!!.doneLine)
        f.end()
        assertTrue(f.view().history.fasts.single().reachedGoal)
        assertTrue(notices().isEmpty())
    }

    @Test
    fun aDailyFastHasNoCheckInsAndNoDoneLine() {
        f.start()
        assertTrue(notices().none { it.source == NoticeSource.FAST_CHECK_IN })
        assertEquals("Fasting goal reached", notices().single().title)
        hours(17)
        assertNull(f.view().current!!.doneLine)
    }

    @Test
    fun aShortExtendedFastSkipsACheckInTooCloseToItsGoal() {
        val start = world.clock.nowMs
        f.startExtended(36) // a check-in at 24 h is 12 h before the goal: kept
        assertEquals(listOf(start + dayMs), FastingRules.checkInTimes(start, start + 36 * hourMs))
        assertEquals(emptyList(), FastingRules.checkInTimes(start, start + 28 * hourMs)) // 4 h before: left out
        assertEquals(emptyList(), FastingRules.checkInTimes(start, start + 24 * hourMs))
        assertEquals("You did it · 36 h", FastingRules.doneLine(start, start + 36 * hourMs))
        assertEquals(9, FastingRules.checkInTimes(start, start + 240 * hourMs).size)
    }

    @Test
    fun aCheckInInQuietHoursRidesInTheNextDigest() {
        hours(3) // Monday 23:00, inside quiet hours (22:00–07:00)
        f.startExtended(72)
        hours(24) // Tuesday 23:00: Day 2 begins in quiet hours
        val cal = LocalCalendar.UTC
        val quiet = Governor.evaluate(notices(), NotificationSettings.DEFAULT, DeviceAlerts.ALL, GovernorState(), world.clock.nowMs, cal)
        assertTrue(quiet.post.isEmpty())
        hours(13); world.clock.advance(31 * 60_000L) // Wednesday 12:31, the midday digest
        val noon = Governor.evaluate(notices(), NotificationSettings.DEFAULT, DeviceAlerts.ALL, quiet.state, world.clock.nowMs, cal)
        assertTrue(noon.digest!!.lines.any { it.startsWith("3-day fast · Day 2 of 3 · 24 h so far") })
    }
}
