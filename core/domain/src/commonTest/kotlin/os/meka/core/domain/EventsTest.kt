package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import os.meka.core.sync.fv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventsTest {
    private val hour = 3_600_000L
    // A London BST day: local midnight 3 Oct 2026 = 2 Oct 23:00 UTC, offset +1 h.
    private val oct3Utc = 1_790_985_600_000L // 2026-10-03T00:00:00Z
    private val day = DayWindow(startMs = oct3Utc - hour, endMs = oct3Utc + 23 * hour, utcOffsetMs = hour)

    private fun ev(id: String, start: Long, end: Long, allDay: Boolean = false, title: String = id) =
        CalendarEvent(id, title, start, end, allDay, null, "google", "me@example.com", "Personal")

    @Test
    fun timedEventsOverlappingTheLocalDayAreIncludedInStartOrder() {
        val events = listOf(
            ev("late", oct3Utc + 18 * hour, oct3Utc + 19 * hour),
            ev("early", oct3Utc + 8 * hour, oct3Utc + 9 * hour),
            ev("yesterday", oct3Utc - 5 * hour, oct3Utc - 4 * hour),       // 2 Oct 20:00 local
            ev("overnight", oct3Utc - 2 * hour, oct3Utc + hour),           // crosses local midnight: included
            ev("tomorrow", oct3Utc + 23 * hour, oct3Utc + 24 * hour),      // 4 Oct 00:00 local
        )
        val t = TodayProjection.project(emptyList(), oct3Utc + 10 * hour, day, events)
        assertEquals(listOf("overnight", "early", "late"), t.events.map { it.id })
        assertEquals(listOf("late"), t.upcomingEvents(oct3Utc + 10 * hour).map { it.id })
    }

    @Test
    fun allDayEventsMatchByCalendarDateNotUtcInstantAndComeFirst() {
        val events = listOf(
            ev("meeting", oct3Utc + 9 * hour, oct3Utc + 10 * hour),
            ev("birthday", oct3Utc, oct3Utc + 24 * hour, allDay = true),           // 3 Oct only
            ev("trip", oct3Utc - 24 * hour, oct3Utc + 48 * hour, allDay = true),   // 2–4 Oct
            ev("yesterday-all-day", oct3Utc - 24 * hour, oct3Utc, allDay = true),  // 2 Oct only: excluded
        )
        val t = TodayProjection.project(emptyList(), oct3Utc, day, events)
        assertEquals(listOf("trip", "birthday", "meeting"), t.events.map { it.id })
        assertTrue(t.upcomingEvents(oct3Utc).none { it.allDay })
    }

    @Test
    fun serverAuthoredEventsAreReadFromTheReplicaAndRemovalHidesThem() {
        val replica = Replica("hh", "server", HlcClock("server", { 1_000L }), InMemoryReplicaStore(), MekaSchema) { "op" + counter++ }
        replica.commitLocal(EntityTypes.EVENT, "ev1", mapOf(
            EventFields.TITLE to "Dentist".fv(), EventFields.START_AT to (oct3Utc + 9 * hour).fv(),
            EventFields.END_AT to (oct3Utc + 10 * hour).fv(), EventFields.ALL_DAY to false.fv(),
            EventFields.PROVIDER to "microsoft".fv(),
        ))
        val events = CalendarEvents(replica)
        assertEquals(listOf("Dentist"), events.all().map { it.title })
        replica.commitLocal(EntityTypes.EVENT, "ev1", mapOf(EventFields.REMOVED to FieldValue.Bool(true)))
        assertTrue(events.all().isEmpty())
        // Unlike task deletion, removal can be undone when the event comes back into the provider's window.
        replica.commitLocal(EntityTypes.EVENT, "ev1", mapOf(EventFields.REMOVED to FieldValue.Bool(false)))
        assertEquals(1, events.all().size)
    }

    @Test
    fun eventFieldsNeverRaiseConflicts() {
        assertEquals(os.meka.core.sync.MergePolicy.Lww, MekaSchema.policyFor(EntityTypes.EVENT, EventFields.TITLE))
        assertEquals(os.meka.core.sync.MergePolicy.Lww, MekaSchema.policyFor(EntityTypes.EVENT, EventFields.REMOVED))
    }

    private var counter = 0
}
