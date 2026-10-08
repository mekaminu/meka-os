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

/** Edit your calendars, slice 2c-ii: a change shows on every view before Google answers. */
class PendingEditsTest {
    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val world = SyncWorld()
    private val thu = CivilDate.toEpochDay(2026, 10, 8)
    private fun at(h: Int, m: Int = 0) = thu * day + h * hour + m * 60_000L

    private val fold = world.device("android")
    private val mac = world.device("mac")
    private var n = 0
    private fun edits(d: os.meka.core.testing.Device) =
        CalendarEdits(d.replica, { world.clock.nowMs }, { "ed${d.name.take(1)}${n++}" }) { p, a -> p == "google" && a == "meka@gmail.com" }
    private val eFold = edits(fold)
    private val eMac = edits(mac)

    init { world.clock.nowMs = at(10) }

    private val now get() = world.clock.nowMs
    private val dentist = CalendarEvent("ev2", "Dentist", at(14), at(15), false, "High St Surgery", "google", "meka@gmail.com", "meka@gmail.com")
    private val standup = CalendarEvent("ev3", "Standup", at(9), at(9, 15), false, null, "google", "meka@gmail.com", "Work")
    private val mirror = listOf(standup, dentist)

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }
    private fun made(r: EventEditResult) = assertIs<EventEditResult.Made>(r).id

    private val serverClock = HlcClock("server", { world.clock.nowMs })
    private var serverOps = 0
    private fun answer(id: String, status: String, detail: String? = null) {
        val fields = buildMap<String, FieldValue> {
            put(EventEditFields.STATUS, status.fv())
            put(EventEditFields.STATUS_AT, now.fv())
            detail?.let { put(EventEditFields.DETAIL, it.fv()) }
        }
        for ((f, v) in fields) world.serverStore.append(
            Op("srvpend$id${serverOps++}", world.householdId, EntityTypes.EVENT_EDIT, id, f, v, serverClock.now(), emptyList(), "server"),
        )
    }

    @Test
    fun aMoveShowsAtOnceOnBothDevicesWithTheSameIdSoTheRowGlides() {
        val id = made(eFold.move(dentist, at(16)))
        val shown = PendingEditRules.apply(mirror, eFold.all(), now)
        val moved = shown.single { it.id == "ev2" }
        assertEquals(at(16), moved.startAtMs)
        assertEquals(at(17), moved.endAtMs)
        assertEquals("High St Surgery", moved.location)
        assertEquals(id, moved.pendingEditId)
        assertNull(shown.single { it.id == "ev3" }.pendingEditId)

        sync()
        world.clock.nowMs += 8_000 // past the window: on its way, still shown moved
        assertEquals(at(16), PendingEditRules.apply(mirror, eMac.all(), now).single { it.id == "ev2" }.startAtMs)
    }

    @Test
    fun undoPutsItBack() {
        val id = made(eFold.move(dentist, at(16)))
        assertTrue(eFold.undo(id))
        assertEquals(mirror, PendingEditRules.apply(mirror, eFold.all(), now))
    }

    @Test
    fun aDeleteTakesTheEventAwayUntilTheMirrorDropsIt() {
        val id = made(eFold.delete(dentist))
        assertEquals(listOf("ev3"), PendingEditRules.apply(mirror, eFold.all(), now).map { it.id })
        sync()
        world.clock.nowMs += 8_000
        answer(id, "DONE")
        sync()
        // Done but the poll hasn't dropped it yet: still gone.
        assertEquals(listOf("ev3"), PendingEditRules.apply(mirror, eMac.all(), now).map { it.id })
        // Long after, the mirror is the truth again (by then it has dropped it).
        world.clock.nowMs += EditLineRules.DONE_SHOWN_MS + 1_000
        assertEquals(mirror, PendingEditRules.apply(mirror, eMac.all(), now))
    }

    @Test
    fun aRefusedDeleteForGuestsBringsTheEventBack() {
        val id = made(eFold.delete(dentist))
        sync()
        world.clock.nowMs += 8_000
        answer(id, "REFUSED", CalendarEditRules.cancelsFor(4))
        sync()
        assertEquals(mirror, PendingEditRules.apply(mirror, eFold.all(), now))
    }

    @Test
    fun anAddIsAProvisionalEventUntilTheRealOneIsMirrored() {
        val id = made(eFold.add("google", "meka@gmail.com", EventDraft("Gym class", at(18), at(19), false, null, "Bring a towel")))
        val p = PendingEditRules.apply(mirror, eFold.all(), now).single { it.isProvisional }
        assertEquals(PendingEditRules.PROVISIONAL_PREFIX + id, p.id)
        assertEquals(id, PendingEditRules.editIdOf(p.id))
        assertEquals("Gym class", p.title)
        assertEquals("Bring a towel", p.description)
        assertEquals("meka@gmail.com", p.calendarName) // the account's main calendar, as the mirror names it
        assertEquals(id, p.pendingEditId)

        sync()
        world.clock.nowMs += 8_000
        answer(id, "DONE")
        sync()
        // Done, not polled yet: still shown (no longer pending).
        val done = PendingEditRules.apply(mirror, eMac.all(), now).single { it.isProvisional }
        assertNull(done.pendingEditId)
        // Polled: the real event replaces it, never shown twice.
        val real = CalendarEvent("ev9", "Gym class", at(18), at(19), false, null, "google", "meka@gmail.com", "meka@gmail.com")
        assertEquals(mirror + real, PendingEditRules.apply(mirror + real, eMac.all(), now))
    }

    @Test
    fun aClashOrFailureShowsTheProvidersCopy() {
        val a = made(eFold.move(dentist, at(16)))
        val b = made(eFold.add("google", "meka@gmail.com", EventDraft("Call", at(12), at(12, 30), false)))
        sync()
        world.clock.nowMs += 8_000
        answer(a, "CLASH")
        answer(b, "FAILED", "Reconnect Google in Calendars")
        sync()
        assertEquals(mirror, PendingEditRules.apply(mirror, eFold.all(), now))
    }

    @Test
    fun aDoneChangeStopsOnceTheMirrorHasIt() {
        val id = made(eFold.change(dentist, CalendarEditRules.draftOf(dentist).copy(title = "Dentist (check-up)")))
        sync()
        world.clock.nowMs += 8_000
        answer(id, "DONE")
        sync()
        assertEquals("Dentist (check-up)", PendingEditRules.apply(mirror, eFold.all(), now).single { it.id == "ev2" }.title)
        // The poll brought Google's copy, renamed again there meanwhile: the mirror wins.
        val polled = listOf(standup, dentist.copy(title = "Dentist (moved by the surgery)"))
        assertEquals(polled, PendingEditRules.apply(polled, eFold.all(), now))
    }

    @Test
    fun twoEditsInARowEndAsTheLaterLeftIt() {
        made(eFold.move(dentist, at(16)))
        world.clock.nowMs += 1_000
        val moved = PendingEditRules.apply(mirror, eFold.all(), now).single { it.id == "ev2" }
        made(eFold.change(moved.copy(pendingEditId = null), CalendarEditRules.draftOf(moved).copy(title = "Dentist!")))
        val shown = PendingEditRules.apply(mirror, eFold.all(), now).single { it.id == "ev2" }
        assertEquals("Dentist!", shown.title)
        assertEquals(at(16), shown.startAtMs)
    }

    @Test
    fun aProvisionalEventCantBeEditedOrMarked() {
        val id = made(eFold.add("google", "meka@gmail.com", EventDraft("Gym class", at(18), at(19), false)))
        val p = PendingEditRules.apply(mirror, eFold.all(), now).single { it.isProvisional }
        assertFalse(CalendarEditRules.editable(p) { _, _ -> true })
        assertEquals(EventEditResult.Refused("Wait until Google has it"), eFold.delete(p))

        val detail = EventDetails.build(p, now, LocalCalendar.UTC)
        assertTrue(detail.provisional)
        assertFalse(detail.canPrep)
        assertTrue(detail.remindChoices.isEmpty() && detail.travelChoices.isEmpty())
        val note = assertNotNull(EditEventRules.note(p.id, eFold.all(), now))
        assertEquals(id, note.editId)
        assertEquals("Adding “Gym class” to Google", note.text)

        val tasks = Tasks(fold.replica, { "t${n++}" }, { now })
        val actions = EventActions(fold.replica, tasks, { now }, LocalCalendar.UTC)
        actions.hide(p.id)
        actions.setReminder(p.id, 10)
        assertNull(fold.replica.entity(EntityTypes.EVENT_MARK, p.id))
    }

    @Test
    fun theMainCalendarNameIsOnlyUsedWhenTheMirrorHasIt() {
        assertEquals("meka@gmail.com", PendingEditRules.mainCalendarName("google", "meka@gmail.com", mirror))
        assertNull(PendingEditRules.mainCalendarName("google", "other@gmail.com", mirror))
        val outlook = CalendarEvent("ev5", "Lunch", at(12), at(13), false, null, "microsoft", "meka@outlook.com", "Calendar")
        assertEquals("Calendar", PendingEditRules.mainCalendarName("microsoft", "meka@outlook.com", listOf(outlook)))
    }
}
