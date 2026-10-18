package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edit your calendars, completeness slice 7: the Mac's drag to move and keyboard shortcuts. */
class EventMoveTest {
    private val day = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    private val today = 20_400L
    private val now = today * day + 10 * 60 * 60_000L // 10:00
    private val canEdit: (String, String) -> Boolean = { p, a -> p == "google" && a == "alex@example.com" }

    private fun ms(d: Long, minute: Int) = d * day + minute * 60_000L
    private fun event(
        id: String, d: Long, from: Int, to: Int, account: String = "alex@example.com",
        guests: List<String> = emptyList(), readOnly: Boolean = false,
    ) = CalendarEvent(id, "Dentist", ms(d, from), ms(d, to), false, null, "google", account, "Personal", guests = guests, readOnly = readOnly)

    @Test
    fun onlyHisOwnEventsWithNoGuestsThatHaventStartedMove() {
        assertTrue(EventMoveRules.movable(event("a", today, 15 * 60, 16 * 60), now, cal, canEdit))
        assertEquals(EventMoveRules.GUESTS_NOTE, EventMoveRules.whyNot(event("b", today, 15 * 60, 16 * 60, guests = listOf("Sam")), now, cal, canEdit))
        assertEquals(EditEventRules.TIME_NOTE, EventMoveRules.whyNot(event("c", today, 9 * 60, 11 * 60), now, cal, canEdit))
        assertTrue(EventMoveRules.whyNot(event("d", today, 15 * 60, 16 * 60, readOnly = true), now, cal, canEdit)!!.startsWith("View only"))
        assertFalse(EventMoveRules.movable(event("e", today, 15 * 60, 16 * 60, account = "work@example.com"), now, cal, canEdit))
        // All day: today or later and one day long.
        val away = CalendarEvent("f", "Away", (today + 1) * day, (today + 2) * day, true, null, "google", "alex@example.com", "Personal")
        assertTrue(EventMoveRules.movable(away, now, cal, canEdit))
        assertEquals(EventMoveRules.SPAN_NOTE, EventMoveRules.whyNot(away.copy(endAtMs = (today + 4) * day), now, cal, canEdit))
    }

    @Test
    fun aDropKeepsTheTimeAndArrowsCarryAcrossMidnight() {
        val e = event("a", today, 23 * 60 + 50, 24 * 60 + 20)
        val opened = EditEventRules.start(e, now, cal)
        val dropped = EventMoveRules.toDay(opened, today + 3)
        assertEquals(today + 3, dropped.day)
        assertEquals(23 * 60 + 50, dropped.minute)
        assertEquals(opened.lengthMin, dropped.lengthMin)
        val later = EventMoveRules.by(opened, 0, EventMoveRules.NUDGE_MIN)
        assertEquals(today + 1, later.day)
        assertEquals(5, later.minute)
        val back = EventMoveRules.by(EventMoveRules.by(opened, 1, 0), 0, -24 * 60)
        assertEquals(opened.day, back.day)
        assertEquals(opened.minute, back.minute)
        assertEquals("Move to " + TaskWhenRules.label(today + 3, 23 * 60 + 50, today), EventMoveRules.dropHint(opened, today + 3, today))
    }

    @Test
    fun refusedInWordsWhenItWouldNotBeAMove() {
        val opened = EditEventRules.start(event("a", today, 10 * 60 + 10, 11 * 60), now, cal)
        assertEquals(EventMoveRules.SAME, EventMoveRules.refusal(EventMoveRules.toDay(opened, today), opened, now, cal))
        assertEquals(EventMoveRules.GONE, EventMoveRules.refusal(EventMoveRules.by(opened, 0, -15), opened, now, cal))
        assertEquals(EventMoveRules.TOO_FAR, EventMoveRules.refusal(EventMoveRules.toDay(opened, today + 800), opened, now, cal))
        assertNull(EventMoveRules.refusal(EventMoveRules.by(opened, 1, 0), opened, now, cal))
        // An all-day event moves by whole days; yesterday has gone.
        val away = CalendarEvent("f", "Away", (today + 1) * day, (today + 2) * day, true, null, "google", "alex@example.com", "Personal")
        val o = EditEventRules.start(away, now, cal)
        assertEquals(today + 2, EventMoveRules.by(o, 1, 15).day)
        assertEquals(EventMoveRules.DAY_GONE, EventMoveRules.refusal(EventMoveRules.toDay(o, today - 1), o, now, cal))
        assertNull(EventMoveRules.refusal(EventMoveRules.toDay(o, today), o, now, cal))
    }
}
