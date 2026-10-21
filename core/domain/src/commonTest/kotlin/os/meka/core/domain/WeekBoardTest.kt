package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board, slice 2: the Board view — a column per day, his order, events as chips, drag between days and within one. */
class WeekBoardTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    // Wed 21 Oct 2026, 10:00 UTC.
    private val today = CivilDate.toEpochDay(2026, 10, 21)
    private val now = today * dayMs + 10 * 3_600_000L

    private fun task(
        id: String, lifecycle: Lifecycle = Lifecycle.ACTIVE, scheduled: Long? = null, created: Long = 0, rank: Long? = null,
        completed: Long? = null, deferred: Long? = null,
    ) = Task(
        id, id, null, lifecycle, null, scheduled, null, 0, null, null, created, completed, false,
        deferredToDay = deferred, boardRank = rank,
    )

    private fun at(day: Long, hour: Int, minute: Int = 0) = day * dayMs + hour * 3_600_000L + minute * 60_000L

    private fun event(id: String, title: String, start: Long, end: Long, allDay: Boolean = false) =
        CalendarEvent(id, title, start, end, allDay, null, "google", "a", "Personal")

    @Test
    fun columnsRunFromThisMondayForFiveWeeksWithTodayLitAndEarlierDaysPast() {
        val b = WeekBoardRules.build(emptyList(), emptyList(), now, cal)
        assertEquals(5, b.weeks.size)
        assertEquals(35, b.days.size)
        val mon = b.days.first()
        assertEquals("Mon", mon.weekday)
        assertEquals("19", mon.number)
        assertTrue(mon.isPast)
        assertFalse(mon.canDrop)
        val wed = b.day(today)!!
        assertTrue(wed.isToday)
        assertEquals("Today", wed.title)
        assertEquals("Thu 22", b.day(today + 1)!!.title)
        assertEquals(listOf("This week", "Next week", "2–8 Nov"), b.weeks.take(3).map { it.title })
        assertEquals("19–25 Oct", b.weeks[0].range)
        assertEquals("Nothing planned", wed.countLine)
    }

    @Test
    fun tasksSitOnTheirDayUnplannedOnTodayCarriedLeadAndDoneAtTheFootOfTheDayDone() {
        val b = WeekBoardRules.build(
            listOf(
                task("later", scheduled = at(today, 15)),
                task("anytime", created = 5),
                task("carried", scheduled = at(today - 2, 9)),
                task("thu", scheduled = at(today + 1, 9, 30)),
                task("snoozed", deferred = today + 3),
                task("doneMon", Lifecycle.DONE, completed = at(today - 2, 18)),
                task("someday", Lifecycle.SOMEDAY),
                task("dropped", Lifecycle.CANCELLED, scheduled = at(today, 9)),
                task("far", scheduled = at(today + 60, 9)),
            ),
            emptyList(), now, cal,
        )
        val wed = b.day(today)!!
        assertEquals(listOf("carried", "later", "anytime"), wed.cards.map { it.id })
        assertEquals("Carried 2 days", wed.cards[0].line)
        assertTrue(wed.cards[0].carried)
        assertNull(wed.cards[0].time, "an earlier day's time isn't a time today")
        assertEquals("15:00", wed.cards[1].time)
        assertEquals("3 tasks", wed.countLine)
        assertEquals("09:30", b.day(today + 1)!!.cards.single().time)
        assertEquals("snoozed", b.day(today + 3)!!.cards.single().id)
        val mon = b.day(today - 2)!!
        assertEquals(listOf("doneMon"), mon.cards.map { it.id })
        assertTrue(mon.cards.single().done)
        assertEquals("1 done", mon.countLine)
        assertTrue(b.days.none { d -> d.cards.any { it.id in setOf("someday", "dropped", "far") } })
    }

    @Test
    fun hisOrderWinsOncePlacedAndUnplacedCardsFollow() {
        val b = WeekBoardRules.build(
            listOf(task("a", rank = 2_000), task("b", rank = 1_000), task("c", scheduled = at(today, 11)), task("d", created = 9)),
            emptyList(), now, cal,
        )
        assertEquals(listOf("b", "a", "c", "d"), b.day(today)!!.cards.map { it.id })
    }

    @Test
    fun eventsAreChipsAllDayFirstThenByTimeThreeThenMore() {
        val events = listOf(
            event("e1", "Gym", at(today + 1, 18), at(today + 1, 19)),
            event("e2", "Dentist", at(today + 1, 9, 30), at(today + 1, 10)),
            event("e3", "Half term", (today + 1) * dayMs, (today + 2) * dayMs, allDay = true),
            event("e4", "Call", at(today + 1, 12), at(today + 1, 12, 30)),
            event("e5", "Late", at(today, 23), at(today + 1, 1)),
        )
        val thu = WeekBoardRules.build(emptyList(), events, now, cal, hidden = setOf("e4")).day(today + 1)!!
        assertEquals(listOf("All day Half term", "Until 01:00 Late", "09:30 Dentist"), thu.chips.map { "${it.time} ${it.title}" })
        assertEquals("+1 more", thu.moreLabel)
        assertTrue(thu.accessibilityLabel.startsWith("Thursday 22 October, 4 events"))
    }

    @Test
    fun officeHomeAndAwayMarkersComeFromWorkDaysAndTrips() {
        val work = WorkHours(WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30), homeDays = setOf(today + 1), away = mapOf(today + 2 to "Madrid"))
        val b = WeekBoardRules.build(emptyList(), emptyList(), now, cal, work)
        assertEquals("Office", b.day(today)!!.placeLabel)
        assertEquals("Home", b.day(today + 1)!!.placeLabel)
        assertEquals("Away", b.day(today + 2)!!.placeLabel)
        assertNull(b.day(today + 3)!!.placeLabel, "Saturday is a day off")
        assertTrue(b.day(today)!!.accessibilityLabel.contains("office day"))
    }

    @Test
    fun aMoveToAnotherDayKeepsTheTimeAndPlacesTheDaysCards() {
        val tasks = listOf(task("a", scheduled = at(today, 15)), task("x", scheduled = at(today + 1, 9)), task("y", created = 1, scheduled = at(today + 1, 11)))
        val b = WeekBoardRules.build(tasks, emptyList(), now, cal)
        val m = assertNotNull(WeekBoardRules.move(b, "a", today + 1, 1, today, cal))
        assertTrue(m.dayChanged)
        assertEquals(15 * 60, m.minuteOfDay)
        assertEquals(mapOf("x" to 1_000L, "a" to 2_000L, "y" to 3_000L), m.ranks)
        assertEquals("Moved “A” to Tomorrow", WeekBoardRules.moveLine("a", m, today, movedUp = false))
    }

    @Test
    fun nothingMovesIntoThePastADoneCardStaysAndTheSamePlaceIsNoMove() {
        val tasks = listOf(task("a"), task("b", created = 1), task("done", Lifecycle.DONE, completed = at(today, 8)))
        val b = WeekBoardRules.build(tasks, emptyList(), now, cal)
        assertNull(WeekBoardRules.move(b, "a", today - 1, 0, today, cal))
        assertNull(WeekBoardRules.move(b, "done", today + 1, 0, today, cal))
        assertNull(WeekBoardRules.move(b, "a", today, 0, today, cal))
        assertNull(WeekBoardRules.move(b, "a", today + 400, 0, today, cal), "off the board")
        val down = assertNotNull(WeekBoardRules.move(b, "a", today, 1, today, cal))
        assertEquals(mapOf("b" to 1_000L, "a" to 2_000L), down.ranks)
        assertEquals("Moved “A” down", WeekBoardRules.moveLine("a", down, today, movedUp = false))
    }

    @Test
    fun nudgesMoveADayOrAPlace() {
        val tasks = listOf(task("a"), task("b", created = 1))
        val b = WeekBoardRules.build(tasks, emptyList(), now, cal)
        assertEquals(today + 1, WeekBoardRules.nudge(b, "a", 1, 0, today, cal)!!.toDay)
        assertNull(WeekBoardRules.nudge(b, "a", -1, 0, today, cal), "not before today")
        assertNull(WeekBoardRules.nudge(b, "a", 0, -1, today, cal), "already at the top")
        assertEquals(mapOf("b" to 1_000L, "a" to 2_000L), WeekBoardRules.nudge(b, "b", 0, -1, today, cal)!!.ranks)
    }

    @Test
    fun placingOnTheBoardSyncsAndUndoes() {
        val world = SyncWorld()
        val d = world.device("android")
        val nowMs = world.clock.nowMs
        val todayDay = LocalCalendar.UTC.epochDayOf(nowMs)
        val a = d.tasks.create(NewTask("Book MOT", scheduledAtMs = nowMs + 3_600_000L))
        val b = d.tasks.create(NewTask("Call the school", scheduledAtMs = nowMs + 26 * 3_600_000L))
        val board = WeekBoardRules.build(d.tasks.all(), emptyList(), nowMs, LocalCalendar.UTC)
        val move = assertNotNull(WeekBoardRules.move(board, a, todayDay + 1, 0, todayDay, LocalCalendar.UTC))
        val undo = d.tasks.placeOnBoard(move)
        val moved = d.tasks.get(a)!!
        assertEquals(todayDay + 1, LocalCalendar.UTC.epochDayOf(moved.scheduledAtMs!!))
        assertEquals(1_000L, moved.boardRank)
        assertEquals(2_000L, d.tasks.get(b)!!.boardRank)
        val after = WeekBoardRules.build(d.tasks.all(), emptyList(), nowMs, LocalCalendar.UTC)
        assertEquals(listOf(a, b), after.day(todayDay + 1)!!.cards.map { it.id })

        val mac = world.device("mac")
        d.sync(); mac.sync()
        val onMac = WeekBoardRules.build(mac.tasks.all(), emptyList(), nowMs, LocalCalendar.UTC)
        assertEquals(listOf(a, b), onMac.day(todayDay + 1)!!.cards.map { it.id })

        assertTrue(d.tasks.undoBoard(undo))
        val back = d.tasks.get(a)!!
        assertEquals(todayDay, LocalCalendar.UTC.epochDayOf(back.scheduledAtMs!!))
        assertNull(back.boardRank)
        assertNull(d.tasks.get(b)!!.boardRank)
    }
}
