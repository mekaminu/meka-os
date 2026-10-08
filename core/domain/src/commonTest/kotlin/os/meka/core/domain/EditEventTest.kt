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

/** Edit your calendars, slice 2c: change, move and delete from the event detail. */
class EditEventTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    private val day = 86_400_000L
    private val thu = CivilDate.toEpochDay(2026, 10, 8)
    private val bst = LocalCalendar.fixedOffset(hour)
    private val now = bst.toEpochMs(thu, 10 * 60 + 7)

    private fun event(
        id: String = "ev1", title: String = "Dentist", start: Long = bst.toEpochMs(thu + 1, 14 * 60 + 5), lengthMs: Long = hour,
        allDay: Boolean = false, provider: String = "google", account: String = "meka@gmail.com",
        location: String? = "High St Surgery", notes: String? = "Bring the form",
    ) = CalendarEvent(id, title, start, start + lengthMs, allDay, location, provider, account, "Personal", notes)

    @Test
    fun theFormIsFilledInFromTheEventAndSavingWaitsForAChange() {
        val e = event()
        val f = EditEventRules.start(e, now, bst)
        assertEquals("Dentist", f.title)
        assertEquals(thu + 1, f.day)
        assertEquals(14 * 60 + 5, f.minute) // off the quarter hour, kept as it is
        assertEquals(60, f.lengthMin)
        assertEquals("google|meka@gmail.com", f.accountKey)
        assertEquals("High St Surgery", f.location)
        assertEquals("Bring the form", f.notes)

        val v = EditEventRules.view(f, e, canEdit = true, now, bst)
        assertEquals("Save to Google", v.addLabel)
        assertEquals("Delete from Google", v.deleteLabel)
        assertEquals("Tomorrow · 14:05–15:05", v.summary)
        assertTrue(v.timeEditable)
        assertTrue(v.notesEditable)
        assertFalse(v.canAdd) // nothing changed yet
        assertNull(v.problem)
        assertEquals(listOf("Google · meka@gmail.com"), v.accounts.map { it.label })
        assertTrue(EditEventRules.changes(f, e, now, bst).isEmpty())

        val renamed = f.withTitle("Dentist (Ada)")
        assertEquals(setOf(EventEditChange.TITLE), EditEventRules.changes(renamed, e, now, bst))
        assertTrue(EditEventRules.view(renamed, e, true, now, bst).canAdd)
        // The time stays the event's own to the millisecond.
        val d = EditEventRules.draft(renamed, e, now, bst)
        assertEquals(e.startAtMs, d.startAtMs)
        assertEquals(e.endAtMs, d.endAtMs)

        assertEquals("Give it a title", EditEventRules.view(f.withTitle("  "), e, true, now, bst).problem)
        val notAllowed = EditEventRules.view(renamed, e, canEdit = false, now, bst)
        assertEquals("Editing isn't allowed for meka@gmail.com · Allow editing in Calendars", notAllowed.problem)
        assertFalse(notAllowed.canAdd)
    }

    @Test
    fun aNewTimeIsAMoveThatKeepsTheLengthAndAMultiDayAllDayEventKeepsItsSpan() {
        val e = event()
        val later = EditEventRules.start(e, now, bst).stepTime(4) // 14:00 snapped, + 1 h
        val d = EditEventRules.draft(later, e, now, bst)
        assertEquals(bst.toEpochMs(thu + 1, 15 * 60), d.startAtMs)
        assertEquals(hour, d.endAtMs - d.startAtMs)
        assertEquals(setOf(EventEditChange.TIME), EditEventRules.changes(later, e, now, bst))
        assertEquals(setOf(EventEditChange.TIME), EditEventRules.changes(EditEventRules.start(e, now, bst).withLength(90), e, now, bst))

        val trip = event(id = "trip", title = "Lisbon", start = (thu + 3) * day, lengthMs = 3 * day, allDay = true, location = null, notes = null)
        val tf = EditEventRules.start(trip, now, bst)
        assertTrue(tf.isAllDay)
        assertEquals(thu + 3, tf.day)
        assertTrue(EditEventRules.changes(tf, trip, now, bst).isEmpty())
        val moved = EditEventRules.draft(tf.withDay(thu + 10), trip, now, bst)
        assertEquals((thu + 10) * day, moved.startAtMs)
        assertEquals((thu + 13) * day, moved.endAtMs)
        assertTrue(moved.allDay)
        // Made a timed event: the form's start and length.
        val timed = EditEventRules.draft(tf.withAllDay(false), trip, now, bst)
        assertFalse(timed.allDay)
        assertEquals(bst.toEpochMs(thu + 3, 9 * 60), timed.startAtMs)
    }

    @Test
    fun aRunningOrLongEventKeepsItsTimeButCanBeRenamed() {
        val running = event(start = now - 10 * min)
        assertFalse(EditEventRules.timeEditable(running, now, bst))
        val f = EditEventRules.start(running, now, bst)
        val v = EditEventRules.view(f, running, true, now, bst)
        assertFalse(v.timeEditable)
        assertTrue(v.chips.isEmpty())
        assertNull(v.startLabel)
        assertTrue(v.lengths.isEmpty())
        assertEquals("Thu 8 Oct · 09:57–10:57", v.summary)
        // Stepping the form anyway changes nothing.
        assertTrue(EditEventRules.changes(f.stepTime(4).withTitle("Dentist!"), running, now, bst) == setOf(EventEditChange.TITLE))

        val long = event(lengthMs = 26 * hour)
        assertFalse(EditEventRules.timeEditable(long, now, bst))
        assertTrue(EditEventRules.timeEditable(event(lengthMs = 24 * hour), now, bst))
        val past = event(start = now - 400 * day)
        assertNull(EditEventRules.view(EditEventRules.start(past, now, bst).withTitle("Old"), past, true, now, bst).problem)
    }

    @Test
    fun outlookNotesAndLongNotesAreLeftAlone() {
        val o = event(provider = "microsoft", account = "meka@outlook.com")
        val v = EditEventRules.view(EditEventRules.start(o, now, bst), o, true, now, bst)
        assertFalse(v.notesEditable)
        assertEquals("Outlook's notes are changed in Outlook", v.notesNote)
        assertEquals("Save to Outlook", v.addLabel)
        val f = EditEventRules.start(o, now, bst).withNotes("New notes")
        assertTrue(EditEventRules.changes(f, o, now, bst).isEmpty())

        val longNotes = "n".repeat(3_000)
        val g = event(notes = longNotes, title = "  Spaced  ")
        assertFalse(EditEventRules.notesEditable(g))
        val renamed = EditEventRules.start(g, now, bst).withLocation("Room 2")
        val d = EditEventRules.draft(renamed, g, now, bst)
        assertEquals(longNotes, d.notes)
        assertEquals("  Spaced  ", d.title) // untouched fields keep the event's own value
        assertEquals(setOf(EventEditChange.LOCATION), CalendarEditRules.changes(CalendarEditRules.draftOf(g), CalendarEditRules.clean(d), "google"))
    }

    // ---- On both devices, through the store ----

    private val world = SyncWorld()
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private var n = 0
    private fun edits(d: os.meka.core.testing.Device) =
        CalendarEdits(d.replica, { world.clock.nowMs }, { "ed${d.name.take(1)}${n++}" }) { p, _ -> p == "google" }

    @Test
    fun savingMakesOneEditOfOnlyWhatChangedThatTheMacSees() {
        world.clock.nowMs = now
        val e = event(notes = "x".repeat(2_500))
        val eFold = edits(fold)
        val eMac = edits(mac)
        val f = EditEventRules.start(e, now, bst).stepTime(4)
        val id = assertIs<EventEditResult.Made>(eFold.change(e, EditEventRules.draft(f, e, now, bst))).id
        fold.sync(); mac.sync()
        val onMac = assertNotNull(eMac.edit(id))
        assertTrue(onMac.isMove)
        assertEquals(e.startAtMs, onMac.base?.startAtMs)
        assertEquals(listOf("Moving “Dentist” in Google"), EditLineRules.lines(eMac.all(), world.clock.nowMs).map { it.text })
        val note = assertNotNull(EditEventRules.note("ev1", eMac.all(), world.clock.nowMs))
        assertEquals("Moving “Dentist” in Google", note.text)
        assertTrue(note.waiting)
        assertFalse(note.deleteAnyway)
        assertNull(EditEventRules.note("other", eMac.all(), world.clock.nowMs))
        // Undone: the detail says nothing.
        assertTrue(eMac.undo(id))
        assertNull(EditEventRules.note("ev1", eMac.all(), world.clock.nowMs))
        // A delete made in the same millisecond: the undone move doesn't hide it.
        val del = assertIs<EventEditResult.Made>(eMac.delete(e)).id
        assertEquals(del, EditEventRules.note("ev1", eMac.all(), world.clock.nowMs)?.editId)
    }

    @Test
    fun aDeleteHeldBackForItsGuestsAsksForASecondTap() {
        world.clock.nowMs = now
        val e = event(title = "Standup")
        val eFold = edits(fold)
        val eMac = edits(mac)
        val id = assertIs<EventEditResult.Made>(eFold.delete(e)).id
        assertEquals("Deleting “Standup” from Google", EditEventRules.deletingLine(e))
        fold.sync()
        world.clock.nowMs += 8_000
        answer(id, mapOf(
            EventEditFields.STATUS to "REFUSED".fv(), EventEditFields.STATUS_AT to world.clock.nowMs.fv(),
            EventEditFields.DETAIL to CalendarEditRules.cancelsFor(4).fv(), EventEditFields.GUESTS to 4L.fv(),
        ))
        fold.sync(); mac.sync()
        val held = assertNotNull(eMac.edit(id))
        assertTrue(EditEventRules.needsGuestsOk(held, world.clock.nowMs))
        val note = assertNotNull(EditEventRules.note("ev1", eMac.all(), world.clock.nowMs))
        assertTrue(note.deleteAnyway)
        assertTrue(note.needsMeka)
        assertFalse(note.waiting)
        assertEquals("This cancels it for 4 people · they'll be told if you delete it", note.text)
        assertEquals(
            listOf("“Standup” · This cancels it for 4 people · open it to delete anyway"),
            EditLineRules.lines(eMac.all(), world.clock.nowMs).map { it.text },
        )

        // Delete anyway on the Mac: a new edit with Meka's confirmation; the detail now follows that one.
        val again = assertIs<EventEditResult.Made>(eMac.delete(e, guestsOk = true)).id
        mac.sync(); fold.sync()
        assertTrue(assertNotNull(eFold.edit(again)).guestsOk)
        val now2 = assertNotNull(EditEventRules.note("ev1", eFold.all(), world.clock.nowMs))
        assertFalse(now2.deleteAnyway)
        assertTrue(now2.waiting)
        assertEquals("Deleting “Standup” from Google", now2.text)

        // Someone else's event is refused with guests counted, but that's no "Delete anyway".
        val other = edits(fold).delete(event(id = "ev2"))
        val otherId = assertIs<EventEditResult.Made>(other).id
        fold.sync()
        answer(otherId, mapOf(
            EventEditFields.STATUS to "REFUSED".fv(), EventEditFields.STATUS_AT to world.clock.nowMs.fv(),
            EventEditFields.DETAIL to "Someone else organises it · MEKA only changes your own events".fv(), EventEditFields.GUESTS to 3L.fv(),
        ))
        fold.sync()
        assertFalse(assertNotNull(EditEventRules.note("ev2", eFold.all(), world.clock.nowMs)).deleteAnyway)
    }

    private val serverClock = HlcClock("server", { world.clock.nowMs })
    private var serverOps = 0
    private fun answer(id: String, fields: Map<String, FieldValue>) {
        for ((f, v) in fields) world.serverStore.append(
            Op("srved$id${serverOps++}", world.householdId, EntityTypes.EVENT_EDIT, id, f, v, serverClock.now(), emptyList(), "server"),
        )
    }
}
