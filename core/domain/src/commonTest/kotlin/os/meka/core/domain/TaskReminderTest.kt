package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Remind me in the task detail (Fold review 2026-10-08, item 8). */
class TaskReminderTest {
    private val world = SyncWorld()
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val day = CivilDate.DAY_MS
    private val min = 60_000L
    private val cal = LocalCalendar.UTC

    private fun today() = world.clock.nowMs.floorDiv(day)
    private fun at(minute: Int) { world.clock.nowMs = today() * day + minute * min }
    private fun ms(d: Long, minute: Int) = d * day + minute * min
    private fun view(id: String, dev: Device = fold) = TaskReminderRules.view(dev.tasks.get(id)!!, world.clock.nowMs, cal)
    private fun notices(dev: Device = fold) = TaskReminderRules.notices(dev.tasks.all(), world.clock.nowMs, cal)

    @Test
    fun aTaskWithATimeOffersAtTheTimeAndBefore() {
        at(9 * 60 + 52)
        val id = fold.tasks.create(NewTask("Call the bank"))
        fold.tasks.setWhen(id, today(), 14 * 60 + 30)
        val v = view(id)
        assertEquals("Off", v.label)
        assertFalse(v.isSet)
        assertEquals(-1L, v.atMsOrNone)
        assertEquals(listOf("At 14:30", "15 min before", "1 h before"), v.choices.map { it.label })
        assertEquals(listOf(ms(today(), 870), ms(today(), 855), ms(today(), 810)), v.choices.map { it.atMs })

        fold.tasks.setReminder(id, ms(today(), 855))
        val set = view(id)
        assertEquals("Today · 14:15", set.label)
        assertTrue(set.isSet)
        assertEquals(listOf(false, true, false), set.choices.map { it.selected })
    }

    @Test
    fun aTaskWithNoTimeOffersInAnHourAndTimesOfItsDay() {
        at(9 * 60 + 52)
        val id = fold.tasks.create(NewTask("Pay council tax"))
        // 09:52 → the next quarter hour is 10:00, an hour on is 11:00; 09:00 has gone.
        assertEquals(listOf("In 1 h", "13:00", "18:00"), view(id).choices.map { it.label })
        assertEquals(ms(today(), 11 * 60), view(id).choices.first().atMs)

        fold.tasks.setWhen(id, today() + 1, null)
        assertEquals(listOf("09:00", "13:00", "18:00"), view(id).choices.map { it.label })
        assertEquals(ms(today() + 1, 9 * 60), view(id).choices.first().atMs)

        // Late in the evening only "In 1 h" is left today.
        fold.tasks.setWhen(id, today(), null)
        at(19 * 60)
        assertEquals(listOf("In 1 h"), view(id).choices.map { it.label })
    }

    @Test
    fun aReminderSetElsewhereIsListedSelected() {
        at(8 * 60)
        val id = fold.tasks.create(NewTask("Renew passport"))
        fold.tasks.setReminder(id, ms(today(), 10 * 60 + 20))
        val v = view(id)
        assertEquals("10:20", v.choices.last().label)
        assertTrue(v.choices.last().selected)
        assertEquals(1, v.choices.count { it.selected })
        // One on another day says which.
        fold.tasks.setReminder(id, ms(today() + 3, 7 * 60))
        assertEquals(TaskWhenRules.label(today() + 3, 7 * 60, today()), view(id).choices.last().label)
    }

    @Test
    fun itPostsAsAClockHeadsUpAndQuietHoursApply() {
        at(13 * 60) // after the midday digest, so the reminder is the next thing to wake for
        val id = fold.tasks.create(NewTask("Book dentist"))
        fold.tasks.setWhen(id, today(), 14 * 60 + 30)
        fold.tasks.setReminder(id, ms(today(), 14 * 60 + 15))
        val n = notices().single()
        assertEquals(NoticeSource.TASK_REMINDER, n.source)
        assertEquals(NoticePrecision.CLOCK, n.precision)
        assertEquals("Book dentist", n.title)
        assertEquals("Planned for 14:30", n.text)
        assertEquals("task:$id:remind:${ms(today(), 855)}", n.key)

        // Not yet: the governor wakes at the reminder, exactly.
        val before = Governor.evaluate(notices(), NotificationSettings.DEFAULT, DeviceAlerts.ALL, GovernorState(), world.clock.nowMs, cal)
        assertTrue(before.post.isEmpty())
        assertEquals(ms(today(), 855), before.nextWakeMs)
        assertEquals(NoticePrecision.CLOCK, before.nextWakePrecision)
        // At the time it posts, once.
        at(14 * 60 + 15)
        val r = Governor.evaluate(notices(), NotificationSettings.DEFAULT, DeviceAlerts.ALL, before.state, world.clock.nowMs, cal)
        assertEquals(listOf(n.key), r.post.map { it.key })
        assertTrue(Governor.evaluate(notices(), NotificationSettings.DEFAULT, DeviceAlerts.ALL, r.state, world.clock.nowMs, cal).post.isEmpty())

        // A reminder set for 23:00 falls in quiet hours: it rides in the next digest instead of waking anyone.
        val late = fold.tasks.create(NewTask("Bins out"))
        fold.tasks.setReminder(late, ms(today(), 23 * 60))
        at(23 * 60)
        val q = Governor.evaluate(notices(), NotificationSettings.DEFAULT, DeviceAlerts.ALL, r.state, world.clock.nowMs, cal)
        assertTrue(q.post.none { it.key.startsWith("task:$late") })
    }

    @Test
    fun doneSomedayDeleteAndStaleSilenceIt() {
        at(9 * 60)
        val a = fold.tasks.create(NewTask("A"))
        val b = fold.tasks.create(NewTask("B"))
        val c = fold.tasks.create(NewTask("C"))
        val d = fold.tasks.create(NewTask("D", dueAtMs = ms(today() + 2, 17 * 60)))
        listOf(a, b, c, d).forEach { fold.tasks.setReminder(it, ms(today(), 10 * 60)) }
        fold.tasks.complete(a)
        fold.tasks.moveToSomeday(b)
        fold.tasks.delete(c)
        assertEquals(listOf(d), notices().map { it.key.split(':')[1] })
        assertEquals("Due ${CivilDate.shortLabel(today() + 2)}", notices().single().text)
        // Missed by more than two hours (phone off): dropped, never sent late.
        at(12 * 60)
        assertTrue(notices().isEmpty())
        assertEquals("Off", view(d).label)
    }

    @Test
    fun itMovesWithTheTask() {
        at(8 * 60)
        val id = fold.tasks.create(NewTask("Standup notes"))
        fold.tasks.setWhen(id, today(), 14 * 60 + 30)
        fold.tasks.setReminder(id, ms(today(), 14 * 60 + 15))
        // Later the same day: still 15 min before.
        fold.tasks.setWhen(id, today(), 16 * 60)
        assertEquals(ms(today(), 15 * 60 + 45), fold.tasks.get(id)!!.remindAtMs)
        // Another day, same time: same time of day, that day.
        fold.tasks.setWhen(id, today() + 2, 16 * 60)
        assertEquals(ms(today() + 2, 15 * 60 + 45), fold.tasks.get(id)!!.remindAtMs)
        // No time: the reminder stays where it was.
        fold.tasks.setWhen(id, today() + 2, null)
        assertEquals(ms(today() + 2, 15 * 60 + 45), fold.tasks.get(id)!!.remindAtMs)
        // Back to today with no time: same time of day, today.
        fold.tasks.setWhen(id, today(), null)
        assertEquals(ms(today(), 15 * 60 + 45), fold.tasks.get(id)!!.remindAtMs)
        // Tomorrow (snooze) moves it a day, and undo puts it back.
        val undo = fold.tasks.decide(id, DecisionEffect.SNOOZE_TASK)!!
        assertEquals(ms(today() + 1, 15 * 60 + 45), fold.tasks.get(id)!!.remindAtMs)
        assertTrue(fold.tasks.undoDecision(undo))
        assertEquals(ms(today(), 15 * 60 + 45), fold.tasks.get(id)!!.remindAtMs)
    }

    @Test
    fun aRepeatingTasksNextOccurrenceRemindsOnItsDay() {
        at(7 * 60)
        val id = fold.tasks.create(NewTask("Vitamins"))
        fold.tasks.setRepeat(id, Recurrence.Daily())
        fold.tasks.setReminder(id, ms(today(), 8 * 60))
        fold.tasks.complete(id)
        val next = fold.tasks.get(Tasks.occurrenceId(id, today() + 1))!!
        assertEquals(ms(today() + 1, 8 * 60), next.remindAtMs)
        assertEquals(listOf("task:${next.id}:remind:${ms(today() + 1, 8 * 60)}"), notices().map { it.key })
    }

    @Test
    fun theRulesRefuseThePastAndOffClearsIt() {
        at(9 * 60)
        val id = fold.tasks.create(NewTask("Something"))
        assertFailsWith<ValidationException> { fold.tasks.setReminder(id, ms(today(), 8 * 60)) }
        assertFailsWith<ValidationException> { fold.tasks.setReminder(id, world.clock.nowMs) }
        assertFailsWith<ValidationException> { fold.tasks.setReminder(id, ms(today() + TaskWhenRules.MAX_DAYS_AHEAD + 2, 0)) }
        assertFailsWith<ValidationException> { fold.tasks.setReminder("nope", ms(today(), 10 * 60)) }
        fold.tasks.setReminder(id, ms(today(), 10 * 60))
        fold.tasks.setReminder(id, null)
        assertNull(fold.tasks.get(id)!!.remindAtMs)
        assertTrue(notices().isEmpty())
    }

    @Test
    fun aReminderSyncsAndPostsOnBothDevicesUnderOneKey() {
        at(9 * 60)
        val id = fold.tasks.create(NewTask("Send invoice"))
        fold.tasks.setReminder(id, ms(today(), 11 * 60))
        fold.sync(); mac.sync()
        assertEquals("Today · 11:00", view(id, mac).label)
        assertEquals(notices().map { it.key }, notices(mac).map { it.key })
        // Off on the Mac turns it off on the Fold.
        mac.tasks.setReminder(id, null)
        mac.sync(); fold.sync()
        assertTrue(notices().isEmpty())
    }
}
