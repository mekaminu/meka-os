package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepeatingTasksTest {
    private val world = SyncWorld()
    private val d = world.device("android")
    private val day = CivilDate.DAY_MS

    private fun today() = world.clock.nowMs.floorDiv(day)
    private fun window() = DayWindow(today() * day, (today() + 1) * day)
    private fun todayIds(dev: Device = d) =
        TodayProjection.project(dev.tasks.all(), world.clock.nowMs, window()).let { t -> (t.needsYou.map { it.task } + listOfNotNull(t.upNext) + t.yourDay).map { it.id } }
    private fun openOf(series: String, dev: Device = d) =
        dev.tasks.all().filter { it.seriesId == series && !it.lifecycle.isTerminal }

    @Test
    fun completingAnOccurrenceCreatesTheNextOneForItsDay() {
        val id = d.tasks.create(NewTask("Vitamins"))
        d.tasks.setRepeat(id, Recurrence.Daily())
        val t = d.tasks.get(id)!!
        assertEquals("Every day", t.repeatLabel)
        assertEquals(today(), t.occurrenceDay)
        assertEquals(id, t.seriesId)

        d.tasks.complete(id)
        val next = assertNotNull(d.tasks.get(Tasks.occurrenceId(id, today() + 1)))
        assertEquals("Vitamins", next.title)
        assertEquals(Lifecycle.ACTIVE, next.lifecycle)
        assertEquals(t.recurrenceRule, next.recurrenceRule)
        // Tomorrow's occurrence stays out of today, then shows up tomorrow.
        assertTrue(next.id !in todayIds())
        world.clock.advance(day)
        assertTrue(next.id in todayIds())
    }

    @Test
    fun theFirstOccurrenceMovesToTheRulesDayAndKeepsItsTime() {
        // Scheduled for today 07:30; "every weekday" on a weekend moves it to Monday 07:30.
        val sat = (today()..today() + 6).first { CivilDate.isoDayOfWeek(it) == 6 }
        world.clock.nowMs = sat * day + 8 * 3_600_000L
        val id = d.tasks.create(NewTask("Stand-up notes", scheduledAtMs = sat * day + 450 * 60_000L))
        d.tasks.setRepeat(id, Recurrence.Weekly(1, Recurrence.WEEKDAYS))
        val t = d.tasks.get(id)!!
        assertEquals(sat + 2, t.occurrenceDay)
        assertEquals((sat + 2) * day + 450 * 60_000L, t.scheduledAtMs)
        assertTrue(id !in todayIds())

        world.clock.nowMs = (sat + 2) * day + 9 * 3_600_000L
        d.tasks.complete(id)
        assertEquals((sat + 3) * day + 450 * 60_000L, d.tasks.get(Tasks.occurrenceId(id, sat + 3))!!.scheduledAtMs)
    }

    @Test
    fun twoDevicesCompletingTheSameOccurrenceOfflineConvergeOnOneNext() {
        val mac = world.device("mac")
        val id = d.tasks.create(NewTask("Water plants"))
        d.tasks.setRepeat(id, Recurrence.Weekly(1, setOf(CivilDate.isoDayOfWeek(today()))))
        d.sync(); mac.sync()
        d.goOffline(); mac.goOffline()
        d.tasks.complete(id)
        mac.tasks.complete(id)
        d.goOnline(); mac.goOnline()
        d.syncWithRetry(); mac.syncWithRetry(); d.syncWithRetry()
        assertEquals(listOf(Tasks.occurrenceId(id, today() + 7)), openOf(id, d).map { it.id })
        assertEquals(openOf(id, d), openOf(id, mac))
        assertTrue(mac.tasks.conflicts().isEmpty())
    }

    @Test
    fun skipCancelsThisOneAndQueuesTheNext() {
        val id = d.tasks.create(NewTask("Gym"))
        d.tasks.setRepeat(id, Recurrence.Daily(2))
        d.tasks.skipOccurrence(id)
        assertEquals(Lifecycle.CANCELLED, d.tasks.get(id)!!.lifecycle)
        assertEquals(listOf(Tasks.occurrenceId(id, today() + 2)), openOf(id).map { it.id })
        assertFailsWith<ValidationException> { d.tasks.skipOccurrence(d.tasks.create(NewTask("One-off"))) }
    }

    @Test
    fun snoozeMovesOneOccurrenceAndTheSeriesKeepsItsRhythm() {
        val id = d.tasks.create(NewTask("Stretch", dueAtMs = today() * day + 20 * 3_600_000L))
        d.tasks.setRepeat(id, Recurrence.Daily())
        d.tasks.snoozeOccurrence(id)
        val t = d.tasks.get(id)!!
        assertEquals(today() + 1, t.deferredToDay)
        assertEquals(today(), t.occurrenceDay)
        assertEquals((today() + 1) * day + 20 * 3_600_000L, t.dueAtMs)
        assertTrue(id !in todayIds())

        world.clock.advance(day)
        assertTrue(id in todayIds())
        d.tasks.complete(id)
        // Done on the snoozed day: the next one is the day after, not a second one today.
        assertEquals(listOf(Tasks.occurrenceId(id, today() + 1)), openOf(id).map { it.id })
        assertEquals((today() + 1) * day + 20 * 3_600_000L, openOf(id).single().dueAtMs)
    }

    @Test
    fun snoozingAOneOffTaskHidesItUntilTomorrow() {
        val id = d.tasks.create(NewTask("Call the garage"))
        d.tasks.snoozeOccurrence(id, 2)
        assertTrue(id !in todayIds())
        assertTrue(DayPlanner.plan(d.tasks.all(), emptyList(), world.clock.nowMs, window()).placements.none { it.task.id == id })
        world.clock.advance(2 * day)
        assertTrue(id in todayIds())
    }

    @Test
    fun missedOccurrencesAreNotBackFilled() {
        val id = d.tasks.create(NewTask("Bins out"))
        d.tasks.setRepeat(id, Recurrence.Weekly(1, setOf(CivilDate.isoDayOfWeek(today()))))
        val start = today()
        world.clock.advance(17 * day) // three weeks of the old occurrence sitting open
        assertTrue(id in todayIds()) // still there, waiting
        d.tasks.complete(id)
        assertEquals(listOf(Tasks.occurrenceId(id, start + 21)), openOf(id).map { it.id })
    }

    @Test
    fun aRoutinesStepsComeBackUnticked() {
        val id = d.tasks.create(NewTask("Morning routine"))
        val a = d.tasks.addChecklistItem(id, "Make bed")
        world.clock.advance(1)
        d.tasks.addChecklistItem(id, "Stretch")
        d.tasks.setChecklistItemChecked(a, true)
        d.tasks.setRepeat(id, Recurrence.Daily())
        d.tasks.complete(id)
        val next = openOf(id).single()
        assertEquals(listOf("Make bed", "Stretch"), next.checklist.map { it.text })
        assertTrue(next.checklist.none { it.checked })
        // The finished occurrence keeps its own ticks.
        assertTrue(d.tasks.get(id)!!.checklist.first().checked)
    }

    @Test
    fun reopenThenCompleteAgainKeepsEditsToTheNextOne() {
        val id = d.tasks.create(NewTask("Journal"))
        d.tasks.setRepeat(id, Recurrence.Daily())
        d.tasks.complete(id)
        val nextId = Tasks.occurrenceId(id, today() + 1)
        d.tasks.edit(nextId, TaskEdit(title = "Journal (10 min)"))
        d.tasks.reopen(id)
        d.tasks.complete(id)
        assertEquals("Journal (10 min)", d.tasks.get(nextId)!!.title)
        assertEquals(1, openOf(id).size)
    }

    @Test
    fun stoppingTheRepeatEndsTheSeries() {
        val id = d.tasks.create(NewTask("Antibiotics course"))
        d.tasks.setRepeat(id, Recurrence.Daily())
        d.tasks.setRepeat(id, null)
        assertNull(d.tasks.get(id)!!.repeatLabel)
        d.tasks.complete(id)
        assertTrue(openOf(id).isEmpty())
    }

    @Test
    fun aRuleFromANewerVersionIsKeptButNotActedOn() {
        val id = d.tasks.create(NewTask("Future rule"))
        d.replica.commitLocal(EntityTypes.TASK, id, mapOf(TaskFields.RECURRENCE to FieldValue.Text("FREQ=DAILY;COUNT=3")))
        val t = d.tasks.get(id)!!
        assertEquals("Repeats", t.repeatLabel)
        assertNull(t.recurrence)
        d.tasks.complete(id)
        assertEquals(1, d.tasks.all().size)
    }

    @Test
    fun localTimeIsKeptInTheUsersZone() {
        // UTC+1 (e.g. BST): 07:30 local is 06:30 UTC. Tomorrow's occurrence is 07:30 local too.
        val cal = LocalCalendar.fixedOffset(3_600_000L)
        val tasks = Tasks(d.replica, { "t" + world.random.nextInt() }, { world.clock.nowMs }, cal)
        val localToday = cal.epochDayOf(world.clock.nowMs)
        val at = cal.toEpochMs(localToday, 450)
        val id = tasks.create(NewTask("Run", scheduledAtMs = at))
        tasks.setRepeat(id, Recurrence.Daily())
        tasks.complete(id)
        val next = tasks.get(Tasks.occurrenceId(id, localToday + 1))!!
        assertEquals(450, cal.minuteOfDay(next.scheduledAtMs!!))
        assertEquals(at + day, next.scheduledAtMs)
    }

    @Test
    fun repeatPickerOffersPresetsForTheTasksDayAndMarksTheCurrentOne() {
        val id = d.tasks.create(NewTask("Pay rent"))
        val choices = d.tasks.repeatChoices(id)
        assertEquals("Doesn't repeat", choices.first().label)
        assertTrue(choices.first().selected)
        assertEquals(8, choices.size)
        val monthly = choices.first { Regex("Monthly on the \\d+(st|nd|rd|th)").matches(it.label) }
        d.tasks.setRepeatRule(id, monthly.rule)
        val after = d.tasks.repeatChoices(id)
        assertEquals(listOf(monthly.label), after.filter { it.selected }.map { it.label })
        // A rule set elsewhere that isn't a preset is still listed, selected.
        d.tasks.setRepeat(id, Recurrence.Daily(3))
        assertEquals("Every 3 days", d.tasks.repeatChoices(id).single { it.selected }.label)
        assertFailsWith<ValidationException> { d.tasks.setRepeatRule(id, "FREQ=SECONDLY") }
    }

    @Test
    fun repeatMetaSaysWhenAnOccurrenceIsFromAnEarlierDay() {
        val id = d.tasks.create(NewTask("Bins out"))
        d.tasks.setRepeat(id, Recurrence.Daily())
        assertEquals("Every day", d.tasks.get(id)!!.repeatMeta(today()))
        val since = CivilDate.shortLabel(today())
        world.clock.advance(day)
        assertEquals("Every day · since $since", d.tasks.get(id)!!.repeatMeta(today()))
        assertNull(d.tasks.get(d.tasks.create(NewTask("One-off")))!!.repeatMeta(today()))
    }
}
