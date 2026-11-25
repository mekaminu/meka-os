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

    // ---- Slice 3b: into his real calendar ----

    private fun ev(id: String, title: String, start: Long, end: Long, allDay: Boolean = false) =
        CalendarEvent(id, title, start, end, allDay, null, "google", "alex@example.com", "Personal")

    @Test
    fun anEventBecomesACalendarAddWithItsTimePlaceAndNotes() {
        assertTrue(SchoolCalendarRules.wanted(fair, SchoolAutoRules.plan(fair, now, cal)))
        val d = SchoolCalendarRules.draft(fair, cal)
        assertEquals("School fair", d.title)
        assertEquals(cal.toEpochMs(sat, 12 * 60), d.startAtMs)
        assertEquals(cal.toEpochMs(sat, 16 * 60), d.endAtMs)
        assertFalse(d.allDay)
        assertEquals("the school hall", d.location)
        assertEquals(
            "Cost: £1\nBring / do: No parking at school · Wear Christmas jumpers\n" +
                "From Oakfield Primary's email “Christmas fair this Saturday”.\nAdded by Navilife · Undo in Navilife's Activity.",
            d.notes,
        )
        // No end: an hour. No time at all: the whole day (UTC midnights, as the calendars mirror them).
        val start = SchoolCalendarRules.draft(fair.copy(endMinute = null), cal)
        assertEquals(start.startAtMs + 60 * 60_000L, start.endAtMs)
        val whole = SchoolCalendarRules.draft(fair.copy(startMinute = null, endMinute = null), cal)
        assertTrue(whole.allDay)
        assertEquals(sat * 86_400_000L, whole.startAtMs)
        assertEquals((sat + 1) * 86_400_000L, whole.endAtMs)
        // Fixed ids, the same on both devices, different for the add and its take-back.
        assertEquals(SchoolCalendarRules.editId("f1"), SchoolCalendarRules.editId("f1"))
        assertTrue(SchoolCalendarRules.editId("f1") != SchoolCalendarRules.deleteId("f1"))
    }

    @Test
    fun deadlinesAsksAndDaysOffStayOutOfHisCalendar() {
        val pay = find("f2", "Trip payment", today + 10, pence = 1200)
        assertFalse(SchoolCalendarRules.wanted(pay, SchoolAutoRules.plan(pay, now, cal)))
        val off = find("f8", "INSET day", today + 6, kind = SchoolKind.OFF.wire)
        assertFalse(SchoolCalendarRules.wanted(off, SchoolAutoRules.plan(off, now, cal)))
        val ask = find("f9", "Send in a named coat", today + 3, kind = SchoolMailRules.ASK)
        assertFalse(SchoolCalendarRules.wanted(ask, SchoolAutoRules.plan(ask, now, cal)))
    }

    @Test
    fun aDayThatAlreadyHoldsItGetsNothingAdded() {
        val invite = ev("g9", "Oakfield Christmas Fair", cal.toEpochMs(sat, 12 * 60), cal.toEpochMs(sat, 16 * 60))
        val other = ev("g8", "Football", cal.toEpochMs(sat, 9 * 60), cal.toEpochMs(sat, 10 * 60))
        // "School fair" isn't inside "Oakfield Christmas Fair"; the same words are.
        assertFalse(SchoolCalendarRules.alreadyThere(fair, listOf(invite, other), cal))
        assertTrue(SchoolCalendarRules.alreadyThere(fair, listOf(invite.copy(title = "School Fair!")), cal))
        assertFalse(SchoolCalendarRules.alreadyThere(fair, listOf(invite.copy(title = "School fair", startAtMs = cal.toEpochMs(sat + 1, 600))), cal))
        assertFalse(SchoolCalendarRules.alreadyThere(fair, listOf(invite.copy(id = PendingEditRules.PROVISIONAL_PREFIX + "x", title = "School fair")), cal))
        assertTrue(SchoolCalendarRules.alreadyThere(fair, listOf(ev("g7", "School fair", sat * 86_400_000L, (sat + 1) * 86_400_000L, allDay = true)), cal))
    }

    private fun edit(status: EventEditStatus? = null, createdAtMs: Long = now, undone: Boolean = false): EventEdit {
        val d = SchoolCalendarRules.draft(fair, cal)
        return EventEdit(
            SchoolCalendarRules.editId("f1"), EventEditKind.ADD, "google", "alex@example.com", null, emptySet(), d, null, false,
            createdAtMs, createdAtMs + CalendarEditRules.UNDO_MS, undone, status, null, null, null, null,
        )
    }

    @Test
    fun undoTakesTheEventBackWhereverItIs() {
        val d = SchoolCalendarRules.draft(fair, cal)
        val inGoogle = ev("g1", d.title, d.startAtMs, d.endAtMs)
        assertEquals(SchoolCalendarRules.TakeBack.UndoAdd, SchoolCalendarRules.takeBack(edit(), false, emptyList(), now + 1_000))
        assertEquals(SchoolCalendarRules.TakeBack.Wait, SchoolCalendarRules.takeBack(edit(), false, emptyList(), now + 60_000))
        assertEquals(SchoolCalendarRules.TakeBack.Delete(inGoogle), SchoolCalendarRules.takeBack(edit(EventEditStatus.DONE), false, listOf(inGoogle), now + 60_000))
        // Already deleted, undone, refused, or long gone: nothing more.
        assertEquals(SchoolCalendarRules.TakeBack.Nothing, SchoolCalendarRules.takeBack(edit(EventEditStatus.DONE), true, listOf(inGoogle), now + 60_000))
        assertEquals(SchoolCalendarRules.TakeBack.Nothing, SchoolCalendarRules.takeBack(edit(undone = true), false, emptyList(), now + 1_000))
        assertEquals(SchoolCalendarRules.TakeBack.Nothing, SchoolCalendarRules.takeBack(edit(EventEditStatus.REFUSED), false, listOf(inGoogle), now + 60_000))
        assertEquals(SchoolCalendarRules.TakeBack.Nothing, SchoolCalendarRules.takeBack(null, false, listOf(inGoogle), now))
        assertEquals(SchoolCalendarRules.TakeBack.Nothing, SchoolCalendarRules.takeBack(edit(EventEditStatus.DONE), false, listOf(inGoogle), now + SchoolCalendarRules.TAKE_BACK_MS + 1))
    }

    @Test
    fun theEventIsShownOnceAsItsTaskAndActivitySaysWhereItWent() {
        val d = SchoolCalendarRules.draft(fair, cal)
        val inGoogle = ev("g1", d.title, d.startAtMs, d.endAtMs)
        val other = ev("g2", "Football", d.startAtMs, d.endAtMs)
        val e = edit(EventEditStatus.DONE)
        assertEquals(listOf(other), SchoolCalendarRules.withoutSchoolEvents(listOf(inGoogle, other), listOf(e), setOf("f1")))
        // The task on the day was deleted: the event shows again.
        assertEquals(listOf(inGoogle, other), SchoolCalendarRules.withoutSchoolEvents(listOf(inGoogle, other), listOf(e), emptySet()))
        val pending = inGoogle.copy(id = PendingEditRules.PROVISIONAL_PREFIX + e.id)
        assertTrue(SchoolCalendarRules.withoutSchoolEvents(listOf(pending), listOf(edit()), setOf("f1")).isEmpty())

        val line = "From the school: School fair · Sat 28 Nov, 12:00–16:00 · in School, reminders Wed and Fri, tasks Fri and Sat"
        assertEquals(
            "From the school: School fair · Sat 28 Nov, 12:00–16:00 · in Google and School, reminders Wed and Fri, tasks Fri and Sat",
            SchoolCalendarRules.withCalendar(line, "google"),
        )
        assertEquals(SchoolCalendarRules.withCalendar(line, "google"), SchoolCalendarRules.withCalendar(SchoolCalendarRules.withCalendar(line, "google"), "google"))
    }
    private fun entry(f: MailFind, atMs: Long, undone: Long? = null) = ActivityItem(
        id = SchoolAutoRules.activityId(f.id), atMs = atMs, kind = ActivityKind.CHANGED,
        summary = "From the school: School fair · Sat 28 Nov, 12:00–16:00 · in School, reminders Wed and Fri, tasks Fri and Sat",
        detail = null, why = SchoolAutoRules.WHY, source = SchoolAutoRules.SOURCE, level = null, changes = emptyList(),
        undoneAtMs = undone, undoNote = null,
    )

    @Test
    fun aSchoolDateDoneForHimIsADoneForYouRowWithUndoChangeAndClear() {
        // Slice 3c.
        val added = fair.copy(autoAtMs = now - 60_000, state = MailFindState.ADDED)
        val titles = mapOf(SchoolAutoRules.taskId("f1", "day") to "School fair", SchoolAutoRules.taskId("f1", "prep") to "Get ready")
        val rows = SchoolDoneRules.rows(listOf(added), mapOf(SchoolAutoRules.activityId("f1") to entry(added, now - 60_000)), { null }, titles::get, now, cal)
        val r = rows.single()
        assertEquals("school:f1", r.id)
        assertEquals("From the school · 17:59", r.from)
        assertEquals("School fair · Sat 28 Nov, 12:00–16:00 · in School, reminders Wed and Fri, tasks Fri and Sat", r.line)
        assertEquals(listOf(SchoolAutoRules.taskId("f1", "day"), SchoolAutoRules.taskId("f1", "prep")), r.taskIds)
        assertEquals("School fair", r.changeTitle)
        assertEquals("Undo", r.undoLabel)
        assertEquals("Change", r.changeLabel)
        assertEquals("f1", SchoolDoneRules.findIdOf(r.id))
        assertNull(SchoolDoneRules.findIdOf("msg#0"))
        // One heads-up, from the school.
        val n = SchoolDoneRules.notices(rows, listOf(added), now, cal).single()
        assertEquals("Done for you · from the school", n.title)
        assertEquals(r.line, n.text)
        assertEquals(NoticeTarget.NEEDS_YOU, n.target)
    }

    @Test
    fun undoneClearedOldOrCatchUpRowsAreNotShownOrNotSaid() {
        val added = fair.copy(autoAtMs = now - 60_000, state = MailFindState.ADDED)
        val key = SchoolAutoRules.activityId("f1")
        fun rows(e: ActivityItem, cleared: Long? = null) =
            SchoolDoneRules.rows(listOf(added), mapOf(key to e), { cleared }, { "x" }, now, cal)
        assertTrue(rows(entry(added, now - 60_000, undone = now)).isEmpty())
        assertTrue(rows(entry(added, now - 60_000), cleared = now).isEmpty())
        assertTrue(rows(entry(added, now - HandleForMeRules.SHOWN_MS - 1)).isEmpty())
        // A find never added on its own (his Add) has no row.
        assertTrue(SchoolDoneRules.rows(listOf(fair), mapOf(key to entry(fair, now)), { null }, { "x" }, now, cal).isEmpty())
        // The 30-day catch-up: an older email's date shows its row but says nothing.
        val old = added.copy(receivedDay = today - 12)
        val shown = SchoolDoneRules.rows(listOf(old), mapOf(key to entry(old, now - 60_000)), { null }, { "x" }, now, cal)
        assertEquals(1, shown.size)
        assertTrue(SchoolDoneRules.notices(shown, listOf(old), now, cal).isEmpty())
    }

    @Test
    fun theCatchUpIsSaidOnceTogetherSoonestFirst() {
        // Slice 5: dates from older emails added today are one summary; a fresh email's, a change, or yesterday's aren't.
        fun added(id: String, what: String, day: Long, received: Long, change: String? = null) =
            find(id, what, day).copy(autoAtMs = now - 60_000, state = MailFindState.ADDED, receivedDay = received, schoolChange = change)
        fun row(f: MailFind, line: String, atMs: Long = now - 60_000) = DoneForYou(
            id = SchoolDoneRules.rowId(f.id), from = "From the school · 17:59", line = line, spoken = line, atMs = atMs,
            taskIds = emptyList(), homeDay = null, person = SchoolDoneRules.PERSON,
        )
        val trip = added("a", "Year 5 trip", today + 10, today - 12)
        val fairOld = added("b", "School fair", sat, today - 20)
        val nativity = added("c", "Nativity", today + 15, today - 8)
        val disco = added("d", "Disco", today + 20, today - 6)
        val fresh = added("e", "Choir", today + 3, today)
        val moved = added("f", "Cake sale", today + 5, today - 9, change = "moved")
        val yesterdays = added("g", "Bingo", today + 7, today - 9)
        val finds = listOf(trip, fairOld, nativity, disco, fresh, moved, yesterdays)
        val rows = listOf(
            row(trip, "Year 5 trip · Fri 4 Dec, 09:00 · in School"),
            row(fairOld, "School fair · Sat 28 Nov, 12:00–16:00 · in School, reminders Wed and Fri"),
            row(nativity, "Nativity · Wed 9 Dec · in School"),
            row(disco, "Disco · Mon 14 Dec, 17:00–19:00 · in School"),
            row(fresh, "Choir · Fri 27 Nov · in School"),
            row(moved, "Cake sale moved to Sun 29 Nov · in School"),
            row(yesterdays, "Bingo · Tue 1 Dec · in School", atMs = now - 20 * 60 * 60_000L),
        )
        assertEquals(listOf("b", "a", "c", "d"), SchoolDoneRules.catchUp(rows, finds, now, cal).mapNotNull { SchoolDoneRules.findIdOf(it.id) })
        val n = SchoolDoneRules.catchUpNotices(rows, finds, now, cal).single()
        assertEquals("schoolcatchup:$today", n.key)
        assertEquals(SchoolDoneRules.CATCH_UP_TITLE, n.title)
        assertEquals(
            "4 still to come added from the last 30 days: School fair (Sat 28 Nov), Year 5 trip (Fri 4 Dec), Nativity (Wed 9 Dec) and 1 more · each in Needs you with Undo",
            n.text,
        )
        assertEquals(NoticeTarget.NEEDS_YOU, n.target)
        assertEquals(
            "2 still to come added from the last 30 days: Year 5 trip (Fri 4 Dec) and Nativity (Wed 9 Dec) · each in Needs you with Undo",
            SchoolDoneRules.catchUpLine(listOf(rows[0], rows[2])),
        )
        assertEquals("1 still to come added from the last 30 days: Nativity (Wed 9 Dec) · each in Needs you with Undo", SchoolDoneRules.catchUpLine(listOf(rows[2])))
        // Only fresh mail, or nothing: no summary.
        assertTrue(SchoolDoneRules.catchUpNotices(listOf(rows[4]), finds, now, cal).isEmpty())
        assertNull(SchoolDoneRules.catchUpLine(emptyList()))
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
