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

/** Edit your calendars, slice 2c-iii: the clash chooser (both versions; Keep mine · Keep theirs). */
class ClashChooserTest {
    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val world = SyncWorld()
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(h: Int, m: Int = 0) = fri * day + h * hour + m * 60_000L
    private val utc = LocalCalendar.UTC

    private val fold = world.device("android")
    private val mac = world.device("mac")
    private var n = 0
    private var allowed = true
    private fun edits(d: os.meka.core.testing.Device) =
        CalendarEdits(d.replica, { world.clock.nowMs }, { "ed${d.name.take(1)}${n++}" }) { p, a -> allowed && p == "google" && a == "meka@gmail.com" }
    private val eFold = edits(fold)
    private val eMac = edits(mac)

    init { world.clock.nowMs = at(10) }

    private val now get() = world.clock.nowMs
    private val dentist = CalendarEvent("ev2", "Dentist", at(14), at(15), false, "High St Surgery", "google", "meka@gmail.com", "meka@gmail.com")

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }
    private fun made(r: EventEditResult) = assertIs<EventEditResult.Made>(r).id

    private val serverClock = HlcClock("server", { world.clock.nowMs })
    private var serverOps = 0
    private fun clash(id: String, theirs: EventDraft) {
        val fields = mapOf<String, FieldValue>(
            EventEditFields.STATUS to "CLASH".fv(), EventEditFields.STATUS_AT to now.fv(),
            EventEditFields.THEIR_TITLE to theirs.title.fv(), EventEditFields.THEIR_START to theirs.startAtMs.fv(),
            EventEditFields.THEIR_END to theirs.endAtMs.fv(), EventEditFields.THEIR_ALL_DAY to theirs.allDay.fv(),
            EventEditFields.THEIR_LOCATION to (theirs.location ?: "").fv(), EventEditFields.THEIR_NOTES to (theirs.notes ?: "").fv(),
        )
        for ((f, v) in fields) world.serverStore.append(
            Op("srvclash$id${serverOps++}", world.householdId, EntityTypes.EVENT_EDIT, id, f, v, serverClock.now(), emptyList(), "server"),
        )
    }

    /** Meka moved the dentist to 16:00 while it was moved to 13:00 in Google (and its place changed there). */
    private val theirs = EventDraft("Dentist", at(13), at(14), false, "Elm Rd Surgery", null)
    private fun clashedMove(): String {
        val id = made(eFold.move(dentist, at(16)))
        sync()
        world.clock.nowMs += 8_000
        clash(id, theirs)
        sync()
        return id
    }

    @Test
    fun theDetailShowsBothVersionsOfWhatTheEditTouches() {
        val id = clashedMove()
        val note = assertNotNull(EditEventRules.note("ev2", eMac.all(), now, utc))
        assertEquals("“Dentist” changed in Google meanwhile · choose a version", note.text)
        assertTrue(note.needsMeka)
        val c = assertNotNull(note.clash)
        assertEquals(id, c.editId)
        assertEquals("Yours", c.mineLabel)
        assertEquals("Google", c.theirsLabel)
        // Only the time was Meka's change, so only the time is compared (their new place isn't his to overwrite).
        assertEquals(listOf(ClashRow("When", "Fri 9 Oct · 16:00–17:00", "Fri 9 Oct · 13:00–14:00")), c.rows)
        assertEquals("Keep mine", c.keepMineLabel)
        assertEquals("Keep Google's", c.keepTheirsLabel)
        // Calendar's line sends Meka to the detail.
        val line = EditLineRules.lines(eMac.all(), now).single()
        assertEquals("“Dentist” changed in Google meanwhile · open it to choose a version", line.text)
        assertTrue(line.needsMeka)
        assertEquals(listOf(id), eMac.open().map { it.id })
        // Without a calendar (no chooser asked for) the note stays as it was.
        assertNull(EditEventRules.note("ev2", eMac.all(), now)?.clash)
    }

    @Test
    fun keepMineSendsItAgainAgainstTheirVersionWithUndoOnBothDevices() {
        val id = clashedMove()
        val again = made(eMac.keepMine(id))
        val resend = assertNotNull(eMac.edit(again))
        assertEquals(EventEditKind.CHANGE, resend.kind)
        assertEquals(setOf(EventEditChange.TIME), resend.changes)
        assertEquals(theirs, resend.base) // checked against their version now
        assertEquals(at(16), resend.draft?.startAtMs)
        assertEquals(id, resend.resends)
        assertEquals(EventEditState.WAITING, resend.state(now))
        assertEquals("Moving “Dentist” in Google", CalendarEditRules.line(resend, now))
        sync()

        // The clash is answered on the Fold too: no chooser, and the detail follows the resend.
        assertEquals(ClashChoice.MINE, eFold.edit(id)?.resolved)
        assertFalse(eFold.edit(id)!!.needsChoice)
        val note = assertNotNull(EditEventRules.note("ev2", eFold.all(), now, utc))
        assertNull(note.clash)
        assertEquals("Moving “Dentist” in Google", note.text)
        assertEquals(listOf("Moving “Dentist” in Google"), EditLineRules.lines(eFold.all(), now).map { it.text })
        assertEquals(listOf(again), eFold.open().map { it.id })
        // The move shows at once, over their version as mirrored (keeping their place).
        val shown = PendingEditRules.apply(listOf(dentist.copy(startAtMs = at(13), endAtMs = at(14), location = "Elm Rd Surgery")), eFold.all(), now).single()
        assertEquals(at(16), shown.startAtMs)
        assertEquals("Elm Rd Surgery", shown.location)

        // Answering twice does nothing.
        assertIs<EventEditResult.Refused>(eFold.keepMine(id))
        assertFalse(eFold.keepTheirs(id))
    }

    @Test
    fun undoingKeepMineAsksAgain() {
        val id = clashedMove()
        val again = made(eFold.keepMine(id))
        assertTrue(eFold.undo(again))
        sync()
        assertTrue(eMac.edit(id)!!.needsChoice)
        assertNotNull(EditEventRules.note("ev2", eMac.all(), now, utc)?.clash)
        // And it can be answered again.
        assertTrue(eMac.keepTheirs(id))
    }

    @Test
    fun keepTheirsSendsNothingAndSaysSoBriefly() {
        val id = clashedMove()
        assertTrue(eFold.keepTheirs(id))
        sync()
        val e = eMac.edit(id)!!
        assertEquals(ClashChoice.THEIRS, e.resolved)
        assertEquals(listOf("Kept Google's version of “Dentist”"), EditLineRules.lines(eMac.all(), now).map { it.text })
        assertFalse(EditLineRules.lines(eMac.all(), now).single().needsMeka)
        assertTrue(eMac.open().isEmpty())
        assertNull(EditEventRules.note("ev2", eMac.all(), now, utc)?.clash)
        // The mirror (their version) is what's shown.
        val mirror = listOf(dentist.copy(startAtMs = at(13), endAtMs = at(14)))
        assertEquals(mirror, PendingEditRules.apply(mirror, eMac.all(), now))
        world.clock.nowMs += EditLineRules.DONE_SHOWN_MS + 1_000
        assertTrue(EditLineRules.lines(eMac.all(), now).isEmpty())
    }

    @Test
    fun aClashedDeleteOffersDeletingTheirVersion() {
        val id = made(eFold.delete(dentist))
        sync()
        world.clock.nowMs += 8_000
        clash(id, theirs.copy(title = "Dentist + hygienist"))
        sync()
        val c = assertNotNull(EditEventRules.note("ev2", eFold.all(), now, utc)?.clash)
        assertEquals(listOf(ClashRow("Event", "Deleted", "Dentist + hygienist · Fri 9 Oct · 13:00–14:00")), c.rows)
        assertEquals("Delete it", c.keepMineLabel)
        val again = made(eFold.keepMine(id))
        val resend = eFold.edit(again)!!
        assertEquals(EventEditKind.DELETE, resend.kind)
        assertEquals("Dentist + hygienist", resend.base?.title)
        assertFalse(resend.guestsOk) // the guests guard still applies
    }

    @Test
    fun changesMadeOnBothSidesAreComparedFieldByField() {
        val id = made(eFold.change(dentist, CalendarEditRules.draftOf(dentist).copy(title = "Dentist check-up", location = "High St, room 2")))
        sync()
        world.clock.nowMs += 8_000
        clash(id, EventDraft("Dentist (Dr Okafor)", at(14), at(15), false, "High St Surgery", "Long notes ".repeat(20)))
        sync()
        val c = assertNotNull(EditEventRules.note("ev2", eFold.all(), now, utc)?.clash)
        assertEquals(
            listOf(ClashRow("Title", "Dentist check-up", "Dentist (Dr Okafor)"), ClashRow("Place", "High St, room 2", "High St Surgery")),
            c.rows,
        )
        // Keep mine writes only what still differs from theirs, and keeps their notes.
        val resend = eFold.edit(made(eFold.keepMine(id)))!!
        assertEquals(setOf(EventEditChange.TITLE, EventEditChange.LOCATION), resend.changes)
        assertEquals("Long notes ".repeat(20), resend.base?.notes)
    }

    @Test
    fun whenTheirVersionAlreadyReadsAsMineNothingIsSent() {
        val id = made(eFold.change(dentist, CalendarEditRules.draftOf(dentist).copy(title = "Dentist check-up")))
        sync()
        world.clock.nowMs += 8_000
        clash(id, EventDraft("Dentist check-up", at(13), at(14), false, null, null))
        sync()
        val r = assertIs<EventEditResult.Refused>(eFold.keepMine(id))
        assertEquals("Google already has your version", r.reason)
        assertEquals(ClashChoice.THEIRS, eFold.edit(id)?.resolved)
        assertEquals(1, eFold.all().size)
    }

    @Test
    fun keepMineNeedsEditingStillAllowed() {
        val id = clashedMove()
        allowed = false
        val r = assertIs<EventEditResult.Refused>(eFold.keepMine(id))
        assertEquals("Editing isn't allowed for meka@gmail.com · Allow editing in Calendars", r.reason)
        assertTrue(eFold.edit(id)!!.needsChoice)
        // Keep theirs sends nothing, so it is always allowed.
        assertTrue(eFold.keepTheirs(id))
    }
}
