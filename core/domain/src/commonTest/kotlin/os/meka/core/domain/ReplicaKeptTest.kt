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
 * Taps feel instant, slice 5 (2026-10-25): the replica keeps each type's entities, and what was read from them, until
 * a write to that type, so a tap on a task no longer re-reads every email eight times a refresh.
 */
class ReplicaKeptTest {
    private var n = 0
    private fun replica(name: String) =
        Replica("hh", name, HlcClock(name, { 1_000L }), InMemoryReplicaStore(), MekaSchema) { name + n++ }

    private fun title(text: String) = mapOf("title" to FieldValue.Text(text))

    @Test
    fun aTypeIsReadOnceUntilItIsWritten() {
        val r = replica("a")
        r.commitLocal(EntityTypes.MAIL_ITEM, "m1", title("Your parcel"))
        val first = r.entities(EntityTypes.MAIL_ITEM)
        // A tap on a task (another type) keeps the emails as they were read.
        r.commitLocal("task", "t1", title("Ring the garage"))
        assertTrue(first === r.entities(EntityTypes.MAIL_ITEM))
        // A new email is read at once.
        r.commitLocal(EntityTypes.MAIL_ITEM, "m2", title("Dentist on Friday"))
        val second = r.entities(EntityTypes.MAIL_ITEM)
        assertTrue(first !== second)
        assertEquals(setOf("m1", "m2"), second.map { it.ref.entityId }.toSet())
        // Deleted ones stay out of the usual list and in the full one.
        r.commitLocal(EntityTypes.MAIL_ITEM, "m1", mapOf("deleted" to FieldValue.Bool(true)))
        assertEquals(listOf("m2"), r.entities(EntityTypes.MAIL_ITEM).map { it.ref.entityId })
        assertEquals(2, r.entities(EntityTypes.MAIL_ITEM, includeDeleted = true).size)
    }

    @Test
    fun anotherDevicesChangeIsReadAtOnce() {
        val a = replica("a")
        val b = replica("b")
        b.commitLocal("task", "t1", title("Book the MOT"))
        assertEquals(listOf("Book the MOT"), b.entities("task").map { it["title"].textOrNull })
        b.applyRemoteBatch(a.commitLocal("task", "t2", title("Pay the car tax")))
        assertEquals(setOf("Book the MOT", "Pay the car tax"), b.entities("task").map { it["title"].textOrNull }.toSet())
    }

    @Test
    fun aRolledBackBatchLeavesNothingBehind() {
        val r = replica("a")
        r.commitLocal("task", "t1", title("One"))
        assertEquals(1, r.entities("task").size)
        assertFailsWith<IllegalStateException> {
            r.batched {
                r.commitLocal("task", "t2", title("Two"))
                // Read inside the batch: sees its own write…
                assertEquals(2, r.entities("task").size)
                error("the statement import failed half way")
            }
        }
        // …but once rolled back, the kept read is gone too.
        assertEquals(listOf("t1"), r.entities("task").map { it.ref.entityId })
    }

    @Test
    fun aReadingIsDoneOncePerChange() {
        val r = replica("a")
        r.commitLocal(EntityTypes.MAIL_ITEM, "m1", title("Your parcel"))
        var reads = 0
        val decode = { l: List<os.meka.core.sync.EntitySnapshot> -> reads++; l.map { it.ref.entityId } }
        repeat(8) { r.decoded(EntityTypes.MAIL_ITEM, "ids", decode) }
        assertEquals(1, reads)
        r.commitLocal("task", "t1", title("Ring the garage"))
        assertEquals(listOf("m1"), r.decoded(EntityTypes.MAIL_ITEM, "ids", decode))
        assertEquals(1, reads)
        r.commitLocal(EntityTypes.MAIL_ITEM, "m2", title("Dentist"))
        assertTrue(r.decoded(EntityTypes.MAIL_ITEM, "ids", decode).containsAll(listOf("m1", "m2")))
        assertEquals(2, reads)
    }
}
