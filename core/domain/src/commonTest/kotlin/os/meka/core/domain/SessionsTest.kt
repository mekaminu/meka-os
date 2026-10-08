package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionsTest {
    private val cal = LocalCalendar.UTC
    private val mon = CivilDate.toEpochDay(2026, 10, 5) // Monday 5 Oct 2026
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val work = WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30)

    private fun gym(
        perWeek: Int = 3, timing: HabitTiming = HabitTiming.EVENING, minutes: Int = 60,
        done: Set<Long> = emptySet(), missed: Set<Long> = emptySet(), rotation: List<String> = emptyList(), lastLabel: String? = null,
        created: Long = at(mon - 14, 12), id: String = "gym", title: String = "Gym", todayLabel: String? = null, todayNote: String? = null,
        appLink: String? = null,
    ) = SessionHabit(id, title, perWeek, timing, minutes, created, done, missed, rotation, lastLabel, todayLabel, todayNote, appLink)

    private fun ev(id: String, from: Long, to: Long, provider: String = "google") =
        CalendarEvent(id, id, from, to, false, null, provider, null, null)

    private fun book(
        habits: List<SessionHabit>, today: Long = mon, now: Long = at(today, 8), events: List<CalendarEvent> = emptyList(),
        schedule: WorkSchedule? = work, holidays: HolidayCalendar = HolidayCalendar.NONE,
    ) = SessionRules.book(habits, today, now, cal) { d -> SessionRules.busyOn(d, events, schedule, holidays, cal) }

    @Test
    fun threeAWeekIsBookedAfterWorkWithARestDayBetween() {
        val v = book(listOf(gym()))
        assertEquals(listOf(mon, mon + 2, mon + 4), v.sessions.map { it.day })
        // Work ends 17:30, plus 15 minutes to get there.
        assertTrue(v.sessions.all { cal.minuteOfDay(it.startMs) == 17 * 60 + 45 && it.endMs - it.startMs == 3_600_000L })
        assertEquals("Booked Today 17:45 · Wed 17:45 · Fri 17:45", v.lines["gym"])
        val card = v.cards.single()
        assertEquals(SessionStatus.BOOKED, card.status)
        assertEquals("Today 17:45–18:45", card.line)
        assertEquals("Next: Wed 17:45", card.next)
    }

    @Test
    fun theWorkoutAppsLinkIsCleanedAndNamed() {
        assertEquals("https://hevy.com", SessionRules.cleanAppLink("  hevy.com "))
        assertEquals("https://www.strava.com/dashboard?x=1", SessionRules.cleanAppLink("HTTPS://WWW.Strava.com/dashboard?x=1"))
        assertEquals("http://gym.example.co.uk:8080/app", SessionRules.cleanAppLink("http://gym.example.co.uk:8080/app"))
        listOf(
            "", "   ", "hevy", "javascript:alert(1)", "intent://x", "file:///etc/passwd", "strava://feed", "https://user@evil.com",
            "https://hevy .com", "https://-bad.com", "https://hevy..com", "https://1.2.3.4", "https://hevy.com:abc", "https://hevy.com:",
            "https://" + "a".repeat(300) + ".com",
        ).forEach { assertNull(SessionRules.cleanAppLink(it), it) }
        assertEquals("Hevy", SessionRules.appName("https://hevy.com"))
        assertEquals("Strava", SessionRules.appName("https://www.strava.com/dashboard"))
        assertEquals("Garmin", SessionRules.appName("https://connect.garmin.com/modern"))
        assertEquals("Strava", SessionRules.appName("https://strava.app.link/abc"))
        assertEquals("Puregym", SessionRules.appName("https://www.puregym.co.uk:443/members"))
    }

    @Test
    fun theCardOffersTheAppWhileTheSessionIsAheadOnOrDoneButNotAfterDidntGo() {
        val h = listOf(gym(appLink = "https://hevy.com"))
        listOf(at(mon, 8), at(mon, 18), at(mon, 20)).forEach { now ->
            val c = book(h, now = now).cards.single()
            assertEquals("https://hevy.com", c.appLink)
            assertEquals("Open Hevy", c.openLabel)
        }
        val went = book(listOf(gym(done = setOf(mon), appLink = "https://hevy.com")), now = at(mon, 20)).cards.single()
        assertEquals(SessionStatus.WENT, went.status)
        assertEquals("Open Hevy", went.openLabel)
        val missed = book(listOf(gym(missed = setOf(mon), appLink = "https://hevy.com")), now = at(mon, 20)).cards.single()
        assertEquals(SessionStatus.MISSED, missed.status)
        assertNull(missed.openLabel)
        // No link, or one that isn't a web address (a synced value is checked again): nothing to open.
        assertNull(book(listOf(gym())).cards.single().openLabel)
        assertNull(book(listOf(gym(appLink = "javascript:alert(1)"))).cards.single().appLink)
    }

    @Test
    fun aMeetingOnTheSlotMovesTheSessionAndAFixtureKeepsItsLeadIn() {
        val v = book(listOf(gym()), events = listOf(ev("drinks", at(mon, 18), at(mon, 19)), ev("barca", at(mon + 2, 19), at(mon + 2, 21), "fixtures")))
        assertEquals(at(mon, 19, 15), v.sessions[0].startMs) // after drinks + 15 min
        // Wednesday: 17:45 would run into the hour before kick-off (18:00) and after the match there's no hour by 21:30,
        // so the session goes where the day has room: 07:00, before work.
        assertEquals(at(mon + 2, 7), v.sessions[1].startMs)
        assertEquals("Booked Today 19:15 · Wed 07:00 · Fri 17:45", v.lines["gym"])
    }

    @Test
    fun bankHolidaysAndWeekendsAreFreeDays() {
        val hols = HolidayCalendar(mapOf(mon to "Bank holiday"))
        val v = book(listOf(gym(timing = HabitTiming.MORNING)), holidays = hols)
        // Today's slot is worked out from the whole day (it stays put), so at 08:00 the 06:30 session asks "Did you go?".
        assertEquals(at(mon, 6, 30), v.sessions[0].startMs)
        assertEquals(SessionStatus.ASK, v.cards.single().status)
        // Tuesday morning: before 08:45 (work at 09:00 less 15 min): 06:30–07:30.
        assertEquals(at(mon + 2, 6, 30), v.sessions[1].startMs)
    }

    @Test
    fun tickedDaysCountAndTheWeekStopsBookingOnceMet() {
        val v = book(listOf(gym(done = setOf(mon, mon + 2))), today = mon + 3, now = at(mon + 3, 8))
        assertEquals(listOf(mon + 4), v.sessions.map { it.day }) // Thu rests after Wed, Fri booked
        val met = book(listOf(gym(done = setOf(mon, mon + 2, mon + 4))), today = mon + 5, now = at(mon + 5, 8))
        assertTrue(met.sessions.isEmpty())
        assertEquals("Week done · 3 of 3", met.lines["gym"])
        assertTrue(met.cards.isEmpty())
    }

    @Test
    fun theSlotBecomesDidYouGoOnceOverAndStaysPut() {
        val h = listOf(gym())
        assertEquals(SessionStatus.NOW, book(h, now = at(mon, 18)).cards.single().status)
        assertEquals("Now · until 18:45", book(h, now = at(mon, 18)).cards.single().line)
        val ask = book(h, now = at(mon, 20)).cards.single()
        assertEquals(SessionStatus.ASK, ask.status)
        assertEquals("Did you go? · 17:45–18:45", ask.line)
        assertTrue(ask.asks)
        assertEquals(at(mon, 17, 45), ask.startMs)
    }

    @Test
    fun didntGoRebooksTheWeekInsteadOfNagging() {
        val v = book(listOf(gym(missed = setOf(mon))), now = at(mon, 20))
        assertEquals(listOf(mon + 1, mon + 3, mon + 5), v.sessions.map { it.day })
        val card = v.cards.single()
        assertEquals(SessionStatus.MISSED, card.status)
        assertEquals("Not today · no worries", card.line)
        assertEquals("Rebooked for Tue 17:45", card.next)
        // Sunday afternoon, missed: nothing left this week (next week isn't booked before the evening).
        val sun = mon + 6
        val none = book(listOf(gym(done = setOf(mon, mon + 2), missed = setOf(sun))), today = sun, now = at(sun, 17))
        assertEquals("No other slot this week", none.cards.single().next)
        assertEquals("No room left this week", none.lines["gym"])
        // Sunday evening: it says when next week's first one is, not "rebooked".
        val later = book(listOf(gym(done = setOf(mon, mon + 2), missed = setOf(sun))), today = sun, now = at(sun, 20))
        assertEquals("No other slot this week · Next: Mon 17:45", later.cards.single().next)
        assertEquals("No room left this week · Next week: Mon 17:45 · Wed 17:45 · Fri 17:45", later.lines["gym"])
    }

    @Test
    fun sundayEveningBooksNextWeekToo() {
        val sun = mon + 6
        val h = listOf(gym(done = setOf(mon, mon + 2, mon + 4)))
        // Before 18:00 only this week (done).
        val before = book(h, today = sun, now = at(sun, 17, 59))
        assertTrue(before.sessions.isEmpty())
        assertEquals("Week done · 3 of 3", before.lines["gym"])
        // From 18:00, when the weekly review comes, next week at its full target around work, a rest day between.
        val v = book(h, today = sun, now = at(sun, 18))
        assertEquals(listOf(sun + 1, sun + 3, sun + 5), v.sessions.map { it.day })
        assertTrue(v.sessions.all { cal.minuteOfDay(it.startMs) == 17 * 60 + 45 })
        assertEquals("Week done · 3 of 3 · Next week: Mon 17:45 · Wed 17:45 · Fri 17:45", v.lines["gym"])
        // Nothing today, so no card; Today's timeline and the planner only ever take today's.
        assertTrue(v.cards.isEmpty())
        assertTrue(v.todayBlocks(sun, at(sun, 18)).isEmpty())
        // Went today (Sunday): the card says what's next (Monday rests) instead of "Week done".
        val went = book(listOf(gym(done = setOf(mon, mon + 2, sun))), today = sun, now = at(sun, 19)).cards.single()
        assertEquals(SessionStatus.WENT, went.status)
        assertEquals("Next: Tue 17:45", went.next)
    }

    @Test
    fun nextWeekCarriesOnTheRestDaysAndTheRotation() {
        val sun = mon + 6
        // Two done, Sunday's session still to answer (17:00 on a free day): next week rests on Monday.
        val v = book(listOf(gym(done = setOf(mon + 2, mon + 4), rotation = listOf("Push", "Pull", "Legs"), lastLabel = "Push")),
            today = sun, now = at(sun, 18))
        assertEquals(listOf(sun, sun + 2, sun + 4, sun + 6), v.sessions.map { it.day })
        assertEquals(listOf("Pull", "Legs", "Push", "Pull"), v.sessions.map { it.label })
        assertEquals(at(sun + 6, 17), v.sessions.last().startMs) // Saturday: no work, the evening from 17:00
        val card = v.cards.single()
        assertEquals(SessionStatus.ASK, card.status)
        assertEquals("Next: Tue 17:45 · Legs", card.next)
        // A day-long conference next Tuesday moves that session like any other.
        val moved = book(listOf(gym(done = setOf(mon + 2, mon + 4))), today = sun, now = at(sun, 18),
            events = listOf(ev("conference", at(sun + 2, 6), at(sun + 2, 22))))
        assertEquals(sun + 3, moved.sessions[1].day)
    }

    @Test
    fun aSlotThatPassedUnansweredIsRebookedFromTomorrow() {
        // Monday's slot passed unticked; on Tuesday the week needs all three in Tue–Sun.
        val v = book(listOf(gym()), today = mon + 1, now = at(mon + 1, 8))
        assertEquals(listOf(mon + 1, mon + 3, mon + 5), v.sessions.map { it.day })
    }

    @Test
    fun theRestDayGivesWayWhenTheWeekRunsShort() {
        // Thursday with nothing done: Thu, Fri, Sat, Sun left for 3.
        val thu = mon + 3
        val v = book(listOf(gym()), today = thu, now = at(thu, 8))
        assertEquals(listOf(thu, thu + 2, thu + 3), v.sessions.map { it.day }) // a rest on Friday, then Sat and Sun back to back
        assertEquals("Booked Today 17:45 · Sat 17:00 · Sun 17:00", v.lines["gym"])
        // Saturday with nothing done: all that's left (Sat, Sun) and still one short.
        val sat = mon + 5
        assertEquals("Booked Today 17:00 · Sun 17:00 · no room for 1 more", book(listOf(gym()), today = sat, now = at(sat, 8)).lines["gym"])
    }

    @Test
    fun theRotationCarriesOnFromTheLastSessionTicked() {
        val ppl = listOf("Push", "Pull", "Legs")
        val v = book(listOf(gym(rotation = ppl, lastLabel = "Pull")))
        assertEquals(listOf("Legs", "Push", "Pull"), v.sessions.map { it.label })
        assertEquals("Gym · Legs", v.cards.single().heading)
        assertEquals("Next: Wed 17:45 · Push", v.cards.single().next)
        assertEquals(listOf("Push", "Pull", "Legs"), book(listOf(gym(rotation = ppl))).sessions.map { it.label })
    }

    @Test
    fun wentTodayShowsTheNoteAndWhatsNext() {
        val v = book(listOf(gym(done = setOf(mon), rotation = listOf("Upper", "Lower"), lastLabel = "Upper", todayLabel = "Upper", todayNote = "5 km")), now = at(mon, 20))
        val card = v.cards.single()
        assertEquals(SessionStatus.WENT, card.status)
        assertEquals("Gym · Upper", card.heading)
        assertEquals("Went · 1 of 3 this week", card.line)
        assertEquals("5 km", card.note)
        assertEquals("Next: Wed 17:45 · Lower", card.next)
        assertTrue(card.answered)
    }

    @Test
    fun aHabitAddedTodayIsntBookedBeforeItExisted() {
        val v = book(listOf(gym(created = at(mon, 20))), now = at(mon, 20))
        assertEquals(at(mon, 20, 30), v.sessions[0].startMs) // half an hour on, still ends by 21:30
        val late = book(listOf(gym(created = at(mon, 21))), now = at(mon, 21))
        assertTrue(late.sessions.none { it.day == mon })
        assertNull(late.cards.firstOrNull())
    }

    @Test
    fun twoBookedHabitsNeverOverlap() {
        val v = book(listOf(gym(perWeek = 7, id = "a", title = "Gym"), gym(perWeek = 7, id = "b", title = "Swim", minutes = 30)))
        val monday = v.sessions.filter { it.day == mon }
        assertEquals(2, monday.size)
        assertTrue(monday[0].endMs <= monday[1].startMs)
        assertEquals(listOf("Gym", "Swim"), v.cards.map { it.heading })
    }

    @Test
    fun aNightShiftKeepsItsMorningBusy() {
        val nights = WorkSchedule(setOf(1, 2, 3, 4, 5), 22 * 60, 6 * 60)
        val v = book(listOf(gym(timing = HabitTiming.MORNING)), today = mon + 1, now = at(mon + 1, 5), schedule = nights)
        assertEquals(at(mon + 1, 6, 30), v.sessions[0].startMs) // the shift ends 06:00, +15 min, aligned within the morning
    }

    @Test
    fun rotationsAndNotesAreTidied() {
        assertEquals(listOf("Push", "Pull/Legs"), SessionRules.cleanRotation(listOf(" Push ", "", "Pull|Legs")))
        assertEquals("Push · Pull · Legs", SessionRules.rotationLabel(SessionRules.rotationAt(1)))
        assertEquals("No rotation", SessionRules.rotationLabel(SessionRules.rotationAt(0)))
        assertEquals("5 km", SessionRules.cleanNote("  5 km\n"))
        assertNull(SessionRules.cleanNote("  "))
        assertEquals(SessionRules.MAX_NOTE, SessionRules.cleanNote("x".repeat(200))!!.length)
    }

    // ---- Through Goals, across two devices ----

    private fun sync2(a: os.meka.core.testing.Device, b: os.meka.core.testing.Device) { a.sync(); b.sync(); a.sync() }

    @Test
    fun wentOnTheFoldAndDidntGoOnTheMacSettleOnWent() {
        val world = SyncWorld()
        world.clock.nowMs = at(mon, 8)
        var n = 0
        val fold = world.device("android")
        val mac = world.device("mac")
        val gf = Goals(fold.replica, { "F${n++}" }, { world.clock.nowMs })
        val gm = Goals(mac.replica, { "M${n++}" }, { world.clock.nowMs })
        val id = gf.addHabit("Gym", perWeek = 3, timing = HabitTiming.EVENING, minutes = 60)
        gf.setHabitBooked(id, true)
        gf.setHabitRotation(id, listOf("Push", "Pull", "Legs"))
        sync2(fold, mac)
        assertTrue(gm.habits().single().booked)
        assertEquals(listOf("Push", "Pull", "Legs"), gm.habits().single().rotation)
        assertTrue(gm.plannerHabits().isEmpty()) // booked habits come as fixed sessions
        // The workout app's link, set on the Fold, opens from the Mac's card; a bad one writes nothing; blank clears it.
        assertTrue(gf.setHabitAppLink(id, "hevy.com"))
        sync2(fold, mac)
        assertEquals("https://hevy.com", gm.habits().single().appLink)
        assertEquals("Hevy", gm.habits().single().appName)
        assertEquals("https://hevy.com", gm.sessionHabits().single().appLink)
        assertTrue(!gm.setHabitAppLink(id, "javascript:alert(1)"))
        assertEquals("https://hevy.com", gm.habits().single().appLink)
        assertTrue(gm.setHabitAppLink(id, " ")); sync2(fold, mac)
        assertNull(gf.habits().single().appLink)
        assertTrue(gf.setHabitAppLink(id, "https://www.strava.com")); sync2(fold, mac)

        world.clock.nowMs = at(mon, 19)
        
        gm.answerSession(id, went = false, label = null, note = null)
        world.clock.advance(1_000)
        gf.answerSession(id, went = true, label = "Push", note = "  felt strong ")
        sync2(fold, mac)
        val s = gm.sessionHabits().single()
        assertEquals(setOf(mon), s.doneDays)
        assertEquals("Push", s.lastLabel)
        assertEquals("felt strong", s.todayNote)
        assertTrue(s.missedDays.isEmpty())

        // Undo clears today's answer on both.
        gm.clearSessionAnswer(id); sync2(fold, mac)
        assertTrue(gf.sessionHabits().single().doneDays.isEmpty())
        assertTrue(gf.sessionHabits().single().missedDays.isEmpty())
    }

    @Test
    fun thePlannerKeepsTodaysSessionFree() {
        val day = DayWindow(at(mon, 0), at(mon + 1, 0))
        val task = Task(
            id = "t", title = "Invoice", notes = null, lifecycle = Lifecycle.ACTIVE, dueAtMs = null, scheduledAtMs = null,
            estimateMinutes = 60, priority = 0, goalId = null, somedayKind = null, createdAtMs = 0, completedAtMs = null, hasConflict = false,
        )
        val session = DayPlanner.HabitPlacement("gym", "Gym · Push", at(mon, 18), at(mon, 19), behind = false)
        val plan = DayPlanner.plan(listOf(task), emptyList(), at(mon, 17, 45), day, sessions = listOf(session))
        assertEquals(listOf(session), plan.habits)
        assertEquals(at(mon, 19), plan.placements.single().startMs) // 17:45 would run into the session
    }

    private fun sessionNotices(v: SessionsView) = NoticeSources.collect(
        ListsView.EMPTY, FastingView.EMPTY, ShutdownView.EMPTY, Today(emptyList(), null, emptyList(), emptyList()), at(mon, 8), cal, sessions = v,
    ).filter { it.source == NoticeSource.SESSION_LEAVE || it.source == NoticeSource.SESSION_ASK }

    @Test
    fun aBookedSessionSaysTimeToGoThenAsksDidYouGo() {
        val ns = sessionNotices(book(listOf(gym(rotation = listOf("Push", "Pull", "Legs")))))
        val leave = ns.single { it.source == NoticeSource.SESSION_LEAVE }
        assertEquals("Gym · Push at 17:45", leave.title)
        assertEquals("Leave by 17:30 · until 18:45", leave.text)
        assertEquals(at(mon, 17, 15), leave.atMs)
        assertEquals(at(mon, 17, 45), leave.expiresAtMs) // stale once it starts
        assertEquals(NoticeTarget.TODAY, leave.target)
        val ask = ns.single { it.source == NoticeSource.SESSION_ASK }
        assertEquals("Did you go? · Gym · Push", ask.title)
        assertEquals("17:45–18:45 · Went or Didn't go in Today", ask.text)
        assertEquals(at(mon, 18, 45), ask.atMs)
        assertEquals(at(mon + 1, 0), ask.expiresAtMs)
        assertEquals(listOf(NoticeAction.WENT, NoticeAction.DIDNT_GO), ask.actions) // answered from the shade
        assertTrue(leave.actions.isEmpty())
        // Only today's session: Wednesday's and Friday's come on their own days.
        assertEquals(2, ns.size)
    }

    @Test
    fun answeringTakesTheNoticesAwayAndAMovedSessionGetsAFreshOne() {
        assertTrue(sessionNotices(book(listOf(gym(done = setOf(mon))), now = at(mon, 19))).isEmpty())
        assertTrue(sessionNotices(book(listOf(gym(missed = setOf(mon))), now = at(mon, 19))).isEmpty())
        val before = sessionNotices(book(listOf(gym()))).single { it.source == NoticeSource.SESSION_LEAVE }
        val moved = sessionNotices(book(listOf(gym()), events = listOf(ev("drinks", at(mon, 18), at(mon, 19)))))
            .single { it.source == NoticeSource.SESSION_LEAVE }
        assertTrue(before.key != moved.key)
        assertEquals("Gym at 19:15", moved.title)
        // The ask keeps one key a day, so it never comes twice.
        val asks = listOf(book(listOf(gym()), now = at(mon, 19)), book(listOf(gym()), now = at(mon, 20)))
            .map { v -> sessionNotices(v).single { it.source == NoticeSource.SESSION_ASK }.key }
        assertEquals(1, asks.distinct().size)
    }

    @Test
    fun theGovernorPostsEachOnceAndNeverLate() {
        val v = book(listOf(gym()))
        val ns = sessionNotices(v)
        val settings = NotificationSettings.DEFAULT
        val r1 = Governor.evaluate(ns, settings, DeviceAlerts.ALL, GovernorState(), at(mon, 17, 16), cal)
        assertEquals(listOf("Gym at 17:45"), r1.post.map { it.title })
        val r2 = Governor.evaluate(ns, settings, DeviceAlerts.ALL, r1.state, at(mon, 17, 20), cal)
        assertTrue(r2.post.isEmpty())
        assertEquals(at(mon, 18), r2.nextWakeMs) // the evening digest, then the ask at 18:45
        // Missed the leave window entirely (phone off): it is stale at the start and never posts late.
        val late = Governor.evaluate(ns, settings, DeviceAlerts.ALL, GovernorState(), at(mon, 18), cal)
        assertTrue(late.post.isEmpty())
        val digest = Governor.evaluate(ns, settings, DeviceAlerts.ALL, r2.state, at(mon, 18), cal)
        assertNull(digest.digest) // nothing for the evening digest yet
        val r3 = Governor.evaluate(ns, settings, DeviceAlerts.ALL, digest.state, at(mon, 18, 46), cal)
        assertEquals(listOf("Did you go? · Gym"), r3.post.map { it.title })
        // A user can lower either source.
        val lowered = settings.copy(tiers = mapOf(NoticeSource.SESSION_LEAVE to NoticeTier.SILENT))
        assertTrue(Governor.evaluate(ns, lowered, DeviceAlerts.ALL, GovernorState(), at(mon, 17, 16), cal).post.isEmpty())
    }

    @Test
    fun didYouGoFromTheNotificationAnswersOnlyThatDaysSessionWhileItAsks() {
        val asking = book(listOf(gym(rotation = listOf("Push", "Pull", "Legs"))), now = at(mon, 19))
        val key = sessionNotices(asking).single { it.source == NoticeSource.SESSION_ASK }.key
        assertEquals("gym", SessionRules.askedHabit(key, mon))
        assertNull(SessionRules.askedHabit(key, mon + 1), "left in the shade past midnight: answers nothing")
        assertNull(SessionRules.askedHabit("session:gym:$mon:leave:123", mon))
        assertNull(SessionRules.askedHabit("session::ask", mon))
        assertNull(SessionRules.askedHabit("fast-goal:1", mon))
        assertEquals("a:b", SessionRules.askedHabit("session:a:b:$mon:ask", mon), "ids may hold a colon")
        val card = SessionRules.askedCard(asking, key, mon)!!
        assertEquals("Push", card.label)
        // Already answered (on the other device): nothing to answer.
        assertNull(SessionRules.askedCard(book(listOf(gym(done = setOf(mon))), now = at(mon, 19)), key, mon))
        assertNull(SessionRules.askedCard(book(listOf(gym(missed = setOf(mon))), now = at(mon, 19)), key, mon))
        // The quiet note after answering.
        val went = book(listOf(gym(done = setOf(mon))), now = at(mon, 19)).cards.single()
        assertEquals("Went · 1 of 3 this week · Next: Wed 17:45", SessionRules.answeredLine(went))
        val missed = book(listOf(gym(missed = setOf(mon))), now = at(mon, 19)).cards.single()
        assertEquals("Not today · no worries · Rebooked for Tue 17:45", SessionRules.answeredLine(missed))
        assertEquals(NoticeAction.DIDNT_GO, NotifyRules.actionFromName(NotifyRules.actionName(NoticeAction.DIDNT_GO)))
        assertNull(NotifyRules.actionFromName("SNOOZE"))
        assertEquals("Didn't go", NotifyRules.actionLabel(NoticeAction.DIDNT_GO))
    }

    // ---- Slice 2b-ii: Today's timeline and the "now" card ----

    private fun todayWith(v: SessionsView, now: Long, events: List<CalendarEvent> = emptyList(), tasks: List<Task> = emptyList()): Today {
        val day = DayWindow(at(mon, 0), at(mon + 1, 0), 0)
        return TodayProjection.project(tasks, now, day, events, cal, v.todayBlocks(mon, now))
    }

    private fun task(id: String, scheduled: Long? = null) =
        Task(id, id, null, Lifecycle.ACTIVE, null, scheduled, 20, 0, null, null, 0, null, false)

    @Test
    fun todaysSessionSitsInTheTimelineAndTakesItsTimeFromTheGaps() {
        val h = listOf(gym(rotation = listOf("Push", "Pull", "Legs")))
        val v = book(h, now = at(mon, 15))
        val tl = todayWith(v, at(mon, 15), events = listOf(ev("call", at(mon, 19, 30), at(mon, 20)))).timeline
        assertEquals(listOf("now", "gap-s-gym", "s-gym", "gap-e-call", "e-call"), tl.rows.map { it.id })
        val row = tl.rows.single { it.kind == TimelineKind.SESSION }
        assertEquals("17:45–18:45", row.time)
        assertEquals("Gym · Push", row.title)
        assertEquals("Leave by 17:30", row.detail)
        assertEquals("gym", row.session?.habitId)
        assertEquals("2 h 45 free", tl.rows.first { it.id == "gap-s-gym" }.title)
        assertEquals("45 min free", tl.rows.first { it.id == "gap-e-call" }.title) // from 18:45, not 15:00
        assertTrue(tl.hasEventsAhead)
        // On now: marked running; once over (or answered) it leaves the timeline.
        val on = todayWith(book(h, now = at(mon, 18)), at(mon, 18)).timeline.rows.single { it.kind == TimelineKind.SESSION }
        assertTrue(on.running)
        assertEquals("Now · until 18:45", on.detail)
        val over = todayWith(book(h, now = at(mon, 19)), at(mon, 19))
        assertTrue(over.timeline.rows.none { it.kind == TimelineKind.SESSION })
        assertEquals(Today.CLEAR, over.clearLine)
        val went = todayWith(book(listOf(gym(done = setOf(mon))), now = at(mon, 15)), at(mon, 15))
        assertTrue(went.timeline.rows.isEmpty())
        // A session ahead isn't "You're clear.".
        assertNull(todayWith(v, at(mon, 15)).clearLine)
    }

    private fun nowCard(h: List<SessionHabit>, now: Long, events: List<CalendarEvent> = emptyList(), tasks: List<Task> = emptyList()): NowView {
        val v = book(h, now = now, events = events)
        return CoverNowRules.now(todayWith(v, now, events, tasks), now, cal, v.cards)
    }

    @Test
    fun theNowCardTakesTheSessionLikeAnEvent() {
        val h = listOf(gym(rotation = listOf("Push", "Pull", "Legs")))
        // Starting soon: before the Up next task.
        val soon = nowCard(h, at(mon, 17, 33), tasks = listOf(task("invoice")))
        assertEquals(NowKind.SESSION, soon.kind)
        assertEquals("In 12 min", soon.label)
        assertTrue(soon.lit)
        assertEquals("Gym · Push", soon.title)
        assertEquals("17:45–18:45 · Leave by 17:30", soon.line)
        assertTrue(soon.actions.isEmpty())
        assertEquals("Then: invoice", soon.thenLine)
        // Within the hour but the task comes first; "Then" names the session.
        val task = nowCard(h, at(mon, 17), tasks = listOf(task("invoice")))
        assertEquals(NowKind.TASK, task.kind)
        assertEquals("Then: Gym · Push at 17:45", task.thenLine)
        // No task: the session within the hour.
        assertEquals("In 45 min", nowCard(h, at(mon, 17)).label)
        // Just started, then on: Went and Didn't go (an early finish).
        val on = nowCard(h, at(mon, 17, 50), tasks = listOf(task("invoice")))
        assertEquals(NowKind.SESSION, on.kind)
        assertEquals("Now · ends 18:45", on.label)
        assertEquals(listOf(NowAction.WENT, NowAction.DIDNT_GO), on.actions)
        assertEquals(NowKind.TASK, nowCard(h, at(mon, 18, 10), tasks = listOf(task("invoice"))).kind)
        assertEquals(NowKind.SESSION, nowCard(h, at(mon, 18, 10)).kind)
        // Over: "Did you go?" leads for an hour, lit; then waits behind the task, quiet, until answered.
        val ask = nowCard(h, at(mon, 19), tasks = listOf(task("invoice")))
        assertEquals("Did you go?", ask.label)
        assertTrue(ask.lit)
        assertEquals("17:45–18:45", ask.line)
        assertEquals("gym", ask.session?.habitId)
        assertEquals(NowKind.TASK, nowCard(h, at(mon, 20), tasks = listOf(task("invoice"))).kind)
        val late = nowCard(h, at(mon, 20))
        assertEquals("Did you go?", late.label)
        assertTrue(!late.lit)
        assertEquals(NowKind.CLEAR, nowCard(listOf(gym(done = setOf(mon))), at(mon, 20)).kind)
    }

    @Test
    fun anEventBeforeTheSessionComesFirstAndTheWidgetCountsDownToEither() {
        val h = listOf(gym())
        // A call at 17:20 (inside work's buffer, so the session stays at 17:45): ten minutes before, the call leads.
        val call = ev("call", at(mon, 17, 20), at(mon, 17, 30))
        val first = nowCard(h, at(mon, 17, 10), events = listOf(call))
        assertEquals(NowKind.EVENT_SOON, first.kind)
        assertEquals("call", first.title)
        val v = book(h, now = at(mon, 17, 35))
        val today = todayWith(v, at(mon, 17, 35))
        val now = CoverNowRules.now(today, at(mon, 17, 35), cal, v.cards)
        val w = HomeWidgetRules.view(now, today, 0, NeedsYouStack.EMPTY, FastingView.EMPTY, at(mon, 17, 35), cal)
        assertEquals(HomeWidgetRules.STARTS_IN, w.next.label)
        assertEquals(at(mon, 17, 45), w.next.countdownToMs)
        assertEquals(at(mon, 17, 45), w.nextChangeMs) // it starts
    }
}
