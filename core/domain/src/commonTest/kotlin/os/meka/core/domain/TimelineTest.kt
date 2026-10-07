package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    // Tue 6 Oct 2026 in London (BST, +1 h): local midnight = 5 Oct 23:00 UTC.
    private val oct6Utc = 1_791_244_800_000L // 2026-10-06T00:00:00Z
    private val start = oct6Utc - hour
    private val day = DayWindow(startMs = start, endMs = start + 24 * hour, utcOffsetMs = hour)
    private fun at(h: Int, m: Int = 0) = start + h * hour + m * min

    private fun ev(id: String, from: Long, to: Long, allDay: Boolean = false, location: String? = null, provider: String = "google") =
        CalendarEvent(id, id, from, to, allDay, location, provider, null, null)

    private fun task(id: String, scheduled: Long? = null, estimate: Int? = null, priority: Int = 0) =
        Task(id, id, null, Lifecycle.ACTIVE, null, scheduled, estimate, priority, null, null, 0, null, false)

    private fun project(tasks: List<Task>, events: List<CalendarEvent>, now: Long) =
        TodayProjection.project(tasks, now, day, events).timeline

    @Test
    fun dateLabelIsTheLocalDayInFull() {
        assertEquals("Tuesday 6 October", project(emptyList(), emptyList(), at(10)).dateLabel)
        assertEquals("Tuesday 6 October", CivilDate.longLabel(CivilDate.toEpochDay(2026, 10, 6)))
    }

    @Test
    fun eventsAndPlannedTasksMergeInTimeOrderWithNowLineAndGaps() {
        val tl = project(
            tasks = listOf(task("write", scheduled = at(14), estimate = 60), task("loose")),
            events = listOf(ev("standup", at(9, 30), at(9, 45)), ev("lunch", at(12), at(13), location = "Canteen"), ev("call", at(16), at(16, 30))),
            now = at(10, 2),
        )
        // standup has ended: folded away.
        assertEquals(listOf("e-standup"), tl.earlier.map { it.id })
        assertEquals("1 earlier", tl.earlierLabel)
        // write (14:00, 60 min) ends 15:00; call at 16:00 leaves a 1 h gap. From now (10:02) to lunch: 1 h 58 → 1 h 55.
        assertEquals(
            listOf("now", "gap", "e-lunch", "gap", "t-write", "gap", "e-call"),
            tl.rows.map { if (it.kind == TimelineKind.GAP) "gap" else it.id },
        )
        assertEquals(listOf("1 h 55 free", "1 h free", "1 h free"), tl.rows.filter { it.kind == TimelineKind.GAP }.map { it.title })
        assertEquals("10:02", tl.rows.first().time)
        assertEquals("gap-e-lunch", tl.rows[1].id) // stable while now moves
        val lunch = tl.rows.first { it.id == "e-lunch" }
        assertEquals("12:00–13:00", lunch.time)
        assertEquals("Canteen", lunch.detail)
        assertEquals("14:00", tl.rows.first { it.id == "t-write" }.time)
        assertEquals("60 min", tl.rows.first { it.id == "t-write" }.detail)
        assertEquals(listOf("loose"), tl.anytime.map { it.id })
    }

    @Test
    fun runningEventsSitAboveTheNowLineAndFreeTimeCountsFromTheirEnd() {
        val tl = project(emptyList(), listOf(ev("workshop", at(9), at(11)), ev("review", at(11, 20), at(12))), now = at(10))
        assertEquals(listOf("e-workshop", "now", "e-review"), tl.rows.map { it.id })
        assertTrue(tl.rows.first().running)
    }

    @Test
    fun overdueTimeOnAPlannedTaskKeepsItVisibleAboveNow() {
        // A planned task whose time has passed isn't folded: it still needs doing. (Up next takes the next one.)
        val t = TodayProjection.project(listOf(task("morning", scheduled = at(8)), task("later", scheduled = at(15))), at(10), day)
        assertEquals("later", t.upNext!!.id)
        assertEquals(listOf("t-morning", "now", "gap", "t-later"), t.timeline.rows.map { if (it.kind == TimelineKind.GAP) "gap" else it.id })
    }

    @Test
    fun shortGapsAreNotShownAndRoundDownToFiveMinutes() {
        val tl = project(emptyList(), listOf(ev("a", at(10, 20), at(10, 30)), ev("b", at(10, 50), at(11))), now = at(10))
        assertTrue(tl.rows.none { it.kind == TimelineKind.GAP })
        assertEquals("45 min free", TimelineRules.freeLabel(47))
        assertEquals("2 h free", TimelineRules.freeLabel(122))
        assertEquals("1 h 05 free", TimelineRules.freeLabel(65))
    }

    @Test
    fun anEventFromLastNightSaysUntilAndAllDayEventsAreChips() {
        val tl = project(
            emptyList(),
            listOf(
                ev("late shift", start - 2 * hour, at(1)),
                ev("Holiday", oct6Utc, oct6Utc + 24 * hour, allDay = true),
            ),
            now = at(0, 30),
        )
        assertEquals(listOf("Holiday"), tl.allDay.map { it.title })
        val shift = tl.rows.first { it.id == "e-late shift" }
        assertEquals("Until 01:00", shift.time)
        assertTrue(shift.running)
    }

    @Test
    fun nextEventWithinTheHourGoesToUpNext() {
        val events = listOf(ev("Call with Tunde", at(10, 25), at(11), location = "Meet"), ev("Gym", at(18), at(19)))
        val next = project(emptyList(), events, now = at(10)).nextEvent!!
        assertEquals("Call with Tunde in 25 min", next.line)
        assertEquals("10:25–11:00 · Meet", next.detail)
        assertEquals("Standup in 1 h", project(emptyList(), listOf(ev("Standup", at(11), at(11, 15))), at(10)).nextEvent!!.line)
        assertNull(project(emptyList(), events, now = at(9, 20)).nextEvent) // 65 min away
        // Partly through a minute rounds up: 10:00:30 → 10:25 is "25 min".
        assertEquals(25, project(emptyList(), events, now = at(10) + 30_000).nextEvent!!.minutes)
    }

    @Test
    fun noTimedThingsMeansNoNowLine() {
        val tl = project(listOf(task("first", priority = 1), task("loose")), emptyList(), at(10))
        assertTrue(tl.rows.isEmpty())
        assertTrue(!tl.hasTimedOrAllDay)
        assertEquals(1, tl.anytime.size)
    }

    @Test
    fun needsYouIsNotRepeatedButAPlannedUpNextStaysInTheTimeline() {
        val overdue = Task("od", "od", null, Lifecycle.ACTIVE, at(9), at(8), null, 0, null, null, 0, null, false)
        val t = TodayProjection.project(listOf(overdue, task("next", scheduled = at(11)), task("loose")), at(10), day)
        assertEquals("next", t.upNext!!.id)
        assertTrue(t.timeline.rows.none { it.task?.id == "od" })
        assertTrue(t.timeline.rows.any { it.task?.id == "next" })
        // An unscheduled Up next isn't repeated under Anytime today.
        val u = TodayProjection.project(listOf(task("a", priority = 2), task("b")), at(10), day)
        assertEquals("a", u.upNext!!.id)
        assertEquals(listOf("b"), u.timeline.anytime.map { it.id })
    }

    // ---- Today clarity (Meka, 2026-10-07): the "All day" group and an honest "You're clear." ----

    private val oct6Day = CivilDate.toEpochDay(2026, 10, 6)
    private fun allDay(id: String, title: String = id, days: Int = 1, calendar: String? = "Personal", provider: String = "google") =
        CalendarEvent(id, title, oct6Day * 24 * hour, (oct6Day + days) * 24 * hour, true, null, provider, null, calendar)

    @Test
    fun allDayEntriesAreRowsWithTheirCalendarAndHowLongTheyRun() {
        val tl = project(emptyList(), listOf(allDay("b", "Bank holiday"), allDay("a", "Away", days = 3), allDay("f", "Barça v Sevilla", calendar = null, provider = "fixtures")), at(10))
        assertEquals(listOf("Away", "Bank holiday", "Barça v Sevilla"), tl.allDayItems.map { it.event.title })
        assertEquals(listOf("Personal · until Thu 8 Oct", "Personal", "Fixtures"), tl.allDayItems.map { it.line })
        val outlook = AllDayRules.item(allDay("o", calendar = null, provider = "microsoft"), oct6Day)
        assertEquals("Outlook", outlook.line)
        assertNull(AllDayRules.item(allDay("g", calendar = null), oct6Day).line)
    }

    @Test
    fun atMostThreeShowThenMoreUnfoldsTheRest() {
        val items = (1..5).map { AllDayRules.item(allDay("e$it"), oct6Day) }
        assertEquals(3, AllDayRules.shown(items, open = false).size)
        assertEquals("+2 more", AllDayRules.moreLabel(items, open = false))
        assertEquals(5, AllDayRules.shown(items, open = true).size)
        assertNull(AllDayRules.moreLabel(items, open = true))
        val three = items.take(3)
        assertEquals(3, AllDayRules.shown(three, open = false).size)
        assertNull(AllDayRules.moreLabel(three, open = false))
    }

    @Test
    fun entriesThatReadLikeToDosOfferMakeItATask() {
        listOf("Check if to pay for the parking permit", "Pay council tax", "call the garage", "- Book MOT", "To do: forms", "Reminder: bins", "Renew passport")
            .forEach { assertTrue(AllDayRules.looksLikeTodo(it), it) }
        listOf("Bank holiday", "Mum's birthday", "Away", "Checkout day", "Payday", "Today", "Reminders app", "Calling Hours")
            .forEach { assertFalse(AllDayRules.looksLikeTodo(it), it) }
        assertTrue(AllDayRules.item(allDay("p", "Pay rent"), oct6Day).todo)
        // Fixtures never do, whatever they're called.
        assertFalse(AllDayRules.item(allDay("f", "Check the line-up", provider = "fixtures"), oct6Day).todo)
    }

    @Test
    fun clearOnlyWhenNothingIsLeftIncludingAllDayItems() {
        fun line(events: List<CalendarEvent>, tasks: List<Task> = emptyList(), now: Long = at(15)) =
            TodayProjection.project(tasks, now, day, events).clearLine
        assertEquals("You're clear.", line(emptyList()))
        assertTrue(TodayProjection.project(emptyList(), at(15), day).isAllClear)
        assertFalse(TodayProjection.project(emptyList(), at(15), day, listOf(allDay("b"))).isAllClear)
        // Only events that have ended: nothing is left.
        assertEquals("You're clear.", line(listOf(ev("standup", at(9), at(9, 30)))))
        // All-day items remain: not clear, but nothing else is timed.
        assertEquals("Nothing else timed today", line(listOf(allDay("b", "Bank holiday"), ev("standup", at(9), at(9, 30)))))
        // An event still to come, or a task: the timeline and the task say what's left.
        assertNull(line(listOf(allDay("b"), ev("call", at(16), at(17)))))
        assertNull(line(listOf(ev("call", at(14), at(16)))))
        assertNull(line(emptyList(), listOf(task("loose"))))
    }

    // ---- All-day polish (Meka, 2026-10-07 22:37): "All day" once, the calendar named once when shared ----

    @Test
    fun oneCalendarIsNamedOnceInTheLabelAndRowsDropTheirCaption() {
        val tl = project(emptyList(), listOf(allDay("w", "Weekly goals", calendar = "Timestripe"), allDay("r", "Run 5k", days = 2, calendar = "Timestripe")), at(10))
        assertEquals("All day · Timestripe", tl.allDayLabel)
        assertEquals(listOf("until Wed 7 Oct", null), tl.allDayItems.map { it.line })
        assertEquals(setOf("Timestripe"), tl.allDayItems.map { it.calendarLabel }.toSet())
        // A single entry is named the same way.
        assertEquals("All day · Personal", project(emptyList(), listOf(allDay("b", "Bank holiday")), at(10)).allDayLabel)
        // An unnamed Google calendar has nothing to name.
        val g = project(emptyList(), listOf(allDay("g1", calendar = null), allDay("g2", calendar = null)), at(10))
        assertEquals("All day", g.allDayLabel)
        assertEquals(listOf(null, null), g.allDayItems.map { it.line })
        assertEquals("All day", TimelineRules.build(emptyList(), emptyList(), emptyList(), at(10), day, LocalCalendar.fixedOffset(hour)).allDayLabel)
    }

    @Test
    fun mixedCalendarsKeepTheirOwnCaptions() {
        val tl = project(emptyList(), listOf(allDay("w", "Weekly goals", calendar = "Timestripe"), allDay("b", "Bank holiday")), at(10))
        assertEquals("All day", tl.allDayLabel)
        assertEquals(listOf("Personal", "Timestripe"), tl.allDayItems.map { it.line })
        // Same name, different accounts: two calendars, so each row says which.
        val other = CalendarEvent("o", "Gym", oct6Day * 24 * hour, (oct6Day + 1) * 24 * hour, true, null, "google", "work@x.com", "Personal")
        val t2 = project(emptyList(), listOf(allDay("b", "Bank holiday"), other), at(10))
        assertEquals("All day", t2.allDayLabel)
        assertEquals(listOf("Personal", "Personal"), t2.allDayItems.map { it.line })
    }

    @Test
    fun calendarsAreKeyedByProviderAccountAndName() {
        val a = CalendarEvent("1", "x", 0, 1, true, null, "google", "Meka@Gmail.com", "Timestripe")
        assertEquals("google|meka@gmail.com|Timestripe", CalendarRules.key(a))
        assertEquals("Timestripe", CalendarRules.label(a))
        assertEquals("Google · Meka@Gmail.com", CalendarRules.detail(a))
        val f = CalendarEvent("2", "Barça v Sevilla", 0, 1, false, null, "fixtures", null, "LaLiga")
        assertEquals("fixtures||", CalendarRules.key(f))
        assertEquals("Fixtures", CalendarRules.label(f))
        assertEquals("Outlook", CalendarRules.label(CalendarEvent("3", "x", 0, 1, true, null, "microsoft", null, null)))
        assertEquals("Google Calendar", CalendarRules.label(CalendarEvent("4", "x", 0, 1, true, null, "google", null, " ")))
        assertEquals(CalendarRules.markId("fixtures||"), CalendarRules.markId("fixtures||"))
        assertTrue(CalendarRules.markId("a").startsWith("c"))
    }

    @Test
    fun calendarChoicesListEveryCalendarByNameAndHiddenOnesWithNoEvents() {
        val ts = CalendarEvent("1", "Goals", 0, 1, true, null, "google", "meka@gmail.com", "Timestripe")
        val p = CalendarEvent("2", "Call", 0, 1, false, null, "google", "meka@gmail.com", "Personal")
        val p2 = p.copy(id = "3")
        val choices = CalendarRules.choices(listOf(ts, p, p2), mapOf(CalendarRules.key(ts) to "Timestripe", "microsoft|old@x.com|Work" to "Work"))
        assertEquals(listOf("Personal", "Timestripe", "Work"), choices.map { it.label })
        assertEquals(listOf(true, false, false), choices.map { it.onToday })
        assertNull(choices.last().detail)
    }
}
