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

/** Edit your calendars, slice 2a: the edit requests and their outcome. */
class CalendarEditsTest {
    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val world = SyncWorld()
    private val thu = CivilDate.toEpochDay(2026, 10, 8)
    private fun at(h: Int, m: Int = 0) = thu * day + h * hour + m * 60_000L

    private val fold = world.device("android")
    private val mac = world.device("mac")
    private var n = 0
    private var editing = true
    private fun edits(d: os.meka.core.testing.Device) =
        CalendarEdits(d.replica, { world.clock.nowMs }, { "ed${d.name.take(1)}${n++}" }) { p, a -> editing && p == "google" && a == "meka@gmail.com" }
    private val eFold = edits(fold)
    private val eMac = edits(mac)

    init { world.clock.nowMs = at(10) }

    private fun dentist(location: String? = "High St Surgery", notes: String? = null) =
        CalendarEvent("ev2", "Dentist", at(14), at(15), false, location, "google", "meka@gmail.com", "Personal", description = notes)

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private val serverClock = HlcClock("server", { world.clock.nowMs })
    private var serverOps = 0
    /** What the server writes back (CalendarWriter). */
    private fun answer(id: String, fields: Map<String, FieldValue>) {
        for ((f, v) in fields) world.serverStore.append(
            Op("srvedit$id${serverOps++}", world.householdId, EntityTypes.EVENT_EDIT, id, f, v, serverClock.now(), emptyList(), "server"),
        )
    }

    private fun made(r: EventEditResult) = assertIs<EventEditResult.Made>(r).id

    @Test
    fun anAddWaitsFiveSecondsThenTheServerAnswersOnEveryDevice() {
        val id = made(eFold.add("google", "meka@gmail.com", EventDraft("  Dentist ", at(14), at(15), false, " ", "Bring the form")))
        val e = assertNotNull(eFold.edit(id))
        assertEquals(EventEditKind.ADD, e.kind)
        assertEquals(EventDraft("Dentist", at(14), at(15), false, null, "Bring the form"), e.draft)
        assertEquals(at(10) + 5_000, e.sendAfterMs)
        assertEquals(EventEditState.WAITING, e.state(world.clock.nowMs))
        assertEquals("Adding “Dentist” to Google", CalendarEditRules.line(e, world.clock.nowMs))
        assertFalse(CalendarEditRules.due(e, at(10) + 6_000))
        assertTrue(CalendarEditRules.due(e, at(10) + 7_000))

        sync()
        world.clock.nowMs += 8_000
        val onMac = assertNotNull(eMac.edit(id))
        assertEquals(EventEditState.SENDING, onMac.state(world.clock.nowMs))
        assertEquals(listOf(id), eMac.open().map { it.id })

        answer(id, mapOf(EventEditFields.STATUS to "DONE".fv(), EventEditFields.STATUS_AT to world.clock.nowMs.fv()))
        sync()
        for (d in listOf(eFold, eMac)) {
            val done = assertNotNull(d.edit(id))
            assertEquals(EventEditState.DONE, done.state(world.clock.nowMs))
            assertEquals("Added “Dentist” to Google", CalendarEditRules.line(done, world.clock.nowMs))
            assertTrue(d.open().isEmpty())
        }
    }

    @Test
    fun undoWorksOnlyInsideTheWindowAndOnTheOtherDeviceToo() {
        val id = made(eFold.add("google", "meka@gmail.com", EventDraft("Call", at(16), at(16, 30), false)))
        sync()
        world.clock.nowMs += 3_000
        assertTrue(eMac.undo(id))
        sync()
        val e = assertNotNull(eFold.edit(id))
        assertEquals(EventEditState.UNDONE, e.state(world.clock.nowMs))
        assertFalse(CalendarEditRules.due(e, world.clock.nowMs + 60_000))
        assertEquals("Undone", CalendarEditRules.line(e, world.clock.nowMs))

        val late = made(eFold.add("google", "meka@gmail.com", EventDraft("Call", at(16), at(16, 30), false)))
        world.clock.nowMs += 5_000
        assertFalse(eFold.undo(late))
        assertFalse(eFold.undo("nope"))
    }

    @Test
    fun aMoveKeepsTheLengthAndCarriesWhatMekaSaw() {
        val id = made(eFold.move(dentist(), at(16)))
        val e = assertNotNull(eFold.edit(id))
        assertEquals(EventEditKind.CHANGE, e.kind)
        assertEquals("ev2", e.eventId)
        assertEquals(setOf(EventEditChange.TIME), e.changes)
        assertTrue(e.isMove)
        assertEquals(at(16), e.draft!!.startAtMs)
        assertEquals(at(17), e.draft!!.endAtMs)
        assertEquals(EventDraft("Dentist", at(14), at(15), false, "High St Surgery", null), e.base)
        assertEquals("Moving “Dentist” in Google", CalendarEditRules.line(e, world.clock.nowMs))
        // Only the time is written, so a location changed in Google meanwhile isn't overwritten.
        assertNull(fold.replica.entity(EntityTypes.EVENT_EDIT, id)!!.fields[EventEditFields.LOCATION])
        assertNull(fold.replica.entity(EntityTypes.EVENT_EDIT, id)!!.fields[EventEditFields.TITLE])
    }

    @Test
    fun aChangeSendsOnlyWhatChanged() {
        val base = CalendarEditRules.draftOf(dentist())
        val id = made(eFold.change(dentist(), base.copy(title = "Dentist (check-up)", location = null)))
        val e = assertNotNull(eFold.edit(id))
        assertEquals(setOf(EventEditChange.TITLE, EventEditChange.LOCATION), e.changes)
        assertEquals("Dentist (check-up)", e.draft!!.title)
        assertNull(e.draft!!.location)
        assertEquals(
            EventDraft("Dentist (check-up)", at(14), at(15), false, null, null),
            CalendarEditRules.merged(e.base!!, e.draft!!, e.changes),
        )
        assertEquals("Changing “Dentist (check-up)” in Google", CalendarEditRules.line(e, world.clock.nowMs))
        assertEquals(EventEditResult.Refused("Nothing changed"), eFold.change(dentist(), base.copy(title = " Dentist ")))
    }

    @Test
    fun outlookNotesAreNeverChangedBecauseMekaOnlyHasTheirStart() {
        val outlook = dentist(notes = "Preview…").copy(provider = "microsoft")
        val e = CalendarEdits(fold.replica, { world.clock.nowMs }, { "edo" }) { _, _ -> true }
        assertEquals(EventEditResult.Refused("Nothing changed"), e.change(outlook, CalendarEditRules.draftOf(outlook).copy(notes = "New notes")))
        assertEquals(setOf(EventEditChange.NOTES), CalendarEditRules.changes(CalendarEditRules.draftOf(dentist()), CalendarEditRules.draftOf(dentist()).copy(notes = "x"), "google"))
    }

    @Test
    fun editingNeedsAnAccountThatAllowsItAndNeverTouchesFixtures() {
        editing = false
        assertEquals(
            EventEditResult.Refused("Editing isn't allowed for meka@gmail.com · Allow editing in Calendars"),
            eFold.move(dentist(), at(16)),
        )
        assertIs<EventEditResult.Refused>(eFold.add("google", "meka@gmail.com", EventDraft("X", at(16), at(17), false)))
        editing = true
        val fixture = CalendarEvent("evf", "Barça v Sevilla", at(20), at(22), false, null, "fixtures", null, "Fixtures")
        assertEquals(EventEditResult.Refused("MEKA can't change this calendar"), eFold.delete(fixture))
        assertEquals(EventEditResult.Refused("MEKA can't add events there"), eFold.add("fixtures", "meka@gmail.com", EventDraft("X", at(16), at(17), false)))
        assertFalse(CalendarEditRules.editable(fixture) { _, _ -> true })
        assertTrue(CalendarEditRules.editable(dentist()) { _, _ -> true })
        assertFalse(CalendarEditRules.editable(dentist()) { _, _ -> false })
        assertTrue(eFold.all().isEmpty())
    }

    @Test
    fun draftsThatCantBeWrittenAreRefusedInWords() {
        fun add(d: EventDraft) = eFold.add("google", "meka@gmail.com", d)
        assertEquals(EventEditResult.Refused("Give it a title"), add(EventDraft("  ", at(14), at(15), false)))
        assertEquals(EventEditResult.Refused("It has to end after it starts"), add(EventDraft("A", at(15), at(15), false)))
        assertEquals(EventEditResult.Refused("That's longer than a month"), add(EventDraft("A", at(15), at(15) + 32 * day, false)))
        assertEquals(EventEditResult.Refused("An all-day event runs whole days"), add(EventDraft("A", at(15), at(15) + day, true)))
        assertEquals(EventEditResult.Refused("That's more than a year ago"), add(EventDraft("A", at(15) - 400 * day, at(16) - 400 * day, false)))
        assertEquals(EventEditResult.Refused("That's more than two years ahead"), add(EventDraft("A", at(15) + 800 * day, at(16) + 800 * day, false)))
        assertIs<EventEditResult.Made>(add(EventDraft("Holiday", thu * day, (thu + 3) * day, true)))
        // A move that would end before it starts can't happen (the length is kept), but a change to a bad time is refused.
        assertEquals(EventEditResult.Refused("It has to end after it starts"), eFold.change(dentist(), CalendarEditRules.draftOf(dentist()).copy(endAtMs = at(13))))
    }

    @Test
    fun aDeleteWithGuestsIsRefusedUntilMekaConfirms() {
        val id = made(eFold.delete(dentist()))
        val e = assertNotNull(eFold.edit(id))
        assertEquals(EventEditKind.DELETE, e.kind)
        assertNull(e.draft)
        assertFalse(e.guestsOk)
        assertEquals("Deleting “Dentist” from Google", CalendarEditRules.line(e, world.clock.nowMs))
        sync()
        answer(id, mapOf(
            EventEditFields.STATUS to "REFUSED".fv(), EventEditFields.GUESTS to 4.fv(),
            EventEditFields.DETAIL to CalendarEditRules.cancelsFor(4).fv(),
        ))
        sync()
        val refused = assertNotNull(eMac.edit(id))
        assertEquals(EventEditState.REFUSED, refused.state(world.clock.nowMs))
        assertEquals(4, refused.guests)
        assertEquals("This cancels it for 4 people", CalendarEditRules.line(refused, world.clock.nowMs))
        assertEquals(listOf(id), eMac.open().map { it.id })

        val again = made(eMac.delete(dentist(), guestsOk = true))
        assertTrue(assertNotNull(eMac.edit(again)).guestsOk)
        assertEquals("This cancels it for 1 person", CalendarEditRules.cancelsFor(1))
    }

    @Test
    fun theClashCheckLooksOnlyAtWhatTheEditWouldOverwrite() {
        val base = EventDraft("Dentist", at(14), at(15), false, "High St", "Form")
        val time = setOf(EventEditChange.TIME)
        // Google renamed it meanwhile: a move doesn't clash, a rename does.
        assertFalse(CalendarEditRules.clashes(EventEditKind.CHANGE, time, base, base.copy(title = "Dentist!")))
        assertTrue(CalendarEditRules.clashes(EventEditKind.CHANGE, setOf(EventEditChange.TITLE), base, base.copy(title = "Dentist!")))
        // Moved in Google meanwhile: a move clashes.
        assertTrue(CalendarEditRules.clashes(EventEditKind.CHANGE, time, base, base.copy(startAtMs = at(13))))
        assertTrue(CalendarEditRules.clashes(EventEditKind.CHANGE, setOf(EventEditChange.LOCATION), base, base.copy(location = "Elm Rd")))
        assertFalse(CalendarEditRules.clashes(EventEditKind.CHANGE, setOf(EventEditChange.LOCATION), base, base.copy(location = " High St ")))
        assertTrue(CalendarEditRules.clashes(EventEditKind.CHANGE, setOf(EventEditChange.NOTES), base, base.copy(notes = null)))
        // A delete clashes when it has become something else, not over its notes.
        assertTrue(CalendarEditRules.clashes(EventEditKind.DELETE, emptySet(), base, base.copy(endAtMs = at(16))))
        assertFalse(CalendarEditRules.clashes(EventEditKind.DELETE, emptySet(), base, base.copy(notes = "Other")))
    }

    @Test
    fun aClashShowsTheirVersionAndFailuresSayWhy() {
        val id = made(eFold.move(dentist(), at(16)))
        sync()
        answer(id, mapOf(
            EventEditFields.STATUS to "CLASH".fv(), EventEditFields.THEIR_TITLE to "Dentist".fv(),
            EventEditFields.THEIR_START to at(13).fv(), EventEditFields.THEIR_END to at(14).fv(), EventEditFields.THEIR_ALL_DAY to false.fv(),
        ))
        sync()
        val clash = assertNotNull(eMac.edit(id))
        assertEquals(EventEditState.CLASH, clash.state(world.clock.nowMs))
        // A clash written without their place and notes (before slice 2c-iii) falls back to what MEKA saw.
        assertEquals(EventDraft("Dentist", at(13), at(14), false, "High St Surgery", null), clash.theirs)
        assertEquals("“Dentist” changed in Google meanwhile · choose a version", CalendarEditRules.line(clash, world.clock.nowMs))

        val other = made(eFold.add("google", "meka@gmail.com", EventDraft("Gym", at(18), at(19), false)))
        sync()
        answer(other, mapOf(EventEditFields.STATUS to "FAILED".fv(), EventEditFields.DETAIL to "reconnect Google in Calendars".fv()))
        sync()
        assertEquals("Couldn't send to Google · reconnect Google in Calendars", CalendarEditRules.line(eFold.edit(other)!!, world.clock.nowMs))
    }

    @Test
    fun theServerGivesUpAfterADay() {
        val id = made(eFold.add("google", "meka@gmail.com", EventDraft("Gym", at(18), at(19), false)))
        val e = eFold.edit(id)!!
        assertFalse(CalendarEditRules.givenUp(e, at(10) + 23 * hour))
        assertTrue(CalendarEditRules.givenUp(e, at(10) + 25 * hour))
    }
}
