package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeeklyReviewTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private val hourMs = 3_600_000L
    private var n = 0
    private fun ids(): String = "W${n++}"
    private fun now() = world.clock.nowMs

    private val a = world.device("android")
    private val m = world.device("mac")
    private val ta = Tasks(a.replica, ::ids, ::now)
    private val ga = Goals(a.replica, ::ids, ::now)
    private val fa = Fasting(a.replica, ::ids, ::now)
    private val la = Lists(a.replica, ::ids, ::now)
    private val ra = Renewals(a.replica, ::ids, ::now)
    private val wa = WeeklyReview(a.replica, ::now)
    private val wm = WeeklyReview(m.replica, ::now)

    /** Monday of the week the tests start in (Thursday 10:00 UTC). */
    private val monday: Long

    init {
        val today = world.clock.nowMs.floorDiv(dayMs)
        val toThursday = (11 - CivilDate.isoDayOfWeek(today)) % 7
        val thursday = today + toThursday + 7
        world.clock.nowMs = thursday * dayMs + 10 * hourMs
        monday = thursday - 3
    }

    private fun today() = now().floorDiv(dayMs)
    private fun at(day: Long, h: Int) = day * dayMs + h * hourMs
    private fun window(day: Long) = DayWindow(day * dayMs, (day + 1) * dayMs)
    private fun view(
        offset: Int? = null, r: WeeklyReview = wa, t: Tasks = ta, g: Goals = ga, events: List<CalendarEvent> = emptyList(),
    ) = r.view(offset, t.all(), events, g.view(t.all()), fa.ended(), ::window)

    private fun doneOn(title: String, day: Long, h: Int = 12): String {
        val saved = now()
        world.clock.nowMs = at(day, h)
        val id = ta.create(NewTask(title))
        ta.complete(id)
        world.clock.nowMs = saved
        return id
    }

    // ---- Rules ----

    @Test
    fun opensOnLastWeekEarlyInTheWeekAndThisWeekFromThursday() {
        assertEquals(-1, ReviewRules.defaultOffset(monday))
        assertEquals(-1, ReviewRules.defaultOffset(monday + 2))
        assertEquals(0, ReviewRules.defaultOffset(monday + 3))
        assertEquals(0, ReviewRules.defaultOffset(monday + 6))
        assertEquals(-12, ReviewRules.clampOffset(-40))
        assertEquals(0, ReviewRules.clampOffset(3))
    }

    @Test
    fun rangeLabelsNameTheMonthOnceAndTheYearOnlyAcrossYears() {
        val oct5 = CivilDate.toEpochDay(2026, 10, 5)
        assertEquals("Mon 5 – Sun 11 Oct", ReviewRules.rangeLabel(oct5))
        assertEquals("Mon 28 Sep – Sun 4 Oct", ReviewRules.rangeLabel(CivilDate.toEpochDay(2026, 9, 28)))
        assertEquals("Mon 29 Dec 2025 – Sun 4 Jan 2026", ReviewRules.rangeLabel(CivilDate.toEpochDay(2025, 12, 29)))
        assertEquals("This week", ReviewRules.title(0, oct5))
        assertEquals("Last week", ReviewRules.title(-1, oct5))
        assertEquals("Week of 5 Oct", ReviewRules.title(-2, oct5))
    }

    @Test
    fun comparedLineSaysMoreFewerOrTheSame() {
        assertNull(ReviewRules.comparedLine(0, 0))
        assertEquals("3 more done than the week before", ReviewRules.comparedLine(5, 2))
        assertEquals("2 fewer done than the week before", ReviewRules.comparedLine(1, 3))
        assertEquals("The same as the week before", ReviewRules.comparedLine(4, 4))
    }

    // ---- The week ----

    @Test
    fun countsTasksDoneInTheWeekAndComparesWithTheWeekBefore() {
        doneOn("Call the bank", monday, 9)
        doneOn("Book MOT", monday + 2, 15)
        doneOn("Last week's thing", monday - 3)
        ta.create(NewTask("Still to do"))
        val v = view()
        assertEquals(0, v.offset)
        assertTrue(v.isCurrent)
        assertEquals(listOf("Book MOT", "Call the bank"), v.done.map { it.title })
        assertEquals(listOf("Wed", "Mon"), v.done.map { it.dayLabel })
        assertEquals(2, v.tiles.first { it.label == "Done" }.value)
        assertEquals("1 more done than the week before", v.comparedLine)

        val last = view(offset = -1)
        assertEquals("Last week", last.title)
        assertEquals(listOf("Last week's thing"), last.done.map { it.title })
        assertTrue(last.canGoForward)
        assertFalse(v.canGoForward)
        assertTrue(v.canGoBack)
        assertFalse(view(offset = -12).canGoBack)
    }

    @Test
    fun longWeeksListTwelveAndCountTheRest() {
        repeat(15) { doneOn("Task $it", monday + 1) }
        val v = view()
        assertEquals(ReviewRules.MAX_DONE, v.done.size)
        assertEquals(3, v.doneMore)
    }

    @Test
    fun habitsShowTicksAgainstTheWeeksTarget() {
        world.clock.nowMs = at(monday - 7, 8) // added a week earlier, so this week's target is the full one
        val stretch = ga.addHabit("Stretch", perWeek = 3)
        val read = ga.addHabit("Read", perWeek = 7)
        world.clock.nowMs = at(monday + 3, 10)
        ga.setHabitDone(stretch, true, monday)
        ga.setHabitDone(stretch, true, monday + 1)
        ga.setHabitDone(stretch, true, monday + 3)
        ga.setHabitDone(read, true, monday + 2)
        val v = view()
        val s = v.habits.single { it.id == stretch }
        assertEquals(3, s.done)
        assertTrue(s.met)
        assertEquals("3 of 3 · met", s.line)
        assertEquals(listOf(true, true, false, true, false, false, false), s.days)
        val r = v.habits.single { it.id == read }
        assertEquals("1 of 7 so far", r.line)
        assertEquals(r.id, v.habits.first().id) // not met first
        assertEquals("1 of 2 habits met so far", v.habitsLine)
        val tile = v.tiles.single { it.label == "Habit ticks" }
        assertEquals(4, tile.value)
        assertEquals("of 10", tile.detail)

        // A habit added after a past week isn't in that week's review.
        assertTrue(view(offset = -2).habits.isEmpty())
        assertEquals("0 of 3", view(offset = -1).habits.single { it.id == stretch }.line)
    }

    @Test
    fun fastsEndedInTheWeekAreSummed() {
        world.clock.nowMs = at(monday, 20)
        fa.start(0)
        world.clock.nowMs = at(monday + 1, 12) // 16 h
        fa.end()
        world.clock.nowMs = at(monday + 1, 20)
        fa.start(0)
        world.clock.nowMs = at(monday + 2, 10) // 14 h, short of 16
        fa.end()
        world.clock.nowMs = at(monday + 3, 10)
        val v = view()
        assertEquals("2 fasts · average 15 h 00 m · 1 reached the goal", v.fastingLine)
        assertEquals(2, v.tiles.single { it.label == "Fasts" }.value)
        assertNull(view(offset = -1).fastingLine)
    }

    @Test
    fun listsCountWhatArrivedWhatWasDecidedAndRenewalsDealtWith() {
        val w = la.addWaiting("Passport", "HMPO", 3)
        la.received(w)
        la.recordDecision("Stay with Octopus", null, null)
        val r = ra.add("Netflix", ObligationKind.SUBSCRIPTION, today(), RenewalRepeat.MONTHLY, "10.99", null)
        ra.done(r)
        val v = view()
        assertEquals(
            listOf("1 thing you were waiting for arrived", "1 decision made", "1 renewal or bill dealt with"),
            v.listsLines,
        )
        assertEquals(3, v.tiles.single { it.label == "Lists" }.value)
    }

    @Test
    fun stillOpenListsWhatWasPlannedOrDueAndNotDone() {
        ta.create(NewTask("Send invoice", dueAtMs = at(monday + 1, 17)))
        ta.create(NewTask("Gym", scheduledAtMs = at(monday + 2, 7)))
        ta.create(NewTask("Later today", scheduledAtMs = at(monday + 3, 18))) // not yet
        val done = ta.create(NewTask("Done one", dueAtMs = at(monday, 9)))
        ta.complete(done)
        val v = view()
        assertEquals(listOf("Send invoice", "Gym"), v.stillOpen.map { it.title })
        assertEquals("Was due Tue ${CivilDate.fromEpochDay(monday + 1).day}", v.stillOpen[0].detail.substringBeforeLast(" "))
        assertTrue(v.stillOpen[1].detail.startsWith("Planned Wed"))
    }

    @Test
    fun aheadCountsNextWeeksEventsTasksRenewalsReviewsAndChases() {
        val next = monday + 7
        ta.create(NewTask("Dentist prep", scheduledAtMs = at(next + 1, 9)))
        ra.add("Car insurance", ObligationKind.INSURANCE, next + 2, RenewalRepeat.YEARLY, null, null)
        la.addWaiting("Refund", "Shop", 7) // chase on Thursday next week
        val events = listOf(
            CalendarEvent("e1", "Barça v Real", at(next + 5, 15), at(next + 5, 17), false, null, "fixtures", null, null),
            CalendarEvent("e2", "This week's thing", at(monday + 4, 9), at(monday + 4, 10), false, null, "google", null, null),
        )
        val v = view(events = events)
        assertEquals("Next week", v.aheadTitle)
        assertEquals(listOf("1 event", "1 task planned or due", "1 renewal or bill due", "1 to chase"), v.aheadLines)

        // Reviewing last week, "ahead" is this week; further back there's nothing ahead to show.
        val last = view(offset = -1, events = events)
        assertEquals("This week", last.aheadTitle)
        assertEquals("1 event", last.aheadLines.first())
        assertNull(view(offset = -2).aheadTitle)
        assertEquals(listOf("Nothing in the diary yet"), WeeklyReview(world.device("empty").replica, ::now)
            .view(0, emptyList(), emptyList(), GoalsView.EMPTY, emptyList(), ::window).aheadLines)
    }

    @Test
    fun northStarMetricsSayWhenTheyStartCounting() {
        val v = view()
        assertEquals(6, v.northStar.size)
        assertTrue(v.northStar.all { it.value == null && it.line.isNotBlank() }) // nothing could post yet
        assertEquals("Interruptions", v.northStar.single { it.key == "interruptions" }.label)
    }

    // ---- Done reviewing, across devices ----

    @Test
    fun doneReviewingOnTheMacShowsOnTheFold() {
        world.clock.nowMs = at(monday + 6, 18) + 42 * 60_000L // Sunday 18:42
        assertFalse(view().reviewed)
        wm.markReviewed(monday)
        m.sync(); a.sync()
        val v = view()
        assertTrue(v.reviewed)
        assertEquals("Reviewed ${CivilDate.shortLabel(monday + 6)} at 18:42", v.reviewedLine)
        assertFalse(view(offset = -1).reviewed)

        // The next Monday opens last week's review, which is the one already done.
        world.clock.nowMs = at(monday + 7, 8)
        val monday2 = view()
        assertEquals(-1, monday2.offset)
        assertTrue(monday2.reviewed)
    }

    // ---- Sunday-evening card and heads-up ----

    @Test
    fun cardRulesOpenOnSundayEveningAndStayThroughMonday() {
        assertFalse(ReviewRules.cardWindow(monday + 6, 17 * 60 + 59))
        assertTrue(ReviewRules.cardWindow(monday + 6, 18 * 60))
        assertTrue(ReviewRules.cardWindow(monday + 7, 0))
        assertTrue(ReviewRules.cardWindow(monday + 7, 23 * 60))
        assertFalse(ReviewRules.cardWindow(monday + 8, 10 * 60))
        assertFalse(ReviewRules.cardWindow(monday + 5, 20 * 60))
        assertEquals(0, ReviewRules.cardOffset(monday + 6))
        assertEquals(-1, ReviewRules.cardOffset(monday + 7))
        assertEquals("Review your week", ReviewRules.cardTitle(0))
        assertEquals("Review last week", ReviewRules.cardTitle(-1))
        assertEquals("12 done · 3 of 4 habits met · 2 still open", ReviewRules.cardLine(12, 3, 4, 2))
        assertEquals("1 done · 0 of 1 habit met", ReviewRules.cardLine(1, 0, 1, 0))
        assertEquals("A look back, and the week ahead", ReviewRules.cardLine(0, 0, 0, 0))
    }

    @Test
    fun theCardShowsOnSundayEveningAndMondayUntilReviewedOnEitherDevice() {
        doneOn("Ship it", monday + 1)
        doneOn("Post the form", monday + 4)
        // Thursday: no card yet, but the week it will be about is this one.
        val thu = view().card
        assertFalse(thu.offered)
        assertEquals(monday, thu.weekStart)
        assertEquals("", thu.line)

        world.clock.nowMs = at(monday + 6, 17)
        assertFalse(view().card.offered)
        world.clock.nowMs = at(monday + 6, 18)
        val sun = view().card
        assertTrue(sun.offered)
        assertEquals(0, sun.offset)
        assertEquals("Review your week", sun.title)
        assertEquals("2 done", sun.line)

        // Monday: about last week, whichever week the screen shows.
        world.clock.nowMs = at(monday + 7, 9)
        val mon = view(offset = -4).card
        assertTrue(mon.offered)
        assertEquals(monday, mon.weekStart)
        assertEquals(-1, mon.offset)
        assertEquals("Review last week", mon.title)
        assertEquals("2 done", mon.line)

        // Reviewed on the Mac: the Fold's card goes too.
        wm.markReviewed(monday)
        m.sync(); a.sync()
        val after = view().card
        assertFalse(after.offered)
        assertTrue(after.reviewed)

        // Tuesday: gone, and the next card is about this week.
        world.clock.nowMs = at(monday + 8, 9)
        assertFalse(view().card.offered)
        assertEquals(monday + 7, view().card.weekStart)
    }

    @Test
    fun theHeadsUpGoesOutAtSixOnSundayUntilReviewedOrMondayEnds() {
        val cal = LocalCalendar.UTC
        val empty = Today(emptyList(), null, emptyList(), emptyList())
        fun notices() = NoticeSources.collect(ListsView.EMPTY, FastingView.EMPTY, ShutdownView.EMPTY, empty, now(), cal, review = view().card)
            .filter { it.source == NoticeSource.WEEKLY_REVIEW }

        // Thursday: the coming Sunday's notice already exists, so the governor can wake for it.
        val n = notices().single()
        assertEquals(at(monday + 6, 18), n.atMs)
        assertEquals(at(monday + 8, 0), n.expiresAtMs)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals(NoticeTarget.REVIEW, n.target)
        assertEquals("weekly-review:$monday", n.key)

        // Monday: the same key (it posts once), with the week in its text.
        doneOn("Ship it", monday + 2)
        world.clock.nowMs = at(monday + 7, 8)
        val mon = notices().single()
        assertEquals("weekly-review:$monday", mon.key)
        assertEquals("Review last week", mon.title)
        assertEquals("1 done", mon.text)

        wa.markReviewed(monday)
        assertTrue(notices().isEmpty())

        // The governor posts it on Sunday at 18:00 (not in quiet hours), once.
        world.clock.nowMs = at(monday + 13, 18) + 5 * 60_000L
        val settings = NotificationSettings(digestMinutes = emptyList())
        val r = Governor.evaluate(notices(), settings, DeviceAlerts.ALL, GovernorState(), now(), cal)
        assertEquals(listOf("weekly-review:${monday + 7}"), r.post.map { it.key })
        assertTrue(Governor.evaluate(notices(), settings, DeviceAlerts.ALL, r.state, now(), cal).post.isEmpty())
        // Lowered to App only, it never posts.
        val silent = settings.copy(tiers = mapOf(NoticeSource.WEEKLY_REVIEW to NoticeTier.SILENT))
        assertTrue(Governor.evaluate(notices(), silent, DeviceAlerts.ALL, GovernorState(), now(), cal).post.isEmpty())
    }
}
