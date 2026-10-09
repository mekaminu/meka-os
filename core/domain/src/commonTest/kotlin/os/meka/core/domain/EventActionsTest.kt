package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventActionsTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    private val world = SyncWorld()
    // Fixed +1 h (London in October before the clocks go back).
    private val cal = LocalCalendar.fixedOffset(hour)
    private val tue6 = CivilDate.toEpochDay(2026, 10, 6)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private val a = world.device("android")
    private val m = world.device("mac")
    private fun actions(d: Device) = EventActions(d.replica, d.tasks, { world.clock.nowMs }, cal)
    private val ea = actions(a)
    private val em = actions(m)

    init { world.clock.nowMs = at(tue6, 10) }

    private fun ev(id: String = "ev1", title: String = "Call with Tunde", start: Long = at(tue6, 14), end: Long = at(tue6, 15), allDay: Boolean = false) =
        CalendarEvent(id, title, start, end, allDay, null, "google", "meka@gmail.com", "Personal")

    private fun syncBoth() { a.sync(); m.sync(); a.sync() }

    @Test
    fun prepTaskIsPlannedBeforeTheEventAndDueAtItsStart() {
        val e = ev()
        val id = ea.addPrep(e)
        assertEquals("pev1", id)
        val t = a.tasks.get(id)!!
        assertEquals("Prepare for Call with Tunde", t.title)
        assertEquals(at(tue6, 13, 30), t.scheduledAtMs)
        assertEquals(at(tue6, 14), t.dueAtMs)
        assertEquals(15, t.estimateMinutes)
        assertEquals("ev1", t.eventId)
        assertEquals(Lifecycle.ACTIVE, t.lifecycle)
        assertEquals(t, ea.marks().prepTasks["ev1"])
    }

    @Test
    fun prepTaskTooCloseToTheEventIsDueButNotPlanned() {
        world.clock.nowMs = at(tue6, 13, 45)
        val t = a.tasks.get(ea.addPrep(ev()))!!
        assertNull(t.scheduledAtMs)
        assertEquals(at(tue6, 14), t.dueAtMs)
    }

    @Test
    fun allDayEventsArePreparedBy9OnTheirDay() {
        val e = ev(start = (tue6 + 2) * CivilDate.DAY_MS, end = (tue6 + 3) * CivilDate.DAY_MS, allDay = true, title = "Bank holiday")
        val t = a.tasks.get(ea.addPrep(e))!!
        assertNull(t.scheduledAtMs)
        assertEquals(at(tue6 + 2, 9), t.dueAtMs)
    }

    @Test
    fun longTitlesAreCutAtAWord() {
        val long = (1..80).joinToString(" ") { "word$it" }
        val p = PrepRules.plan(ev(title = long), world.clock.nowMs, cal)
        assertTrue(p.title.length <= PrepRules.MAX_TITLE)
        assertTrue(p.title.endsWith("…"))
        assertFalse(p.title.dropLast(1).endsWith(" "))
    }

    @Test
    fun addingTwiceOrOnBothDevicesOfflineMakesOneTask() {
        val e = ev()
        ea.addPrep(e)
        ea.addPrep(e)
        em.addPrep(e)
        syncBoth()
        listOf(a, m).forEach { d ->
            val preps = d.tasks.all().filter { it.eventId == "ev1" }
            assertEquals(1, preps.size)
            assertFalse(preps[0].hasConflict)
        }
    }

    @Test
    fun anOpenPrepTaskIsLeftAsItIs() {
        val id = ea.addPrep(ev())
        a.tasks.edit(id, TaskEdit(title = "Read Tunde's deck"))
        ea.addPrep(ev())
        assertEquals("Read Tunde's deck", a.tasks.get(id)!!.title)
    }

    @Test
    fun aDeletedOrFinishedPrepTaskComesBack() {
        val id = ea.addPrep(ev())
        a.tasks.delete(id)
        assertNull(ea.marks().prepTasks["ev1"])
        assertEquals(id, ea.addPrep(ev()))
        assertEquals(Lifecycle.ACTIVE, a.tasks.get(id)!!.lifecycle)

        a.tasks.complete(id)
        assertTrue(ea.marks().prepTasks["ev1"]!!.isDone)
        ea.addPrep(ev())
        val back = a.tasks.get(id)!!
        assertEquals(Lifecycle.ACTIVE, back.lifecycle)
        assertNull(back.completedAtMs)
    }

    @Test
    fun hideAndShowSyncAndTheLastTapWins() {
        ea.hide("ev1")
        assertTrue(ea.marks().isHidden("ev1"))
        syncBoth()
        assertTrue(em.marks().isHidden("ev1"))
        assertEquals(listOf("ev2"), em.marks().visible(listOf(ev(), ev("ev2"))).map { it.id })

        // Offline on both: Android shows it, then the Mac hides it again later; the later tap wins everywhere.
        ea.show("ev1")
        world.clock.nowMs += min
        em.hide("ev1")
        em.show("ev1")
        world.clock.nowMs += min
        em.hide("ev1")
        syncBoth()
        assertTrue(ea.marks().isHidden("ev1"))
        assertTrue(em.marks().isHidden("ev1"))
        em.show("ev1")
        syncBoth()
        assertFalse(ea.marks().isHidden("ev1"))
    }

    @Test
    fun hidingTwiceWritesNothingNew() {
        ea.hide("ev1")
        val ops = a.replica.pendingPushCount()
        ea.hide("ev1")
        assertEquals(ops, a.replica.pendingPushCount())
        ea.show("ev2") // never hidden
        assertEquals(ops, a.replica.pendingPushCount())
    }

    @Test
    fun hiddenEventsLeaveTheDayButStayListedInTheAgenda() {
        val call = ev()
        val standup = ev("ev2", "Standup", at(tue6, 9, 30), at(tue6, 9, 45))
        val later = ev("ev3", "Dentist", at(tue6 + 4, 9), at(tue6 + 4, 10))
        val v = CalendarAgenda.build(emptyList(), listOf(call, standup, later), at(tue6, 10), cal, hidden = setOf("ev1", "ev3"))
        val today = v.sections[0]
        assertEquals(listOf("ev2"), (today.ended + today.rows).mapNotNull { it.event?.id })
        assertEquals(listOf("ev1"), today.hidden.map { it.id })
        assertEquals("1 hidden from your day", today.hiddenLabel)
        // A day with only a hidden event still shows (so it can be shown again), quietly.
        val sat = v.sections.first { it.firstDay == tue6 + 4 }
        assertEquals(AgendaKind.DAY, sat.kind)
        assertEquals("Nothing planned", sat.subtitle)
        assertNull(sat.emptyLine)
        assertEquals(listOf("ev3"), sat.hidden.map { it.id })
        assertEquals(0, v.weeks[0].days.first { it.epochDay == tue6 + 4 }.dots)
        assertEquals("1 event in the next 30 days", v.summary)
    }

    @Test
    fun theTimelineLeavesOutHiddenEvents() {
        ea.hide("ev1")
        val all = listOf(ev(), ev("ev2", "Standup", at(tue6, 11), at(tue6, 11, 15)))
        val today = TodayProjection.project(emptyList(), at(tue6, 10), CalendarAgenda.window(tue6, cal), ea.marks().visible(all), cal)
        assertEquals(listOf("ev2"), today.events.map { it.id })
    }

    @Test
    fun theDetailSaysWhatIsSet() {
        val e = ev()
        var d = EventDetails.build(e, at(tue6, 10), cal, ea.marks())
        assertFalse(d.hidden)
        assertNull(d.prepLine)
        assertTrue(d.canPrep)

        ea.addPrep(e)
        ea.hide("ev1")
        d = EventDetails.build(e, at(tue6, 10), cal, ea.marks())
        assertTrue(d.hidden)
        assertEquals("pev1", d.prepTaskId)
        assertEquals("Prep task at 13:30", d.prepLine)
        assertFalse(d.canPrep)

        // Tomorrow's event: the day is named.
        val tomorrow = ev("ev9", start = at(tue6 + 1, 9), end = at(tue6 + 1, 10))
        ea.addPrep(tomorrow)
        assertEquals("Prep task at Wed 7 Oct 08:30", EventDetails.build(tomorrow, at(tue6, 10), cal, ea.marks()).prepLine)

        a.tasks.complete("pev1")
        d = EventDetails.build(e, at(tue6, 10), cal, ea.marks())
        assertEquals("Prep task done", d.prepLine)
        assertTrue(d.canPrep)
        // An ended event can't be prepared for.
        assertFalse(EventDetails.build(e, at(tue6, 16), cal, ea.marks()).canPrep)
    }

    // ---- Remind me and Leave by (slice 2) ----

    private fun placed(id: String = "ev2", start: Long = at(tue6, 14)) =
        CalendarEvent(id, "Dentist", start, start + hour, false, "High St Surgery", "google", "meka@gmail.com", "Personal")

    @Test
    fun aReminderIsAClockHeadsUpBeforeTheStartAndStaleOnceItStarts() {
        val e = ev()
        ea.setReminder("ev1", 10)
        assertEquals(10, ea.marks().reminders["ev1"])
        val n = ReminderRules.notices(listOf(e), ea.marks(), world.clock.nowMs, cal).single()
        assertEquals(NoticeSource.EVENT_REMINDER, n.source)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals(NoticePrecision.CLOCK, n.precision)
        assertEquals("Call with Tunde", n.title)
        assertEquals("In 10 min · 14:00", n.text)
        assertEquals(at(tue6, 13, 50), n.atMs)
        assertEquals(at(tue6, 14), n.expiresAtMs)
        assertEquals(NoticeTarget.TODAY, n.target)

        // Through the governor: wakes for it, posts at 13:50, once.
        val settings = NotificationSettings.DEFAULT
        val r0 = Governor.evaluate(listOf(n), settings, DeviceAlerts.ALL, GovernorState(), at(tue6, 13), cal)
        assertTrue(r0.post.isEmpty())
        assertEquals(at(tue6, 13, 50), r0.nextWakeMs)
        assertEquals(NoticePrecision.CLOCK, r0.nextWakePrecision)
        val r1 = Governor.evaluate(listOf(n), settings, DeviceAlerts.ALL, r0.state, at(tue6, 13, 51), cal)
        assertEquals(listOf(n.key), r1.post.map { it.key })
        assertTrue(Governor.evaluate(listOf(n), settings, DeviceAlerts.ALL, r1.state, at(tue6, 13, 52), cal).post.isEmpty())
        // A phone asleep until after the start never sends it late.
        assertTrue(Governor.evaluate(listOf(n), settings, DeviceAlerts.ALL, r0.state, at(tue6, 14, 1), cal).post.isEmpty())
    }

    @Test
    fun leaveByNeedsAPlaceAndSaysWhenToGo() {
        ea.setLeaveBy("ev2", 30)
        ea.setLeaveBy("ev1", 30) // no place: no notice
        val ns = ReminderRules.notices(listOf(ev(), placed()), ea.marks(), world.clock.nowMs, cal)
        val n = ns.single()
        assertEquals("Leave now for Dentist", n.title)
        assertEquals("Starts 14:00 · 30 min away · High St Surgery", n.text)
        assertEquals(at(tue6, 13, 30), n.atMs)
        assertEquals("Reminder 10 min before · Leave by 13:30 · 30 min away",
            ea.setReminder("ev2", 10).let { ReminderRules.line(placed(), ea.marks(), cal) })
    }

    @Test
    fun hiddenStartedAndAllDayEventsDontRemindAndAMovedEventRemindsAgain() {
        ea.setReminder("ev1", 15)
        ea.setReminder("ev3", 15)
        val allDay = ev("ev3", start = (tue6 + 1) * CivilDate.DAY_MS, end = (tue6 + 2) * CivilDate.DAY_MS, allDay = true)
        val before = ReminderRules.notices(listOf(ev(), allDay), ea.marks(), world.clock.nowMs, cal).single()
        val moved = ReminderRules.notices(listOf(ev(start = at(tue6, 16), end = at(tue6, 17))), ea.marks(), world.clock.nowMs, cal).single()
        assertTrue(before.key != moved.key)
        assertEquals(at(tue6, 15, 45), moved.atMs)
        assertTrue(ReminderRules.notices(listOf(ev()), ea.marks(), at(tue6, 14, 1), cal).isEmpty())
        ea.hide("ev1")
        assertTrue(ReminderRules.notices(listOf(ev()), ea.marks(), world.clock.nowMs, cal).isEmpty())
    }

    @Test
    fun turningOffAndBadValues() {
        ea.setReminder("ev1", 10)
        ea.setReminder("ev1", null)
        assertTrue(ea.marks().reminders.isEmpty())
        ea.setLeaveBy("ev1", 20)
        ea.setLeaveBy("ev1", 0)
        assertTrue(ea.marks().travel.isEmpty())
        assertTrue(runCatching { ea.setReminder("ev1", 241) }.isFailure)
        assertTrue(runCatching { ea.setReminder("ev1", -5) }.isFailure)
        // Turning a reminder on doesn't touch hiding, and the other way round.
        ea.hide("ev1")
        ea.setReminder("ev1", 5)
        assertTrue(ea.marks().isHidden("ev1"))
        ea.show("ev1")
        assertEquals(5, ea.marks().reminderOf("ev1"))
        assertEquals(0, ea.marks().travelOf("ev1"))
    }

    @Test
    fun choicesOnlyOfferTimesStillAhead() {
        world.clock.nowMs = at(tue6, 13, 48)
        assertEquals(listOf(5, 10), ReminderRules.remindChoices(ev(), world.clock.nowMs))
        assertEquals(listOf(10), ReminderRules.travelChoices(placed(), world.clock.nowMs))
        assertTrue(ReminderRules.travelChoices(ev(), world.clock.nowMs).isEmpty())
        val link = placed().copy(location = "https://meet.google.com/abc-defg-hij")
        assertTrue(ReminderRules.travelChoices(link, world.clock.nowMs).isEmpty())
        assertEquals("10 min before", ReminderRules.choiceLabel(10))
        assertEquals("1 h away", ReminderRules.travelLabel(60))
    }

    @Test
    fun remindersSyncLastTapWins() {
        ea.setReminder("ev1", 10)
        syncBoth()
        assertEquals(10, em.marks().reminders["ev1"])
        world.clock.nowMs += min
        em.setReminder("ev1", 30)
        world.clock.nowMs += min
        ea.setLeaveBy("ev1", 15)
        syncBoth()
        assertEquals(em.marks(), ea.marks())
        assertEquals(30, ea.marks().reminders["ev1"])
        assertEquals(15, em.marks().travel["ev1"])
    }

    @Test
    fun theDetailOffersRemindersAndSaysWhatIsSet() {
        val e = placed()
        var d = EventDetails.build(e, at(tue6, 10), cal, ea.marks())
        assertEquals(ReminderRules.REMIND_CHOICES, d.remindChoices)
        assertEquals(ReminderRules.TRAVEL_CHOICES, d.travelChoices)
        assertNull(d.reminderLine)
        ea.setReminder("ev2", 15)
        d = EventDetails.build(e, at(tue6, 10), cal, ea.marks())
        assertEquals(15, d.remindMin)
        assertEquals("Reminder 15 min before", d.reminderLine)
        // Once it has started there's nothing to remind about.
        d = EventDetails.build(e, at(tue6, 14, 5), cal, ea.marks())
        assertNull(d.reminderLine)
        assertTrue(d.remindChoices.isEmpty())
    }

    @Test
    fun noticeSourcesIncludeEventReminders() {
        ea.setReminder("ev1", 5)
        val ns = NoticeSources.collect(
            ListsView.EMPTY, FastingView.EMPTY, ShutdownView.EMPTY, Today(emptyList(), null, emptyList(), emptyList()), world.clock.nowMs, cal,
            events = listOf(ev()), marks = ea.marks(),
        )
        assertEquals(1, ns.count { it.source == NoticeSource.EVENT_REMINDER })
    }

    // ---- Make it a task (Today clarity, 2026-10-07) ----

    private fun todo() = CalendarEvent("ad1", "Check if to pay for the parking permit", tue6 * 24 * hour, (tue6 + 1) * 24 * hour, true, null, "google", null, "Personal")

    @Test
    fun makeItATaskAddsAnUndatedTaskAndTheEntryLeavesToday() {
        val e = todo()
        val id = ea.makeTask(e)
        val t = a.tasks.get(id)!!
        assertEquals("Check if to pay for the parking permit", t.title)
        assertEquals("ad1", t.eventId)
        assertNull(t.dueAtMs)
        assertNull(t.scheduledAtMs)
        assertTrue(ea.marks().isHidden("ad1"))
        // It isn't the entry's prep task.
        assertNull(ea.marks().prepTasks["ad1"])
        val today = TodayProjection.project(a.tasks.all(), at(tue6, 10), DayWindow(at(tue6, 0), at(tue6 + 1, 0), hour), ea.marks().visible(listOf(e)))
        assertTrue(today.timeline.allDay.isEmpty())
        assertEquals(id, (listOfNotNull(today.upNext) + today.yourDay).single().id)
    }

    @Test
    fun makeItATaskOnBothDevicesOfflineMakesOneTaskAndUndoBringsTheEntryBack() {
        ea.makeTask(todo())
        em.makeTask(todo())
        syncBoth()
        listOf(a, m).forEach { d -> assertEquals(1, d.tasks.all().count { it.eventId == "ad1" }) }
        ea.unmakeTask("ad1")
        syncBoth()
        listOf(a, m).forEach { d ->
            assertTrue(d.tasks.all().none { it.eventId == "ad1" })
            assertFalse(actions(d).marks().isHidden("ad1"))
        }
        // Made again after the undo: the same task comes back.
        assertEquals(EventActions.allDayTaskId("ad1"), em.makeTask(todo()))
        assertEquals(Lifecycle.ACTIVE, m.tasks.get("aad1")!!.lifecycle)
    }

    // ---- Hide a calendar from Today (all-day polish, 2026-10-07) ----

    private fun ts(id: String, title: String, allDay: Boolean = true) =
        if (allDay) CalendarEvent(id, title, tue6 * 24 * hour, (tue6 + 1) * 24 * hour, true, null, "google", "meka@gmail.com", "Timestripe")
        else CalendarEvent(id, title, at(tue6, 14), at(tue6, 15), false, null, "google", "meka@gmail.com", "Timestripe")

    @Test
    fun hidingACalendarTakesAllItsEventsOffTodayAndTheUndoBringsThemBack() {
        val events = listOf(ts("t1", "Weekly goals"), ts("t2", "Deep work", allDay = false), ev(), todo())
        val key = CalendarRules.key(events[0])
        ea.hideCalendar(key, "Timestripe")
        val marks = ea.marks()
        assertTrue(marks.isCalendarHidden(events[1]))
        assertTrue(marks.isCalendarKeyHidden(key))
        assertFalse(marks.isHidden("t1")) // the events themselves aren't marked one by one
        assertEquals(listOf("ev1", "ad1"), marks.visible(events).map { it.id })
        val today = TodayProjection.project(emptyList(), at(tue6, 10), DayWindow(at(tue6, 0), at(tue6 + 1, 0), hour), marks.visible(events))
        assertEquals(listOf("Check if to pay for the parking permit"), today.timeline.allDayItems.map { it.event.title })
        assertEquals("All day · Personal", today.timeline.allDayLabel)
        // Calendars lists it, switched off (two "Personal" calendars: one from the account, one with none, told apart by detail).
        assertEquals(
            listOf(Triple("Personal", "Google · meka@gmail.com", true), Triple("Personal", "Google", true), Triple("Timestripe", "Google · meka@gmail.com", false)),
            CalendarRules.choices(events, marks.hiddenCalendars).map { Triple(it.label, it.detail, it.onToday) },
        )
        ea.showCalendar(key)
        assertEquals(4, ea.marks().visible(events).size)
    }

    @Test
    fun hidingACalendarSyncsAndTheLatestSwitchWins() {
        val key = CalendarRules.key(ts("t1", "Weekly goals"))
        ea.hideCalendar(key, "Timestripe")
        ea.hideCalendar(key, "Timestripe") // twice: no new op
        syncBoth()
        assertEquals(mapOf(key to "Timestripe"), actions(m).marks().hiddenCalendars)
        // Shown again on the Mac a minute later: that later switch wins on both devices.
        world.clock.nowMs += 60_000
        em.showCalendar(key)
        syncBoth()
        listOf(a, m).forEach { d -> assertTrue(actions(d).marks().hiddenCalendars.isEmpty()) }
        val ops = a.replica.entities(EntityTypes.CALENDAR_MARK)
        assertEquals(listOf(CalendarRules.markId(key)), ops.map { it.ref.entityId })
    }

    private fun hol(id: String, title: String, calendar: String = "Holidays in United States") =
        CalendarEvent(id, title, tue6 * 24 * hour, (tue6 + 1) * 24 * hour, true, null, "google", "meka@gmail.com", calendar)

    @Test
    fun holidayCalendarsAreRecognisedByNameOnly() {
        listOf(
            "Holidays in United States", "Holidays in United Kingdom", "Public holidays in Spain", "Christian Holidays",
            "Jewish holidays", "Muslim Holidays", "United Kingdom holidays", "United States holidays", "UK Holidays",
            "US Holidays", "Nigeria public holidays", "  holidays  in   Ireland ",
        ).forEach { assertTrue(HolidayCalendars.isHolidayName(it), it) }
        listOf(
            "Holidays", "Holiday", "Family holidays", "Our holidays", "Holiday plans", "Personal", "Timestripe", "Fixtures",
            "School holidays", "Summer holidays", "", null,
        ).forEach { assertFalse(HolidayCalendars.isHolidayName(it), it.toString()) }
        assertTrue(HolidayCalendars.isHolidayKey("google|meka@gmail.com|Holidays in United States"))
        assertTrue(HolidayCalendars.isHolidayKey("microsoft|meka@outlook.com|United Kingdom holidays"))
        assertFalse(HolidayCalendars.isHolidayKey("fixtures||"))
        assertFalse(HolidayCalendars.isHolidayKey("google|meka@gmail.com|Personal"))
        assertTrue(HolidayCalendars.isHoliday(hol("h1", "Columbus Day")))
    }

    @Test
    fun aHolidayCalendarStartsOffTodayAndTheSwitchBringsItBack() {
        val columbus = hol("h1", "Columbus Day")
        val events = listOf(columbus, ev(), todo())
        val key = CalendarRules.key(columbus)
        // No mark yet: off Today, the brief and the rest of my day; the Calendar tab still has it (not hidden one by one).
        val marks = ea.marks()
        assertTrue(marks.isCalendarHidden(columbus))
        assertTrue(marks.isCalendarKeyHidden(key))
        assertFalse(marks.isHidden("h1"))
        assertEquals(listOf("ev1", "ad1"), marks.visible(events).map { it.id })
        val today = TodayProjection.project(emptyList(), at(tue6, 10), DayWindow(at(tue6, 0), at(tue6 + 1, 0), hour), marks.visible(events))
        assertEquals(listOf("Check if to pay for the parking permit"), today.timeline.allDayItems.map { it.event.title })
        // Calendars lists it switched off and says why.
        val row = CalendarRules.choices(events, marks.hiddenCalendars, marks.shownCalendars).single { it.key == key }
        assertEquals("Holidays in United States", row.label)
        assertEquals("Google · meka@gmail.com · " + HolidayCalendars.DETAIL, row.detail)
        assertFalse(row.onToday)
        // Turned on in Calendars: a mark with hiddenFromToday = false is written, synced, and it shows on both devices.
        ea.showCalendar(key)
        syncBoth()
        listOf(a, m).forEach { d ->
            val mk = actions(d).marks()
            assertEquals(setOf(key), mk.shownCalendars)
            assertEquals(3, mk.visible(events).size)
            assertTrue(CalendarRules.choices(events, mk.hiddenCalendars, mk.shownCalendars).single { it.key == key }.onToday)
        }
        ea.showCalendar(key) // twice: no new op
        assertEquals(1, a.replica.entities(EntityTypes.CALENDAR_MARK).size)
        // Off again: hidden, and the switch the other way wins.
        world.clock.nowMs += 60_000
        em.hideCalendar(key, "Holidays in United States")
        syncBoth()
        listOf(a, m).forEach { d -> assertEquals(listOf("ev1", "ad1"), actions(d).marks().visible(events).map { it.id }) }
    }

    @Test
    fun otherCalendarsStillStartOnAndHidingOneHolidayCalendarLeavesTheOthers() {
        val events = listOf(ev(), ts("t1", "Weekly goals"), hol("h1", "Columbus Day"), hol("h2", "Rosh Hashanah", "Jewish Holidays"))
        // Meka's own calendars are on with no mark; showing one that's already on writes nothing.
        ea.showCalendar(CalendarRules.key(ev()))
        assertTrue(a.replica.entities(EntityTypes.CALENDAR_MARK).isEmpty())
        ea.showCalendar(CalendarRules.key(events[3]))
        assertEquals(listOf("ev1", "t1", "h2"), ea.marks().visible(events).map { it.id })
    }
}
