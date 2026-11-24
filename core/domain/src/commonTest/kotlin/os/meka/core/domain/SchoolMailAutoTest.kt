package os.meka.core.domain

import os.meka.core.sync.fv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * School emails first, slice 3a: which school finds are done for him, what each makes (its School line, tasks the day
 * before and on the day, reminders 3 days and 1 day before), and the row on What Navilife may do. Invented school and
 * children (rule 13).
 */
class SchoolMailAutoTest {
    private val cal = LocalCalendar.UTC
    /** Tue 24 Nov 2026. */
    private val today = CivilDate.toEpochDay(2026, 11, 24)
    private val now = cal.toEpochMs(today, 18 * 60)
    private val sat = today + 4

    private fun find(
        id: String, what: String, day: Long, kind: String = SchoolKind.DAY.wire, endDay: Long? = day, pence: Long? = null,
        who: String = "", state: MailFindState = MailFindState.OPEN, autoAtMs: Long? = null, startMinute: Int? = null,
        endMinute: Int? = null, place: String? = null, notes: String? = null,
    ) = MailFind(
        id = id, kind = SchoolMailRules.KIND_SCHOOL, account = "Personal", merchant = "Oakfield Primary", renewDay = day,
        pence = pence, obligationKind = ObligationKind.SUBSCRIPTION, repeat = RenewalRepeat.NONE, receivedDay = today - 1,
        subject = "Christmas fair this Saturday", state = state, decidedAtMs = 0L, what = what, autoAtMs = autoAtMs,
        endDay = endDay, schoolKind = kind, who = who, startMinute = startMinute, endMinute = endMinute, place = place, notes = notes,
    )

    private val fair = find(
        "f1", "School fair", sat, pence = 100, startMinute = 12 * 60, endMinute = 16 * 60, place = "the school hall",
        notes = "No parking at school\nWear Christmas jumpers",
    )

    @Test
    fun onlyOpenDatedFindsStillToComeAndNotInSchoolAreDoneSoonestFirst() {
        val finds = listOf(
            fair,
            find("f2", "Trip payment", today + 10, pence = 1200),
            find("f3", "School photos", today - 1), // gone
            find("f4", "PE", today + 1, kind = SchoolKind.WEEKLY.wire, endDay = today + 14), // weekly: waits for Add
            find("f5", "Send in a named coat", today, kind = SchoolMailRules.ASK, endDay = today + 7), // undated ask: waits
            find("f6", "Cake sale", today + 2, autoAtMs = now - 1), // added on its own before (and undone)
            find("f7", "Disco", today + 3, state = MailFindState.DISMISSED),
            find("f8", "INSET day", today + 6, kind = SchoolKind.OFF.wire),
        )
        assertEquals(listOf("f1", "f8", "f2"), SchoolAutoRules.due(finds, emptyList(), today, 0).map { it.id })
        assertEquals(listOf("f1"), SchoolAutoRules.due(finds, emptyList(), today, SchoolAutoRules.PER_DAY - 1).map { it.id })
        assertTrue(SchoolAutoRules.due(finds, emptyList(), today, SchoolAutoRules.PER_DAY).isEmpty())
        // He typed the fair into School himself: left alone.
        val his = SchoolItem("s1", SchoolKind.DAY, "School fair", "", sat, sat, 0, null, emptyList(), 0L)
        assertFalse(SchoolAutoRules.eligible(fair, listOf(his), today))
    }

    @Test
    fun anEventGetsItsLineATaskTheDayBeforeAndOnTheDayWithRemindersThreeDaysAndOneDayBefore() {
        val p = SchoolAutoRules.plan(fair, now, cal)
        assertTrue(p.schoolLine)
        val (prep, day) = p.tasks
        assertEquals("Get ready for School fair: £1 · No parking at school · Wear Christmas jumpers", prep.title)
        assertEquals(sat - 1, prep.day)
        assertNull(prep.minute)
        assertEquals(cal.toEpochMs(sat - 1, 9 * 60), prep.remindAtMs)
        assertEquals("School fair", day.title)
        assertEquals(sat, day.day)
        assertEquals(12 * 60, day.minute)
        assertEquals(cal.toEpochMs(sat - 3, 9 * 60), day.remindAtMs)
        assertTrue(day.notes.contains("When: 12:00–16:00"), day.notes)
        assertTrue(day.notes.contains("Where: the school hall"), day.notes)
        assertTrue(day.notes.contains("never pays, signs or replies"), day.notes)
        assertEquals(
            "From the school: School fair · Sat 28 Nov, 12:00–16:00 · in School, reminders tomorrow and Fri, tasks Fri and Sat",
            SchoolAutoRules.summary(fair, p, now, cal),
        )
        // Both devices land on the same tasks.
        assertEquals(SchoolAutoRules.taskId("f1", "day"), day.id)
    }

    @Test
    fun remindersAndTheDayBeforeThatHaveGoneAreLeftOut() {
        // Tomorrow's event, at 18:00 today: no early reminder (gone), the prep task is today with its 09:00 gone.
        val tomorrow = find("f1", "Non-uniform day", today + 1, who = "Alex")
        val p = SchoolAutoRules.plan(tomorrow, now, cal)
        assertEquals(listOf(today, today + 1), p.tasks.map { it.day })
        assertTrue(p.tasks.all { it.remindAtMs == null })
        assertEquals("Non-uniform day · Alex", p.tasks.last().title)
        assertEquals("From the school: Non-uniform day · tomorrow · in School, tasks today and tomorrow", SchoolAutoRules.summary(tomorrow, p, now, cal))
        // Today's: just the task on the day, no prep.
        assertEquals(1, SchoolAutoRules.plan(find("f2", "Cake sale", today), now, cal).tasks.size)
    }

    @Test
    fun aDeadlineIsATaskDueThatDayAndADatedAskRemindsTwoDaysBefore() {
        val pay = find("f2", "Trip payment", today + 10, pence = 1200, who = "Sam")
        val p = SchoolAutoRules.plan(pay, now, cal)
        assertTrue(p.schoolLine)
        val t = p.tasks.single()
        assertEquals("Trip payment · Sam · £12", t.title)
        assertTrue(t.due)
        assertNull(t.remindAtMs) // School's own heads-up goes 2 days before
        assertEquals("From the school: Trip payment · due Fri 4 Dec · in School, task Fri", SchoolAutoRules.summary(pay, p, now, cal))

        val ask = find("f3", "Send in a named coat", today + 3, kind = SchoolMailRules.ASK, who = "Alex")
        val a = SchoolAutoRules.plan(ask, now, cal)
        assertFalse(a.schoolLine)
        assertEquals("Send in a named coat for Alex", a.tasks.single().title)
        assertEquals(cal.toEpochMs(today + 1, 9 * 60), a.tasks.single().remindAtMs)

        val inset = SchoolAutoRules.plan(find("f4", "INSET day", today + 6, kind = SchoolKind.OFF.wire), now, cal)
        assertTrue(inset.schoolLine)
        assertTrue(inset.tasks.isEmpty())
    }

    @Test
    fun itIsOnUntilHeTurnsItOffAndItsRowSaysSo() {
        assertTrue(SchoolAutoRules.read(null))
        assertFalse(SchoolAutoRules.read(SchoolAutoRules.switchFields(false, now)))
        assertTrue(SchoolAutoRules.read(mapOf(SchoolAutoFields.ON to true.fv())))
        val on = SchoolAutoRules.row(on = true, paused = false)
        assertTrue(on.onItsOwn)
        assertEquals(SchoolAutoRules.TURN_OFF, on.action)
        assertEquals(AutonomyRules.PAUSED_LINE, SchoolAutoRules.row(on = true, paused = true).line)
        assertEquals(SchoolAutoRules.OFF_LINE, SchoolAutoRules.row(on = false, paused = false).line)
        val v = AutonomyRules.view(
            emptyMap(), AutonomySetting(false, null), cal, now, MailAutopilotSetting(on = false, changedAtMs = null),
            mailFinds = false, direct = DirectAddSetting(askFirst = true), school = true,
        )
        assertEquals(SchoolAutoRules.ENTITY_ID, v.sections.single { it.label == AutonomyRules.MAIL_LABEL }.rows.last().key)
        assertEquals("Navilife does 1 thing on its own · each with Undo and in Activity", v.summary)
    }
}

/** The whole path from a long email's words to what is made (the facade test sends the same email). */
class SchoolMailAutoFromEmailTest {
    @Test
    fun aFairEmailBecomesItsLineTasksAndReminders() {
        val cal = LocalCalendar.UTC
        val received = CivilDate.toEpochDay(2026, 10, 16)
        val read = SchoolBodyRules.read("Christmas fair", SchoolMailAutoFixture.BODY, received)
        val b = read.finds.single()
        assertEquals("School fair", b.find.title)
        assertEquals(CivilDate.toEpochDay(2026, 10, 24), b.find.startDay)
        assertEquals(12 * 60, b.startMinute)
        assertEquals(16 * 60, b.endMinute)
        val f = MailFind(
            id = "f1", kind = SchoolMailRules.KIND_SCHOOL, account = "Personal", merchant = "Oakfield Primary", renewDay = b.find.startDay,
            pence = b.find.pence, obligationKind = ObligationKind.SUBSCRIPTION, repeat = RenewalRepeat.NONE, receivedDay = received,
            subject = "Christmas fair", state = MailFindState.OPEN, decidedAtMs = 0L, what = b.find.title, endDay = b.find.endDay,
            schoolKind = b.find.kind.wire, who = b.find.who, startMinute = b.startMinute, endMinute = b.endMinute, place = b.place,
            notes = b.notes.joinToString("\n").ifEmpty { null },
        )
        val now = cal.toEpochMs(received + 1, 9 * 60 + 30)
        val p = SchoolAutoRules.plan(f, now, cal)
        assertEquals(listOf("prep", "day").map { SchoolAutoRules.taskId("f1", it) }, p.tasks.map { it.id })
        assertTrue(p.tasks[0].title.startsWith("Get ready for School fair"), p.tasks[0].title)
        assertTrue(p.tasks[0].title.contains("No parking at school"), p.tasks[0].title)
        assertEquals(
            "From the school: School fair · Sat 24 Oct, 12:00–16:00 · in School, reminders Wed and Fri, tasks Fri and Sat",
            SchoolAutoRules.summary(f, p, now, cal),
        )
    }
}

object SchoolMailAutoFixture {
    const val BODY = "Dear parents,\n\nOur Christmas fair is on Saturday 24 October, 12pm-4pm in the school hall. " +
        "Entry is £1 per adult. Please note there is no parking at school.\n\nThank you"
}
