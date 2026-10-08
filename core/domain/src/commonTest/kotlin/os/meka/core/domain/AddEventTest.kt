package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edit your calendars, slice 2b: the Add event sheet and the lines while an edit is sent. */
class AddEventTest {
    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val thu = CivilDate.toEpochDay(2026, 10, 8)
    /** London in October: BST, an hour ahead of UTC. */
    private val bst = LocalCalendar.fixedOffset(hour)
    private val google = EditAccount("google", "meka@gmail.com")
    private val outlook = EditAccount("microsoft", "meka@outlook.com")
    private val accounts = listOf(google, outlook)

    /** Local 10:07 on Thursday 8 October. */
    private val now = bst.toEpochMs(thu, 10 * 60 + 7)

    private fun form(day: Long? = null, last: String? = null) = AddEventRules.start(thu, 10 * 60 + 7, day, accounts, last)

    @Test
    fun aFreshFormStartsAtTheNextQuarterHourForAnHourOnTheFirstAccount() {
        val f = form()
        assertEquals(thu, f.day)
        assertEquals(10 * 60 + 30, f.minute) // 10:07 → at least 15 min away → 10:30
        assertEquals(60, f.lengthMin)
        assertEquals(google.key, f.accountKey)
        assertEquals("google|meka@gmail.com", google.key)
        assertEquals("Google · meka@gmail.com", google.label)
        val v = AddEventRules.view(f, accounts, now, bst)
        assertEquals("Today · 10:30–11:30", v.summary)
        assertEquals("10:30", v.startLabel)
        assertEquals("11:30", v.endLabel)
        assertEquals(listOf("Today", "Tomorrow"), v.chips.map { it.label })
        assertTrue(v.chips.first().selected)
        assertEquals(listOf("30 min", "1 h", "1 h 30", "2 h"), v.lengths.map { it.label })
        assertEquals(listOf(false, true, false, false), v.lengths.map { it.selected })
        assertEquals("Add to Google", v.addLabel)
        // No title yet: Add waits, but nothing is said.
        assertFalse(v.canAdd)
        assertNull(v.problem)
        assertTrue(AddEventRules.view(f.withTitle("Dentist"), accounts, now, bst).canAdd)
    }

    @Test
    fun anotherDayStartsAtNineAndAnEarlierDayIsToday() {
        val fri = form(day = thu + 1)
        assertEquals(9 * 60, fri.minute)
        assertEquals("Tomorrow · 09:00–10:00", AddEventRules.view(fri, accounts, now, bst).summary)
        assertEquals(thu, form(day = thu - 3).day)
        val later = form().withDay(thu + 7)
        val v = AddEventRules.view(later, accounts, now, bst)
        assertEquals(listOf("Today", "Tomorrow", "Thu 15 Oct"), v.chips.map { it.label })
        assertEquals(listOf(false, false, true), v.chips.map { it.selected })
        assertEquals(thu, form().withDay(thu - 1).day)
        assertEquals(thu + TaskWhenRules.MAX_DAYS_AHEAD, form().withDay(thu + 5_000).day)
    }

    @Test
    fun theLastAccountAddedToIsRememberedWhileItCanStillEdit() {
        assertEquals(outlook.key, form(last = outlook.key).accountKey)
        assertEquals(google.key, form(last = "google|someone@else.com").accountKey)
        val edits = listOf(
            edit("a", EventEditKind.ADD, "microsoft", "meka@outlook.com", created = 1),
            edit("b", EventEditKind.ADD, "google", "meka@gmail.com", created = 2, undone = true),
            edit("c", EventEditKind.CHANGE, "google", "meka@gmail.com", created = 3),
        )
        assertEquals(outlook.key, AddEventRules.lastUsedKey(edits))
        assertNull(AddEventRules.lastUsedKey(emptyList()))
        val o = AddEventRules.view(form(last = outlook.key).withTitle("Lunch"), accounts, now, bst)
        assertEquals("Add to Outlook", o.addLabel)
        assertEquals(listOf(false, true), o.accounts.map { it.selected })
        assertEquals("Outlook keeps these notes; MEKA can't change them later", o.notesNote)
        assertNull(AddEventRules.view(form(), accounts, now, bst).notesNote)
    }

    @Test
    fun withoutAnAccountThatCanEditItSaysWhereToAllowIt() {
        val none = AddEventRules.start(thu, 600, null, emptyList(), null).withTitle("Dentist")
        val v = AddEventRules.view(none, emptyList(), now, bst)
        assertNull(none.accountKey)
        assertEquals("Allow editing on an account in Calendars first", v.problem)
        assertFalse(v.canAdd)
        assertEquals("Add to your calendar", v.addLabel)
        // An account that stopped editing while the sheet was open.
        val gone = AddEventRules.view(form().withTitle("Dentist"), listOf(outlook), now, bst)
        assertEquals("Choose a calendar", gone.problem)
        assertFalse(gone.canAdd)
    }

    @Test
    fun aTimedDraftIsAtTheLocalTimeAndAnAllDayOneRunsWholeUtcDays() {
        val f = form().withTitle("  Dentist ").withLocation(" High St Surgery ").withNotes("  ").stepTime(2).withLength(30)
        assertEquals(11 * 60, f.minute)
        val d = AddEventRules.draft(f, bst)
        assertEquals(EventDraft("Dentist", thu * day + 10 * hour, thu * day + 10 * hour + 30 * 60_000L, false, "High St Surgery", null), d)
        assertNull(CalendarEditRules.problem(d, now))

        val allDay = f.withAllDay(true).withDay(thu + 2)
        assertTrue(allDay.isAllDay)
        assertEquals(-1, allDay.minuteOrNone)
        val a = AddEventRules.draft(allDay, bst)
        assertEquals((thu + 2) * day, a.startAtMs)
        assertEquals((thu + 3) * day, a.endAtMs)
        assertTrue(a.allDay)
        assertNull(CalendarEditRules.problem(a, now))
        val v = AddEventRules.view(allDay, accounts, now, bst)
        assertEquals("Sat 10 Oct · all day", v.summary)
        assertNull(v.startLabel)
        assertTrue(v.lengths.isEmpty())
        // Off again: the day's suggested time (another day → 09:00); stepping does nothing while all day.
        assertEquals(9 * 60, allDay.withAllDay(false).minute)
        assertEquals(allDay, allDay.stepTime(1))
        assertEquals(11 * 60, f.withAllDay(false).minute)
    }

    @Test
    fun timeStepsStayInTheDayAndAnEventMayRunPastMidnight() {
        val late = form().stepTime(60) // capped at 23:45
        assertEquals(23 * 60 + 45, late.minute)
        val v = AddEventRules.view(late, accounts, now, bst)
        assertEquals("00:45 next day", v.endLabel)
        assertEquals("Today · 23:45–00:45", v.summary)
        assertEquals(0, form().stepTime(-100).minute)
        val odd = form().withLength(45)
        assertEquals(listOf("30 min", "45 min", "1 h", "1 h 30", "2 h"), AddEventRules.view(odd, accounts, now, bst).lengths.map { it.label })
        assertEquals(15, form().withLength(1).lengthMin)
        assertEquals("2 h 15", AddEventRules.lengthLabel(135))
    }

    @Test
    fun aTimeInThePastIsStillAllowedButNotLongerThanTheRules() {
        val earlier = form().stepTime(-8) // 08:30 today
        assertTrue(AddEventRules.view(earlier.withTitle("Standup"), accounts, now, bst).canAdd)
        val v = AddEventRules.view(form().withTitle("x".repeat(600)), accounts, now, bst)
        assertTrue(v.canAdd)
        assertEquals(CalendarEditRules.MAX_TITLE, form().withTitle("x".repeat(600)).title.length)
    }

    // ---- The lines while an edit is sent ----

    private fun edit(
        id: String, kind: EventEditKind, provider: String = "google", account: String = "meka@gmail.com", created: Long = now,
        undone: Boolean = false, status: EventEditStatus? = null, statusAt: Long? = null, detail: String? = null,
    ) = EventEdit(
        id, kind, provider, account, if (kind == EventEditKind.ADD) null else "ev1", emptySet(),
        EventDraft("Dentist", now + hour, now + 2 * hour, false), EventDraft("Dentist", now + hour, now + 2 * hour, false),
        false, created, created + CalendarEditRules.UNDO_MS, undone, status, statusAt, detail, null, null,
    )

    @Test
    fun linesShowWhatIsOnItsWayWhatNeedsMekaAndWhatJustLanded() {
        val t = now + 10 * 60_000L
        val edits = listOf(
            edit("sending", EventEditKind.ADD, created = t - 20_000),
            edit("waiting", EventEditKind.ADD, created = t - 1_000),
            edit("undone", EventEditKind.ADD, created = t - 2_000, undone = true),
            edit("done", EventEditKind.ADD, created = t - 90_000, status = EventEditStatus.DONE, statusAt = t - 60_000),
            edit("old", EventEditKind.ADD, created = t - 600_000, status = EventEditStatus.DONE, statusAt = t - 500_000),
            edit("refused", EventEditKind.DELETE, created = t - 3 * hour, status = EventEditStatus.REFUSED, statusAt = t - 3 * hour,
                detail = "MEKA only changes your own events"),
            edit("ancient", EventEditKind.ADD, created = t - 2 * day, status = EventEditStatus.FAILED, statusAt = t - 2 * day),
        )
        val all = EditLineRules.lines(edits, t)
        // Newest first, at most three.
        assertEquals(listOf("waiting", "sending", "done"), all.map { it.id })
        assertEquals("Adding “Dentist” to Google", all[0].text)
        assertEquals(EventEditState.WAITING, all[0].state)
        assertEquals(EventEditState.SENDING, all[1].state)
        assertEquals("Added “Dentist” to Google", all[2].text)
        assertFalse(all.any { it.needsMeka })

        val later = EditLineRules.lines(edits.filter { it.id in setOf("refused", "done", "old", "ancient") }, t)
        assertEquals(listOf("done", "refused"), later.map { it.id })
        val refused = later[1]
        assertTrue(refused.needsMeka)
        assertEquals("MEKA only changes your own events", refused.text)
        // The done line goes after two minutes.
        assertEquals(listOf("refused"), EditLineRules.lines(edits.filter { it.id == "done" || it.id == "refused" }, t + 70_000).map { it.id })
    }

    // ---- From the sheet to the edit, on both devices ----

    private val world = SyncWorld()
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private var n = 0
    private fun edits(d: os.meka.core.testing.Device) =
        CalendarEdits(d.replica, { world.clock.nowMs }, { "ad${d.name.take(1)}${n++}" }) { p, a -> AddEventRules.accountKey(p, a) in accounts.map { it.key } }

    @Test
    fun addingFromTheSheetMakesOneEditBothDevicesSeeAndRemembersTheAccount() {
        world.clock.nowMs = now
        val eFold = edits(fold)
        val eMac = edits(mac)
        val f = form(last = AddEventRules.lastUsedKey(eFold.all())).withAccount(outlook.key).withTitle("Dentist").withDay(thu + 1)
        val acct = assertNotNull(AddEventRules.account(f, accounts))
        val id = assertIs<EventEditResult.Made>(eFold.add(acct.provider, acct.email, AddEventRules.draft(f, bst))).id
        fold.sync(); mac.sync()
        val onMac = assertNotNull(eMac.edit(id))
        assertEquals(EventEditKind.ADD, onMac.kind)
        assertEquals("microsoft", onMac.provider)
        assertEquals(bst.toEpochMs(thu + 1, 10 * 60 + 30), onMac.draft?.startAtMs) // the time stays when the day changes
        assertEquals(listOf("Adding “Dentist” to Outlook"), EditLineRules.lines(eMac.all(), world.clock.nowMs).map { it.text })
        // The Mac's next sheet starts on Outlook.
        assertEquals(outlook.key, form(last = AddEventRules.lastUsedKey(eMac.all())).accountKey)

        // Undo on the Mac inside the window: the line goes on both.
        assertTrue(eMac.undo(id))
        mac.sync(); fold.sync()
        assertTrue(EditLineRules.lines(eFold.all(), world.clock.nowMs).isEmpty())
        assertNull(AddEventRules.lastUsedKey(eFold.all()))

        // The server's answer reaches both devices' lines.
        val id2 = assertIs<EventEditResult.Made>(eFold.add("google", "meka@gmail.com", AddEventRules.draft(form().withTitle("Gym"), bst))).id
        fold.sync()
        world.clock.nowMs += 8_000
        answer(id2, mapOf(EventEditFields.STATUS to "DONE".fv(), EventEditFields.STATUS_AT to world.clock.nowMs.fv()))
        fold.sync(); mac.sync()
        assertEquals(listOf("Added “Gym” to Google"), EditLineRules.lines(eMac.all(), world.clock.nowMs).map { it.text })
    }

    private val serverClock = HlcClock("server", { world.clock.nowMs })
    private var serverOps = 0
    private fun answer(id: String, fields: Map<String, FieldValue>) {
        for ((f, v) in fields) world.serverStore.append(
            Op("srvadd$id${serverOps++}", world.householdId, EntityTypes.EVENT_EDIT, id, f, v, serverClock.now(), emptyList(), "server"),
        )
    }
}
