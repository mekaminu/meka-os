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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edit your calendars, slice 2d: every calendar edit Meka makes is in the activity log, as it goes. */
class CalendarEditActivityTest {
    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val world = SyncWorld()
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(h: Int, m: Int = 0) = fri * day + h * hour + m * 60_000L
    private val utc = LocalCalendar.UTC

    private val fold = world.device("android")
    private val mac = world.device("mac")
    private var n = 0
    private fun edits(d: os.meka.core.testing.Device) =
        CalendarEdits(d.replica, { world.clock.nowMs }, { "ed${d.name.take(1)}${n++}" }) { p, a -> p == "google" && a == "meka@gmail.com" }
    private val eFold = edits(fold)
    private val eMac = edits(mac)
    private fun log(d: os.meka.core.testing.Device) = ActivityLog(d.replica, { "a${n++}" }, { world.clock.nowMs }, utc)
    private val logFold = log(fold)
    private val logMac = log(mac)

    init { world.clock.nowMs = at(10) }

    private val now get() = world.clock.nowMs
    private val dentist = CalendarEvent("ev2", "Dentist", at(14), at(15), false, "High St Surgery", "google", "meka@gmail.com", "meka@gmail.com")
    private val standup = CalendarEvent("ev3", "Standup", at(9), at(9, 15), false, null, "google", "meka@gmail.com", "meka@gmail.com")

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }
    private fun made(r: EventEditResult) = assertIs<EventEditResult.Made>(r).id
    private fun rows(l: ActivityLog) = l.view().days.flatMap { it.rows }.filter { it.kind == ActivityKind.CALENDAR }

    private val serverClock = HlcClock("server", { world.clock.nowMs })
    private var serverOps = 0
    private fun answer(id: String, vararg fields: Pair<String, FieldValue>) {
        for ((f, v) in listOf(EventEditFields.STATUS_AT to now.fv()) + fields) world.serverStore.append(
            Op("srv$id${serverOps++}", world.householdId, EntityTypes.EVENT_EDIT, id, f, v, serverClock.now(), emptyList(), "server"),
        )
    }

    @Test
    fun aMoveShowsOnBothDevicesWhileItIsSentAndOnceGoogleHasIt() {
        val id = made(eFold.move(dentist, at(16)))
        sync()
        val waiting = rows(logMac).single()
        assertEquals("Moving “Dentist” in Google", waiting.summary)
        assertEquals("Fri 9 Oct · 14:00–15:00 → 16:00–17:00", waiting.detail)
        assertEquals("Why: Your edit in MEKA · editing allowed for meka@gmail.com", waiting.why)
        assertFalse(waiting.canUndo)
        assertNull(waiting.undoneLine)
        assertEquals("10:00", waiting.time)

        world.clock.nowMs += 8_000
        answer(id, EventEditFields.STATUS to "DONE".fv())
        sync()
        for (l in listOf(logFold, logMac)) {
            val done = rows(l).single()
            assertEquals("Moved “Dentist” in Google", done.summary)
            assertEquals(ActivityRules.calendarEditId(id), done.id)
        }
        assertEquals("This week: 1 calendar edit", logMac.view().weekLine)
        // Nothing to undo from Activity: the row says so by offering none.
        assertEquals(UndoOutcome.NOT_UNDOABLE, logFold.undo(ActivityRules.calendarEditId(id)))
    }

    @Test
    fun anEditUndoneInItsFiveSecondsLeavesNothingInTheLog() {
        val id = made(eFold.add("google", "meka@gmail.com", EventDraft("Haircut", at(17), at(17, 30), false)))
        assertEquals("Adding “Haircut” to Google", rows(logFold).single().summary)
        assertTrue(eFold.undo(id))
        sync()
        assertTrue(rows(logFold).isEmpty())
        assertTrue(rows(logMac).isEmpty())
    }

    @Test
    fun anAddSaysWhenAndWhere() {
        val id = made(eMac.add("google", "meka@gmail.com", EventDraft("Dentist", at(15), at(16), false, "High St Surgery")))
        world.clock.nowMs += 8_000
        answer(id, EventEditFields.STATUS to "DONE".fv())
        sync()
        val row = rows(logFold).single()
        assertEquals("Added “Dentist” to Google", row.summary)
        assertEquals("Fri 9 Oct · 15:00–16:00 · High St Surgery", row.detail)
    }

    @Test
    fun aChangeSaysWhatItChanged() {
        val draft = EventDraft("Dentist check-up", at(14), at(15), false, "Elm Rd Surgery", null)
        val id = made(eFold.change(dentist, draft))
        val row = rows(logFold).single { it.id == ActivityRules.calendarEditId(id) }
        assertEquals("Changing “Dentist check-up” in Google", row.summary)
        assertEquals("Was “Dentist” · Place: High St Surgery → Elm Rd Surgery", row.detail)

        val e = eFold.edit(id)!!
        assertEquals("Place removed", ActivityRules.calendarEditDetail(e.copy(changes = setOf(EventEditChange.LOCATION), draft = draft.copy(location = null)), utc))
        assertEquals("Place: Elm Rd Surgery", ActivityRules.calendarEditDetail(e.copy(changes = setOf(EventEditChange.LOCATION), base = e.base!!.copy(location = null)), utc))
        assertEquals("Notes changed", ActivityRules.calendarEditDetail(e.copy(changes = setOf(EventEditChange.NOTES)), utc))
        // A move to another day says both days.
        val other = e.copy(changes = setOf(EventEditChange.TIME), draft = draft.copy(startAtMs = at(14) + day, endAtMs = at(15) + day))
        assertEquals("Fri 9 Oct · 14:00–15:00 → Sat 10 Oct · 14:00–15:00", ActivityRules.calendarEditDetail(other, utc))
    }

    @Test
    fun refusedFailedAndClashedEditsSayWhatHappened() {
        val held = made(eFold.delete(standup))
        world.clock.nowMs += 8_000
        answer(held, EventEditFields.STATUS to "REFUSED".fv(), EventEditFields.DETAIL to "This cancels it for 4 people".fv(), EventEditFields.GUESTS to 4L.fv())
        sync()
        assertEquals("Didn't delete “Standup” from Google · This cancels it for 4 people", rows(logMac).single().summary)
        assertEquals("Fri 9 Oct · 09:00–09:15", rows(logMac).single().detail)

        val anyway = made(eMac.delete(standup, guestsOk = true))
        world.clock.nowMs += 8_000
        answer(anyway, EventEditFields.STATUS to "DONE".fv(), EventEditFields.GUESTS to 4L.fv())
        sync()
        val deleted = rows(logFold).single { it.id == ActivityRules.calendarEditId(anyway) }
        assertEquals("Deleted “Standup” from Google", deleted.summary)
        assertEquals("Fri 9 Oct · 09:00–09:15 · its 4 guests were told", deleted.detail)

        val failed = made(eFold.move(dentist, at(16)))
        world.clock.nowMs += 8_000
        answer(failed, EventEditFields.STATUS to "FAILED".fv(), EventEditFields.DETAIL to "Reconnect Google in Calendars".fv())
        sync()
        assertEquals("Couldn't move “Dentist” in Google · Reconnect Google in Calendars",
            rows(logFold).single { it.id == ActivityRules.calendarEditId(failed) }.summary)

        val clashed = made(eFold.move(dentist, at(17)))
        world.clock.nowMs += 8_000
        answer(
            clashed, EventEditFields.STATUS to "CLASH".fv(),
            EventEditFields.THEIR_TITLE to "Dentist".fv(), EventEditFields.THEIR_START to at(13).fv(), EventEditFields.THEIR_END to at(14).fv(),
            EventEditFields.THEIR_ALL_DAY to false.fv(),
        )
        sync()
        val clashRow = { rows(logMac).single { it.id == ActivityRules.calendarEditId(clashed) } }
        assertEquals("“Dentist” changed in Google meanwhile · open it to choose a version", clashRow().summary)
        assertTrue(eMac.keepTheirs(clashed))
        sync()
        assertEquals("Kept Google's version of “Dentist”", rows(logFold).single { it.id == ActivityRules.calendarEditId(clashed) }.summary)
        assertEquals("This week: 4 calendar edits", logFold.view().weekLine)
    }

    @Test
    fun keepMineIsItsOwnRowNamingTheClash() {
        val clashed = made(eFold.move(dentist, at(16)))
        world.clock.nowMs += 8_000
        answer(
            clashed, EventEditFields.STATUS to "CLASH".fv(),
            EventEditFields.THEIR_TITLE to "Dentist".fv(), EventEditFields.THEIR_START to at(13).fv(), EventEditFields.THEIR_END to at(14).fv(),
            EventEditFields.THEIR_ALL_DAY to false.fv(),
        )
        sync()
        val resend = made(eFold.keepMine(clashed))
        val all = rows(logFold)
        assertEquals("Kept your version of “Dentist” · sent again", all.single { it.id == ActivityRules.calendarEditId(clashed) }.summary)
        val again = all.single { it.id == ActivityRules.calendarEditId(resend) }
        assertEquals("Moving “Dentist” in Google", again.summary)
        assertEquals("Fri 9 Oct · 13:00–14:00 → 16:00–17:00", again.detail)
        assertEquals("Why: Your choice after a clash · editing allowed for meka@gmail.com", again.why)
    }

    @Test
    fun calendarEditsSitAmongMekasOwnEntriesNewestFirst() {
        logFold.recordPosted(listOf(Notice("k1", NoticeSource.RENEWAL_CANCEL_BY, NoticeTier.HEADS_UP, "Cancel or keep Netflix?", "Cancel by tomorrow", now, NoticeTarget.LISTS)))
        world.clock.nowMs += 60_000
        made(eFold.move(dentist, at(16)))
        val rows = logFold.view().days.single().rows
        assertEquals(listOf(ActivityKind.CALENDAR, ActivityKind.REMINDED), rows.map { it.kind })
        assertEquals("This week: 1 reminder · 1 calendar edit", logFold.view().weekLine)
    }
}
