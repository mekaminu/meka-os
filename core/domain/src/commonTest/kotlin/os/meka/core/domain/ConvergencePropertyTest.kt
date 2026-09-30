package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Op
import os.meka.core.sync.Replica
import os.meka.core.sync.fv
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Property: any delivery order of the same op set yields identical entity state and identical conflicts.
 * Devices edit concurrently with partial, random knowledge of each other's ops, which exercises causal chains,
 * concurrent heads, terminal/delete policies and late arrivals.
 */
class ConvergencePropertyTest {
    private val fields = listOf(
        ActionableFields.TITLE, ActionableFields.NOTES, ActionableFields.LIFECYCLE, ActionableFields.DUE_AT,
        ActionableFields.PRIORITY, ActionableFields.DELETED,
    )

    private fun randomValue(field: String, r: Random): FieldValue = when (field) {
        ActionableFields.LIFECYCLE -> listOf("ACTIVE", "DONE", "SOMEDAY", "CANCELLED").random(r).fv()
        ActionableFields.DELETED -> r.nextBoolean().fv()
        ActionableFields.DUE_AT, ActionableFields.PRIORITY -> (r.nextInt(5).toLong()).fv()
        else -> listOf("a", "b", "c").random(r).fv()
    }

    private fun replica(name: String, time: () -> Long, r: Random) =
        Replica("hh", name, HlcClock(name, time), InMemoryReplicaStore(), MekaSchema) { "op-" + r.nextLong().toString(16) }

    @Test
    fun anyDeliveryOrderConverges() {
        repeat(200) { seed ->
            val r = Random(seed)
            var t = 1_000L
            val names = listOf("android", "mac", "server")
            val devices = names.map { n -> replica(n, { t + r.nextLong(-50, 50) }, r) }
            val authored = mutableListOf<Op>()

            repeat(30) {
                t += r.nextLong(0, 20)
                val d = devices.random(r)
                // Partial gossip: this device has seen a random subset of others' ops before editing.
                authored.filter { r.nextInt(3) == 0 }.forEach { d.applyRemote(it) }
                val entity = listOf("t1", "t2").random(r)
                val f = fields.random(r)
                authored += d.commitLocal(EntityTypes.TASK, entity, mapOf(f to randomValue(f, r)))
            }

            val reference = replica("ref", { t }, r).also { rep -> authored.forEach { rep.applyRemote(it) } }
            repeat(5) {
                val other = replica("o$it", { t }, r)
                authored.shuffled(r).forEach { op -> other.applyRemote(op) }
                // Duplicates must be harmless.
                authored.shuffled(r).take(10).forEach { op -> other.applyRemote(op) }
                for (id in listOf("t1", "t2")) {
                    assertEquals(reference.entity(EntityTypes.TASK, id), other.entity(EntityTypes.TASK, id), "seed $seed entity $id")
                    assertEquals(
                        reference.conflictsFor(EntityTypes.TASK, id).map { it.key to it.winning.opId }.toSet(),
                        other.conflictsFor(EntityTypes.TASK, id).map { it.key to it.winning.opId }.toSet(),
                        "seed $seed conflicts $id",
                    )
                }
            }
        }
    }
}
