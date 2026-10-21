package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import os.meka.core.testing.SyncWorld

/** Week board, slice 3: time estimates on cards, Navilife's guess, and each day's "3h planned · 4h free". */
class BoardCapacityTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    // Wed 21 Oct 2026, 10:00 UTC.
    private val today = CivilDate.toEpochDay(2026, 10, 21)
    private val now = today * dayMs + 10 * 3_600_000L

    private fun task(id: String, title: String = id, estimate: Int? = null, scheduled: Long? = null, lifecycle: Lifecycle = Lifecycle.ACTIVE, completed: Long? = null) =
        Task(id, title, null, lifecycle, null, scheduled, estimate, 0, null, null, 0, completed, false)

    private fun at(day: Long, hour: Int) = day * dayMs + hour * 3_600_000L

    private fun event(id: String, start: Long, end: Long, allDay: Boolean = false) =
        CalendarEvent(id, id, start, end, allDay, null, "google", "a", "Personal")

    private val weekdays = WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60)

    @Test
    fun labelsReadShortAndSnapToTheFourChoices() {
        assertEquals("15m", TaskEstimateRules.label(15))
        assertEquals("1h", TaskEstimateRules.label(60))
        assertEquals("1h 30m", TaskEstimateRules.label(90))
        assertEquals("0m", TaskEstimateRules.label(-5))
        assertEquals(15, TaskEstimateRules.snap(5))
        assertEquals(30, TaskEstimateRules.snap(40))
        assertEquals(60, TaskEstimateRules.snap(75))
        assertEquals(60, TaskEstimateRules.snap(90), "a tie goes to the smaller")
        assertEquals(120, TaskEstimateRules.snap(300))
    }

    @Test
    fun theGuessIsTheMiddleOfTheClosestMatchesAndNeverWhenAlreadySized() {
        val history = listOf(
            task("a", "Call the garage about the MOT", estimate = 15),
            task("b", "Book MOT at the garage", estimate = 60),
            task("c", "Book the MOT", estimate = 30),
            task("d", "Wash the car", estimate = 120),
        )
        // "Book MOT garage": b shares three words, so b alone decides.
        assertEquals(60, TaskEstimateRules.suggest(task("x", "book mot garage"), history))
        // "MOT" alone: a, b and c tie on one word; the middle of 15, 30, 60 is 30.
        assertEquals(30, TaskEstimateRules.suggest(task("x", "MOT reminder"), history))
        assertNull(TaskEstimateRules.suggest(task("x", "Plan the party"), history))
        assertNull(TaskEstimateRules.suggest(task("x", "Book MOT", estimate = 15), history), "already sized")
        assertNull(TaskEstimateRules.suggest(task("b", "Book MOT at the garage"), listOf(task("b", "Book MOT at the garage", estimate = 60))), "not from itself")
    }

    @Test
    fun theMenuTicksTheOneSetOrMarksTheGuess() {
        val guessed = TaskEstimateRules.choices(null, 30)
        assertEquals(listOf("15m", "30m · suggested", "1h", "2h"), guessed.map { it.menuLabel })
        val set = TaskEstimateRules.choices(60, 30)
        assertEquals(listOf("15m", "30m", "✓ 1h", "2h"), set.map { it.menuLabel })
    }

    @Test
    fun freeTimeIsThePlannersDayLessEventsWorkAndTheCommute() {
        val work = WorkHours(weekdays, commute = CommuteSetting(60, 60), homeDays = setOf(today + 2))
        // Today from now (10:00) to 21:00, but at work 10–17 and commuting 17–18: 18:00–21:00.
        assertEquals(180, TaskEstimateRules.freeMinutes(today, today, now, emptyList(), cal, work))
        // No work: from now to 21:00.
        assertEquals(11 * 60, TaskEstimateRules.freeMinutes(today, today, now, emptyList(), cal, null))
        // Friday at home: no commute, free 17–21.
        assertEquals(4 * 60, TaskEstimateRules.freeMinutes(today + 2, today, now, emptyList(), cal, work))
        // Saturday with a 13:00–14:30 event and an all-day one: 12 h less 1 h 30.
        val sat = today + 3
        val events = listOf(event("lunch", at(sat, 13), at(sat, 14) + 30 * 60_000L), event("away", at(sat, 0), at(sat + 1, 0), allDay = true))
        assertEquals(10 * 60 + 30, TaskEstimateRules.freeMinutes(sat, today, now, events, cal, work))
    }

    @Test
    fun eachColumnFromTodayShowsPlannedAgainstFreeAndTurnsAmberWhenOver() {
        val sat = today + 3
        val b = WeekBoardRules.build(
            listOf(
                task("mot", "Book MOT", estimate = 60, scheduled = at(sat, 9)),
                task("tidy", "Tidy the shed", estimate = 120, scheduled = at(sat, 10)),
                task("call", "Call the bank", scheduled = at(sat, 11)),
                task("big", "Paint the fence", estimate = 120, scheduled = at(today + 1, 9)),
                task("big2", "Clear the gutters", estimate = 120, scheduled = at(today + 1, 9)),
                task("old", "Old one", lifecycle = Lifecycle.DONE, completed = at(today - 1, 12)),
            ),
            emptyList(), now, cal, WorkHours(weekdays, commute = CommuteSetting(60, 60)),
        )
        val s = b.day(sat)!!.capacity!!
        assertEquals(180, s.plannedMin)
        assertEquals(12 * 60, s.freeMin)
        assertEquals(1, s.unsized)
        assertFalse(s.over)
        assertEquals("3h planned · 12h free", s.line)
        assertEquals(0.25f, s.fill)
        assertTrue(b.day(sat)!!.accessibilityLabel.endsWith("3 hours planned, 12 hours free, 1 task without a time"))
        assertEquals("1h", b.day(sat)!!.cards.first { it.id == "mot" }.estimate)
        assertEquals("09:00 · 1h", b.day(sat)!!.cards.first { it.id == "mot" }.meta)

        val thu = b.day(today + 1)!!.capacity!!
        assertTrue(thu.over, "4h planned on an office day with 3h free")
        assertEquals("4h planned · 1h over", thu.line)
        assertEquals(1f, thu.fill)

        assertNull(b.day(today - 1)!!.capacity, "no capacity on a day gone by")
        assertEquals("12h free", b.day(today + 4)!!.capacity!!.line)
    }

    @Test
    fun aCardWithNoEstimateCarriesNavilifesGuess() {
        val b = WeekBoardRules.build(
            listOf(
                task("done", "Book the MOT", estimate = 30, lifecycle = Lifecycle.DONE, completed = at(today - 9, 12)),
                task("new", "Book MOT for the van"),
            ),
            emptyList(), now, cal,
        )
        val card = b.day(today)!!.cards.single()
        assertEquals(30, card.suggestedMinutes)
        assertEquals("30m · suggested", card.estimateChoices[1].menuLabel)
        assertNull(card.estimate)
    }

    @Test
    fun setEstimateStoresAndClears() {
        val world = SyncWorld()
        val a = world.device("android")
        val id = a.tasks.create(NewTask("Book MOT"))
        assertNull(a.tasks.setEstimate(id, 30))
        assertEquals(30, a.tasks.get(id)!!.estimateMinutes)
        assertEquals(30, a.tasks.setEstimate(id, null))
        assertNull(a.tasks.get(id)!!.estimateMinutes)
        assertFailsWith<ValidationException> { a.tasks.setEstimate(id, 0) }
        assertFailsWith<ValidationException> { a.tasks.setEstimate(id, 25 * 60) }
    }
}
