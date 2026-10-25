package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Taps feel instant, slice 6 (2026-10-25): parts of the views kept while what they're built from is unchanged. */
class ViewMemoTest {
    @Test
    fun aPartIsBuiltOncePerChangeOfItsInputs() {
        val m = ViewMemo()
        var built = 0
        val emails = listOf("a", "b", "c")
        val first = m.keep("Mail needs you", listOf(emails, 10L)) { built++; emails.size.toString() }
        // The same list (the replica hands out the same one until a write) and the same minute: kept.
        repeat(5) { assertTrue(first === m.keep("Mail needs you", listOf(emails, 10L)) { built++; "x" }) }
        assertEquals(1, built)
        // An equal list made afresh is still the same input.
        m.keep("Mail needs you", listOf(listOf("a", "b", "c"), 10L)) { built++; "x" }
        assertEquals(1, built)
        // A new minute or a new email builds again.
        assertEquals("y", m.keep("Mail needs you", listOf(emails, 11L)) { built++; "y" })
        assertEquals("z", m.keep("Mail needs you", listOf(emails + "d", 11L)) { built++; "z" })
        assertEquals(3, built)
        assertEquals(3, m.builds["Mail needs you"])
    }

    @Test
    fun partsAreKeptApartAndNullIsAValue() {
        val m = ViewMemo()
        var built = 0
        assertEquals(null, m.keep<String?>("Archived for you", listOf(1)) { built++; null })
        assertEquals(null, m.keep<String?>("Archived for you", listOf(1)) { built++; "not again" })
        assertEquals("other", m.keep("Not opened", listOf(1)) { built++; "other" })
        assertEquals(2, built)
        m.clear()
        assertEquals("again", m.keep<String?>("Archived for you", listOf(1)) { built++; "again" })
        assertEquals(3, built)
    }

    @Test
    fun theMinute() {
        assertEquals(ViewMemo.minute(120_000L), ViewMemo.minute(179_999L))
        assertTrue(ViewMemo.minute(180_000L) > ViewMemo.minute(179_999L))
    }

    @Test
    fun theActivityLogReadsItsEntriesOncePerChange() {
        var n = 0
        val r = Replica("hh", "a", HlcClock("a", { 1_000L }), InMemoryReplicaStore(), MekaSchema) { "id" + n++ }
        var now = 1_000L
        val log = ActivityLog(r, { "act" + n++ }, { now })
        log.actRecorded("Moved the dentist", "You asked", "test", "AUTO") {
            r.commitLocal("task", "t1", mapOf("title" to FieldValue.Text("Dentist")))
        }
        val first = log.items()
        assertEquals(1, first.size)
        // A write to another type keeps what was read from the log.
        r.commitLocal(EntityTypes.MAIL_SENDER, "s1", mapOf("name" to FieldValue.Text("News")))
        val again = log.items()
        assertEquals(first, again)
        assertTrue(first[0] === again[0], "the entry was read once")
        // A new entry is read at once.
        now += 1_000
        log.actRecorded("Added milk", "You asked", "test", "AUTO") {
            r.commitLocal("task", "t2", mapOf("title" to FieldValue.Text("Milk")))
        }
        assertEquals(2, log.items().size)
    }
}
