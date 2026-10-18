package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edit your calendars, completeness slice 1: a repeating event is changed or deleted as this one or every one. */
class RepeatingEventEditTest {
    private val hour = 3_600_000L
    private val thu = CivilDate.toEpochDay(2026, 10, 8)
    private val bst = LocalCalendar.fixedOffset(hour)
    private val now = bst.toEpochMs(thu, 10 * 60)

    private fun gym(provider: String = "google", series: String? = "srabc", allDay: Boolean = false): CalendarEvent {
        val start = if (allDay) (thu + 1) * 86_400_000L else bst.toEpochMs(thu + 1, 18 * 60)
        val end = if (allDay) start + 86_400_000L else start + hour
        return CalendarEvent("ev1", "Gym", start, end, allDay, "Leisure centre", provider, "meka@example.com", "Personal", series = series)
    }

    @Test
    fun aRepeatingEventAsksThisOneOrEveryOne() {
        val e = gym()
        val f = EditEventRules.start(e, now, bst)
        assertFalse(f.every)
        val v = EditEventRules.view(f, e, canEdit = true, now, bst)
        assertEquals(listOf("This event", "Every event"), v.scopes.map { it.label })
        assertEquals(listOf(true, false), v.scopes.map { it.selected })
        assertEquals("Delete this event from Google", v.deleteLabel)
        assertEquals("It repeats · only this one changes", v.scopeNote)

        val every = EditEventRules.view(f.withEvery(true).withTitle("Swim"), e, true, now, bst)
        assertEquals(listOf(false, true), every.scopes.map { it.selected })
        assertEquals("Delete every event from Google", every.deleteLabel)
        assertTrue(every.canAdd)
        assertEquals("Deleting every “Gym” from Google", EditEventRules.deletingLine(e, every = true))

        // A one-off event asks nothing.
        val one = gym(series = null)
        val plain = EditEventRules.view(EditEventRules.start(one, now, bst), one, true, now, bst)
        assertTrue(plain.scopes.isEmpty())
        assertNull(plain.scopeNote)
        assertEquals("Delete from Google", plain.deleteLabel)
        assertEquals("Deleting “Gym” from Google", EditEventRules.deletingLine(one, every = true))
    }

    @Test
    fun everyEventKeepsItsDay() {
        val e = gym()
        val f = EditEventRules.start(e, now, bst).withEvery(true)
        // A new time of day is fine for every event…
        val later = EditEventRules.view(f.stepTime(2), e, true, now, bst)
        assertNull(later.problem)
        assertTrue(later.canAdd)
        // …another day is only for this one.
        val friday = EditEventRules.view(f.withDay(thu + 2), e, true, now, bst)
        assertEquals("Every event keeps its own day · choose This event to move just this one", friday.problem)
        assertFalse(friday.canAdd)
        assertNull(EditEventRules.view(f.withDay(thu + 2).withEvery(false), e, true, now, bst).problem)
        // An all-day series, and Outlook's series, keep their time.
        assertEquals(
            "Change every event's time in Outlook · or choose This event",
            EditEventRules.view(EditEventRules.start(gym("microsoft"), now, bst).withEvery(true).stepTime(2), gym("microsoft"), true, now, bst).problem,
        )
        val allDay = gym(allDay = true)
        assertEquals(
            "An all-day series keeps its days · change it in Google, or choose This event",
            EditEventRules.view(EditEventRules.start(allDay, now, bst).withEvery(true).withAllDay(false), allDay, true, now, bst).problem,
        )
    }

    @Test
    fun theScopeIsSyncedWithTheEditAndReadBack() {
        val world = os.meka.core.testing.SyncWorld()
        val fold = world.device("fold")
        var n = 0
        val edits = CalendarEdits(fold.replica, { world.clock.nowMs }, { "edit${n++}" }) { _, _ -> true }
        val e = gym()
        val id = (edits.change(e, CalendarEditRules.draftOf(e).copy(title = "Swim"), every = true, calendar = bst) as EventEditResult.Made).id
        assertTrue(edits.edit(id)!!.every)
        val del = (edits.delete(e, every = true) as EventEditResult.Made).id
        assertTrue(edits.edit(del)!!.every)
        val one = (edits.delete(e) as EventEditResult.Made).id
        assertFalse(edits.edit(one)!!.every)
        // Every event of a one-off is refused.
        assertTrue(edits.delete(gym(series = null), every = true) is EventEditResult.Refused)
        assertTrue(
            edits.change(e, CalendarEditRules.draftOf(e).let { it.copy(startAtMs = it.startAtMs + 86_400_000L, endAtMs = it.endAtMs + 86_400_000L) }, every = true, calendar = bst)
                is EventEditResult.Refused,
        )
    }
}
