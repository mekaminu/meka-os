package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edit your calendars, completeness slice 3: a view-only calendar's events grey Edit out and say why. */
class ReadOnlyCalendarTest {
    private val hour = 3_600_000L
    private val thu = CivilDate.toEpochDay(2026, 10, 8)
    private val bst = LocalCalendar.fixedOffset(hour)
    private val now = bst.toEpochMs(thu, 10 * 60)
    private val allowed: (String, String) -> Boolean = { p, _ -> p == "google" }

    private fun event(readOnly: Boolean, calendar: String? = "Holidays in the UK", provider: String = "google") =
        CalendarEvent(
            "ev1", "Bank holiday", bst.toEpochMs(thu + 1, 9 * 60), bst.toEpochMs(thu + 1, 10 * 60), false, null,
            provider, "alex@example.com", calendar, readOnly = readOnly,
        )

    @Test
    fun aViewOnlyCalendarIsNeverEditableAndSaysWhy() {
        val ro = event(readOnly = true)
        assertFalse(CalendarEditRules.editable(ro, allowed))
        assertEquals(
            "View only · “Holidays in the UK” is shared with you to see, not change, so Navilife can't edit it · its owner changes it in Google",
            CalendarEditRules.notEditableReason(ro, allowed),
        )
        // Allowing editing on the account wouldn't help, so the view-only reason comes first even when it's off.
        assertTrue(CalendarEditRules.notEditableReason(ro) { _, _ -> false }!!.startsWith("View only"))
        // A calendar named after the account (the primary) reads "This calendar".
        assertTrue(CalendarEditRules.notEditableReason(event(true, calendar = "alex@example.com"), allowed)!!.startsWith("View only · This calendar is"))
    }

    @Test
    fun anEditableCalendarSaysNothingAndAnAccountWithEditingOffSaysHowToAllowIt() {
        val mine = event(readOnly = false, calendar = "Personal")
        assertTrue(CalendarEditRules.editable(mine, allowed))
        assertNull(CalendarEditRules.notEditableReason(mine, allowed))
        assertEquals(
            "Editing is off for alex@example.com · Allow editing in Ask → Settings → Calendars",
            CalendarEditRules.notEditableReason(mine) { _, _ -> false },
        )
        // Fixtures and anything Navilife never edits get no reason line.
        assertNull(CalendarEditRules.notEditableReason(event(false, provider = "fixtures"), allowed))
    }

    @Test
    fun theFieldIsReadFromTheMirrorAndTheEditSheetRefuses() {
        val world = SyncWorld()
        val fold = world.device("android")
        world.clock.nowMs = now
        val ro = event(readOnly = true)
        val v = EditEventRules.view(EditEventRules.start(ro, now, bst).withTitle("Renamed"), ro, canEdit = false, now, bst)
        assertTrue(v.problem!!.startsWith("View only"))
        assertFalse(v.canAdd)

        val edits = CalendarEdits(fold.replica, { world.clock.nowMs }, { "ro1" }, allowed)
        val refused = assertIs<EventEditResult.Refused>(edits.delete(ro))
        assertTrue(refused.reason.startsWith("View only"))
        assertTrue(edits.all().isEmpty())

        // An event without the field (polled before slice 3, or a calendar he can change) is not view only.
        fold.replica.commitLocal(EntityTypes.EVENT, "ev2", mapOf(
            EventFields.TITLE to "Gym".fv(), EventFields.START_AT to now.fv(), EventFields.PROVIDER to "google".fv(),
        ))
        fold.replica.commitLocal(EntityTypes.EVENT, "ev3", mapOf(
            EventFields.TITLE to "Holiday".fv(), EventFields.START_AT to now.fv(), EventFields.PROVIDER to "google".fv(),
            EventFields.READ_ONLY to true.fv(),
        ))
        val events = CalendarEvents(fold.replica).all().associateBy { it.id }
        assertFalse(events.getValue("ev2").readOnly)
        assertTrue(events.getValue("ev3").readOnly)
    }
}
