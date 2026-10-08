package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The task detail's When and Notes rows (Fold review 2026-10-08, item 8). */
class TaskWhenTest {
    private val world = SyncWorld()
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val day = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC

    private fun today() = world.clock.nowMs.floorDiv(day)
    private fun at(minute: Int) { world.clock.nowMs = today() * day + minute * 60_000L }
    private fun window() = DayWindow(today() * day, (today() + 1) * day)
    private fun project(dev: Device = fold) = TodayProjection.project(dev.tasks.all(), world.clock.nowMs, window())
    private fun todayIds(dev: Device = fold) = project(dev).let { t -> (t.needsYou.map { it.task } + listOfNotNull(t.upNext) + t.yourDay).map { it.id } }
    private fun view(id: String, dev: Device = fold) = TaskWhenRules.view(dev.tasks.get(id)!!, world.clock.nowMs, cal)

    @Test
    fun aNewTaskIsTodayWithNoTimeAndTheChipsAreTodayAndTomorrow() {
        at(7 * 60 + 57)
        val id = fold.tasks.create(NewTask("Create CR for Data Quality Control"))
        val v = view(id)
        assertEquals("Today", v.label)
        assertEquals(today(), v.day)
        assertNull(v.minute)
        assertEquals(-1, v.minuteOrNone)
        assertEquals(listOf("Today" to true, "Tomorrow" to false), v.chips.map { it.label to it.selected })
        // "Add a time" starts at the next quarter hour at least 15 minutes away: 07:57 → 08:15.
        assertEquals(8 * 60 + 15, v.suggestedMinute)
    }

    @Test
    fun aTimeTodayPlansItOnTheTimeline() {
        at(8 * 60)
        val id = fold.tasks.create(NewTask("Call the bank"))
        fold.tasks.setWhen(id, today(), 14 * 60 + 30)
        val t = fold.tasks.get(id)!!
        assertEquals(today() * day + (14 * 60 + 30) * 60_000L, t.scheduledAtMs)
        assertNull(t.deferredToDay)
        assertEquals("Today · 14:30", view(id).label)
        assertEquals("14:30", view(id).timeLabel)
        assertTrue(project().timeline.rows.any { it.id == "t-$id" })
        assertTrue(project().timeline.anytime.none { it.id == id })

        // No time again: off the timeline, still today (here as Up next, the only thing left).
        fold.tasks.setWhen(id, today(), null)
        assertNull(fold.tasks.get(id)!!.scheduledAtMs)
        assertTrue(project().timeline.rows.none { it.id == "t-$id" })
        assertEquals(id, project().upNext?.id)
    }

    @Test
    fun aLaterDayLeavesTodayAndComesBackOnItsDayAtItsTime() {
        at(9 * 60)
        val id = fold.tasks.create(NewTask("Renew passport photos"))
        val thu = today() + 7
        fold.tasks.setWhen(id, thu, 9 * 60)
        assertEquals(CivilDate.shortLabel(thu) + " · 09:00", view(id).label)
        assertEquals(listOf("Today" to false, "Tomorrow" to false, CivilDate.shortLabel(thu) to true), view(id).chips.map { it.label to it.selected })
        assertTrue(id !in todayIds())

        world.clock.nowMs = thu * day + 8 * 3_600_000L
        assertTrue(id in todayIds())
        assertEquals("Today · 09:00", view(id).label)
    }

    @Test
    fun tomorrowWithNoTimeAndBackToToday() {
        at(10 * 60)
        val id = fold.tasks.create(NewTask("Pay council tax"))
        fold.tasks.setWhen(id, today() + 1, null)
        assertEquals(today() + 1, fold.tasks.get(id)!!.deferredToDay)
        assertEquals("Tomorrow", view(id).label)
        assertEquals(TaskWhenRules.DEFAULT_MINUTE, view(id).suggestedMinute)
        assertTrue(id !in todayIds())

        fold.tasks.setWhen(id, today(), null)
        assertNull(fold.tasks.get(id)!!.deferredToDay)
        assertTrue(id in todayIds())
    }

    @Test
    fun aRepeatingOccurrenceMovesWithoutLeavingItsSeries() {
        at(8 * 60)
        val id = fold.tasks.create(NewTask("Vitamins"))
        fold.tasks.setRepeat(id, Recurrence.Daily())
        fold.tasks.setWhen(id, today() + 2, 8 * 60)
        val t = fold.tasks.get(id)!!
        assertEquals(today(), t.occurrenceDay)
        assertEquals(today() + 2, t.deferredToDay)
        assertEquals(id, t.seriesId)
        // Today again: no snooze left, the occurrence's own day stands.
        fold.tasks.setWhen(id, today(), null)
        assertNull(fold.tasks.get(id)!!.deferredToDay)
        assertTrue(id in todayIds())
    }

    @Test
    fun anOverduePlanReadsAsTodayWithNoTime() {
        at(9 * 60)
        val id = fold.tasks.create(NewTask("Expenses", scheduledAtMs = (today() - 1) * day + 15 * 3_600_000L))
        val v = view(id)
        assertEquals(today(), v.day)
        assertNull(v.minute)
        assertEquals("Today", v.label)
    }

    @Test
    fun theRulesRefuseThePastAndNonsense() {
        at(9 * 60)
        val id = fold.tasks.create(NewTask("Something"))
        assertFailsWith<ValidationException> { fold.tasks.setWhen(id, today() - 1, null) }
        assertFailsWith<ValidationException> { fold.tasks.setWhen(id, today() + TaskWhenRules.MAX_DAYS_AHEAD + 1, null) }
        assertFailsWith<ValidationException> { fold.tasks.setWhen(id, today(), 24 * 60) }
        assertFailsWith<ValidationException> { fold.tasks.setWhen("nope", today(), null) }
        // A done task stays as it was.
        fold.tasks.complete(id)
        fold.tasks.setWhen(id, today() + 1, 9 * 60)
        assertNull(fold.tasks.get(id)!!.scheduledAtMs)
    }

    @Test
    fun timeStepsByQuarterHoursWithinTheDay() {
        assertEquals(9 * 60 + 15, TaskWhenRules.step(9 * 60, 1))
        assertEquals(8 * 60 + 45, TaskWhenRules.step(9 * 60, -1))
        assertEquals(9 * 60 + 15, TaskWhenRules.step(9 * 60 + 7, 1)) // snaps to the quarter first
        assertEquals(0, TaskWhenRules.step(0, -1))
        assertEquals(23 * 60 + 45, TaskWhenRules.step(23 * 60 + 45, 1))
        assertEquals(23 * 60 + 45, TaskWhenRules.suggestedMinute(today(), today(), 23 * 60 + 50))
        assertEquals(10 * 60 + 15, TaskWhenRules.suggestedMinute(today(), today(), 10 * 60))
        assertEquals(10 * 60 + 30, TaskWhenRules.suggestedMinute(today(), today(), 10 * 60 + 1))
        assertEquals("00:05", TaskWhenRules.timeLabel(5))
    }

    @Test
    fun notesAreKeptTrimmedAndBlankClearsThem() {
        val id = fold.tasks.create(NewTask("Plan trip"))
        fold.tasks.setNotes(id, "Flights from LHR\nHotel near the Camp Nou  \n")
        assertEquals("Flights from LHR\nHotel near the Camp Nou", fold.tasks.get(id)!!.notes)
        fold.tasks.setNotes(id, "   ")
        assertNull(fold.tasks.get(id)!!.notes)
        assertFailsWith<ValidationException> { fold.tasks.setNotes(id, "x".repeat(Tasks.MAX_NOTES + 1)) }
    }

    @Test
    fun whenAndNotesSyncToTheMacAndDeleteCanBeUndone() {
        at(8 * 60)
        val id = fold.tasks.create(NewTask("Book dentist"))
        fold.tasks.setWhen(id, today(), 16 * 60)
        fold.tasks.setNotes(id, "Ask about the filling")
        fold.sync(); mac.sync()
        assertEquals("Today · 16:00", view(id, mac).label)
        assertEquals("Ask about the filling", mac.tasks.get(id)!!.notes)

        // Delete from the detail, then Undo on the bar.
        mac.tasks.delete(id)
        assertNull(mac.tasks.get(id))
        mac.tasks.restore(id)
        mac.sync(); fold.sync()
        assertEquals("Today · 16:00", view(id).label)
        assertEquals("Deleted “Book dentist”", TaskWhenRules.deletedLine("Book dentist"))
    }
}
