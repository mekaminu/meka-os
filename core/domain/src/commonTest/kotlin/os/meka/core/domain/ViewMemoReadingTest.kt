package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Taps feel instant, slice 8: a part kept over what it read through the replica. Rebuilt after a write to any type it
 * read (this device's, another device's, or a rolled-back batch), kept after writes to other types and after a commit
 * that changed nothing.
 */
class ViewMemoReadingTest {
    private var n = 0
    private fun replica(name: String) =
        Replica("hh", name, HlcClock(name, { 1_000L }), InMemoryReplicaStore(), MekaSchema) { name + n++ }

    private fun title(text: String) = mapOf("title" to FieldValue.Text(text))

    private fun habitsAndTasks(r: Replica): Int = r.entities(EntityTypes.HABIT).size + (r.entity("task", "t1")?.let { 1 } ?: 0)

    @Test
    fun keptUntilATypeItReadIsWritten() {
        val r = replica("a")
        val memo = ViewMemo()
        r.commitLocal(EntityTypes.HABIT, "h1", title("Stretch"))
        assertEquals(1, memo.keepReading("Review", r, listOf(1L)) { habitsAndTasks(r) })

        // An email is another type: kept.
        r.commitLocal(EntityTypes.MAIL_ITEM, "m1", title("Your parcel"))
        assertEquals(1, memo.keepReading("Review", r, listOf(1L)) { habitsAndTasks(r) })
        assertEquals(1, memo.builds["Review"])

        // A commit that changed nothing: kept.
        r.commitLocal(EntityTypes.HABIT, "h1", title("Stretch"))
        memo.keepReading("Review", r, listOf(1L)) { habitsAndTasks(r) }
        assertEquals(1, memo.builds["Review"])

        // A habit (read as a list) or the one task (read by id): rebuilt.
        r.commitLocal(EntityTypes.HABIT, "h2", title("Read"))
        assertEquals(2, memo.keepReading("Review", r, listOf(1L)) { habitsAndTasks(r) })
        r.commitLocal("task", "t1", title("Ring the garage"))
        assertEquals(3, memo.keepReading("Review", r, listOf(1L)) { habitsAndTasks(r) })
        assertEquals(3, memo.builds["Review"])

        // A new input (the minute): rebuilt.
        memo.keepReading("Review", r, listOf(2L)) { habitsAndTasks(r) }
        assertEquals(4, memo.builds["Review"])
    }

    @Test
    fun anotherDevicesWriteAndARollbackRebuild() {
        val a = replica("a")
        val b = replica("b")
        val memo = ViewMemo()
        memo.keepReading("Balance", b, emptyList()) { b.entities(EntityTypes.HABIT).size }
        val ops = a.commitLocal(EntityTypes.HABIT, "h1", title("Stretch"))
        b.applyRemoteBatch(ops)
        assertEquals(1, memo.keepReading("Balance", b, emptyList()) { b.entities(EntityTypes.HABIT).size })

        // The same ops again (a duplicate pull) change nothing: kept.
        b.applyRemoteBatch(ops)
        memo.keepReading("Balance", b, emptyList()) { b.entities(EntityTypes.HABIT).size }
        assertEquals(2, memo.builds["Balance"])

        assertFailsWith<IllegalStateException> {
            b.batched { b.commitLocal(EntityTypes.HABIT, "h2", title("Read")); error("rolled back") }
        }
        memo.keepReading("Balance", b, emptyList()) { b.entities(EntityTypes.HABIT).size }
        assertEquals(3, memo.builds["Balance"])
    }

    @Test
    fun aKeptPartStillTellsAnOuterOneWhatItRead() {
        val r = replica("a")
        val memo = ViewMemo()
        val inner = { memo.keepReading("Inner", r, emptyList()) { r.entities(EntityTypes.HABIT).size } }
        inner()
        // The outer part reads the habits only through the kept inner one.
        memo.keepReading("Outer", r, emptyList()) { inner() }
        r.commitLocal(EntityTypes.HABIT, "h1", title("Stretch"))
        assertEquals(1, memo.keepReading("Outer", r, emptyList()) { inner() })
        assertEquals(2, memo.builds["Outer"])
        val (_, types) = r.tracking { inner() }
        assertTrue(EntityTypes.HABIT in types)
    }
}
