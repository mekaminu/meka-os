package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * School emails first, slice 4c: a later email that calls off or moves a school date updates what was made, and says so.
 * Invented school (rule 13).
 */
class SchoolMailChangeTest {
    /** Tue 24 Nov 2026 (Sat 28 Nov, Sun 29 Nov). */
    private val today = CivilDate.toEpochDay(2026, 11, 24)
    private val sat = today + 4
    private val sun = today + 5

    private fun find(
        id: String, what: String, day: Long, received: Long = today - 3, change: String? = null,
        state: MailFindState = MailFindState.OPEN, auto: Long? = null, replaces: String? = null,
    ) = MailFind(
        id = id, kind = SchoolMailRules.KIND_SCHOOL, account = "Personal", merchant = "Oakfield Primary", renewDay = day,
        pence = null, obligationKind = ObligationKind.SUBSCRIPTION, repeat = RenewalRepeat.NONE, receivedDay = received,
        subject = "News", state = state, decidedAtMs = 0L, what = what,
        endDay = day, schoolKind = SchoolKind.DAY.wire, schoolChange = change, autoAtMs = auto, replacesId = replaces,
    )

    @Test
    fun aSentenceSaysWhetherADateWasCalledOffOrMoved() {
        val X = SchoolChangeRules
        assertEquals(X.CANCELLED, SchoolBodyRules.change("The Christmas fair on Saturday 28 November has been cancelled."))
        assertEquals(X.CANCELLED, SchoolBodyRules.change("Sadly the disco will no longer take place"))
        assertEquals(X.CANCELLED, SchoolBodyRules.change("The trip has been called off due to the weather"))
        assertEquals(X.MOVED, SchoolBodyRules.change("The fair has been moved to Sunday 29 November"))
        assertEquals(X.MOVED, SchoolBodyRules.change("The fair will now be on Sunday 29 November"))
        assertEquals(X.MOVED, SchoolBodyRules.change("The fair was cancelled and rescheduled for Sunday 29 November"))
        assertNull(SchoolBodyRules.change("Our Christmas fair is on Saturday 28 November, 12pm-4pm."))
        assertNull(SchoolBodyRules.change("Please move your car from the school gates by 9am."))
    }

    @Test
    fun theBodyMarksACancellationAndAMoveKeepsOnlyItsNewDay() {
        val cancel = SchoolBodyRules.read(
            "Christmas fair",
            "Dear Parents,\nUnfortunately the Christmas fair on Saturday 28 November has been cancelled.\nThank you",
            today,
        )
        val c = cancel.finds.single()
        assertEquals(sat, c.find.startDay)
        assertEquals(SchoolChangeRules.CANCELLED, c.change)
        assertEquals(FieldValue.Text("cancelled"), SchoolBodyRules.findFields(c)[MailFindFields.SCHOOL_CHANGE])
        assertTrue(cancel.summary.first().value.startsWith("Cancelled · was Sat 28 Nov"), cancel.summary.toString())
        // Its own find beside the date it calls off (the server writes each find once).
        val fair = SchoolBodyRules.read("Christmas fair", "Our Christmas fair is on Saturday 28 November, 12pm-4pm.", today).finds.single()
        assertEquals(SchoolMailRules.findId("acc", fair.find), SchoolMailRules.bodyFindId("acc", fair))
        assertTrue(SchoolMailRules.bodyFindId("acc", c) != SchoolMailRules.bodyFindId("acc", fair))
        assertEquals(fair.find.title, c.find.title)

        val move = SchoolBodyRules.read(
            "Christmas fair update",
            "Dear Parents,\nThe Christmas fair has been moved from Saturday 28 November to Sunday 29 November, 12pm-4pm.\nThank you",
            today,
        )
        val m = move.finds.single()
        assertEquals(sun, m.find.startDay, move.finds.toString())
        assertEquals(SchoolChangeRules.MOVED, m.change)
        assertTrue(move.summary.first().value.startsWith("Moved to Sun 29 Nov"), move.summary.toString())

        val plain = SchoolBodyRules.read("Christmas fair", "Our Christmas fair is on Saturday 28 November, 12pm-4pm.", today).finds.single()
        assertNull(plain.change)
        assertNull(SchoolBodyRules.findFields(plain)[MailFindFields.SCHOOL_CHANGE])
    }

    @Test
    fun aChangeFindsTheDateHeHasAndNothingElse() {
        val X = SchoolChangeRules
        val had = find("a", "School fair", sat, state = MailFindState.ADDED, auto = 1L)
        val cancel = find("b", "School fair", sat, received = today, change = X.CANCELLED)
        val move = find("c", "School fair", sun, received = today, change = X.MOVED)
        assertEquals(listOf("b" to "a"), X.pending(listOf(had, cancel), today).map { it.first.id to it.second.id })
        assertEquals(listOf("c" to "a"), X.pending(listOf(had, move), today).map { it.first.id to it.second.id })
        // Not one he doesn't have, not another thing, not another day for a cancellation, not one already replaced.
        assertTrue(X.pending(listOf(had.copy(state = MailFindState.OPEN), cancel), today).isEmpty())
        assertTrue(X.pending(listOf(had, cancel.copy(what = "Trip payment")), today).isEmpty())
        assertTrue(X.pending(listOf(had, cancel.copy(renewDay = sun, endDay = sun)), today).isEmpty())
        assertTrue(X.pending(listOf(had, cancel), today, replaced = setOf("a")).isEmpty())
        // Not from an email older than his date's, and not once acted on.
        assertTrue(X.pending(listOf(had.copy(receivedDay = today + 1), cancel), today).isEmpty())
        assertTrue(X.pending(listOf(had, cancel.copy(autoAtMs = 2L)), today).isEmpty())
        // "Reminder: School fair cancelled" is the same thing.
        assertEquals(1, X.pending(listOf(had, cancel.copy(what = "Reminder: School fair cancelled")), today).size)
    }

    @Test
    fun aCancellationIsNeverAddedOrOfferedAndStopsTheEarlierDateInTheSameCheck() {
        val X = SchoolChangeRules
        val cancel = find("b", "School fair", sat, received = today, change = X.CANCELLED)
        assertFalse(SchoolAutoRules.eligible(cancel, emptyList(), today))
        assertFalse(SchoolMailRules.showable(cancel, emptyList(), today))
        // The catch-up reads the fair and its cancellation together: neither is added.
        val fair = find("a", "School fair", sat)
        assertTrue(SchoolAutoRules.due(listOf(fair, cancel), emptyList(), today, 0).isEmpty())
        // Read with its move: only the new day is added.
        val move = find("c", "School fair", sun, received = today, change = X.MOVED)
        assertEquals(listOf("c"), SchoolAutoRules.due(listOf(fair, move), emptyList(), today, 0).map { it.id })
    }

    @Test
    fun whatAChangeSaysAndTheEarlierRowGivesWay() {
        val X = SchoolChangeRules
        val cal = LocalCalendar.UTC
        val now = cal.toEpochMs(today, 10 * 60)
        val had = find("a", "School fair", sat, state = MailFindState.ADDED, auto = 1L)
        val cancel = find("b", "School fair", sat, received = today, change = X.CANCELLED, auto = 2L, replaces = "a")
        assertEquals(
            "From the school: School fair cancelled · was Sat 28 Nov · taken out of School, your calendar and 2 tasks",
            X.cancelledSummary(cancel, had, SchoolChangeRules.Removed(true, 2, true), today),
        )
        assertEquals(
            "From the school: School fair cancelled · was Sat 28 Nov · nothing was left to take out",
            X.cancelledSummary(cancel, had, SchoolChangeRules.Removed(false, 0, false), today),
        )
        val move = find("c", "School fair", sun, received = today, change = X.MOVED).copy(startMinute = 12 * 60, endMinute = 16 * 60)
        val line = X.movedSummary(move, had, SchoolAutoRules.plan(move, now, cal), now, cal)
        assertTrue(line.startsWith("From the school: School fair moved to Sun 29 Nov, 12:00–16:00 (was Sat 28 Nov) · in School"), line)

        fun entry(id: String, undone: Boolean = false) = ActivityItem(
            id = SchoolAutoRules.activityId(id), atMs = now, kind = ActivityKind.CHANGED, summary = "From the school: x", detail = null,
            why = "", source = SchoolAutoRules.SOURCE, level = null, changes = emptyList(), undoneAtMs = if (undone) now else null, undoNote = null,
        )
        val entries = mapOf(entry("a").id to entry("a"), entry("b").id to entry("b"))
        assertEquals(setOf("a"), X.replaced(listOf(had, cancel), entries))
        val rows = SchoolDoneRules.rows(listOf(had, cancel), entries, { null }, { null }, now, cal)
        assertEquals(listOf(SchoolDoneRules.rowId("b")), rows.map { it.id })
        // Undo the cancellation: the fair's own row is back.
        val undone = entries + (entry("b").id to entry("b", undone = true))
        assertTrue(X.replaced(listOf(had, cancel), undone).isEmpty())
        assertEquals(listOf(SchoolDoneRules.rowId("a")), SchoolDoneRules.rows(listOf(had, cancel), undone, { null }, { null }, now, cal).map { it.id })
    }
}
