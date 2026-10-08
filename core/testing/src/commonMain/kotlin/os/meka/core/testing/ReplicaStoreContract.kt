package os.meka.core.testing

import os.meka.core.sync.EntityRef
import os.meka.core.sync.FieldKey
import os.meka.core.sync.FieldState
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.Op
import os.meka.core.sync.ReplicaStore

/**
 * Behavioural contract every [ReplicaStore] must satisfy (ADR-002). Call [verify] from each implementation's tests.
 * Throws AssertionError with a description on the first violation.
 */
object ReplicaStoreContract {
    fun verify(newStore: () -> ReplicaStore) {
        roundTripsEveryValueType(newStore())
        fieldStateReplacesHeadsAndAccumulatesSuperseded(newStore())
        pendingQueueOrderingAndAck(newStore())
        metaPersists(newStore())
        localValuesPersistApartFromMeta(newStore())
        transactionRollsBack(newStore())
    }

    private fun op(id: String, value: FieldValue, wall: Long, base: List<String> = emptyList(), field: String = "title") =
        Op(id, "hh", "task", "t1", field, value, Hlc(wall, 0, "dev"), base, "dev")

    private fun check(cond: Boolean, msg: String) { if (!cond) throw AssertionError("ReplicaStoreContract: $msg") }

    private fun roundTripsEveryValueType(s: ReplicaStore) {
        val values = listOf(FieldValue.Text("héllo, \"x\""), FieldValue.Int64(-42), FieldValue.Bool(true), FieldValue.Bool(false), FieldValue.Null)
        s.transaction {
            values.forEachIndexed { i, v -> s.appendOp(op("o$i", v, 10L + i, listOf("a", "b")), local = false) }
        }
        values.forEachIndexed { i, v ->
            check(s.op("o$i") == op("o$i", v, 10L + i, listOf("a", "b")), "op o$i round trip ($v)")
        }
        check(s.op("missing") == null, "missing op is null")
    }

    private fun fieldStateReplacesHeadsAndAccumulatesSuperseded(s: ReplicaStore) {
        val key = FieldKey("task", "t1", "title")
        val a = op("a", FieldValue.Text("A"), 1)
        val b = op("b", FieldValue.Text("B"), 2)
        val c = op("c", FieldValue.Text("C"), 3, listOf("a"))
        s.transaction {
            listOf(a, b, c).forEach { s.appendOp(it, local = false) }
            s.setFieldState(key, FieldState.EMPTY.add(a).add(b))
            s.setFieldState(key, s.fieldState(key).add(c))
        }
        val st = s.fieldState(key)
        check(st.heads.map { it.opId } == listOf("b", "c"), "heads replaced and ordered by hlc, got ${st.heads.map { it.opId }}")
        check(st.superseded == setOf("a"), "superseded accumulated, got ${st.superseded}")
        check(s.fieldHeads(EntityRef("task", "t1")).keys == setOf("title"), "fieldHeads lists fields")
        check(s.entityIds("task") == listOf("t1"), "entityIds")
        check(s.entityIds("person").isEmpty(), "entityIds for other type is empty")
    }

    private fun pendingQueueOrderingAndAck(s: ReplicaStore) {
        s.transaction {
            s.appendOp(op("l2", FieldValue.Null, 20), local = true)
            s.appendOp(op("l1", FieldValue.Null, 10), local = true)
            s.appendOp(op("r1", FieldValue.Null, 5), local = false)
        }
        check(s.pendingCount() == 2, "only local ops are pending")
        val pending = s.pendingPush(10).map { it.opId }
        check(pending.toSet() == setOf("l1", "l2"), "pending contains local ops")
        check(s.pendingPush(1).size == 1, "limit respected")
        s.transaction { s.markPushed(listOf("l1")) }
        check(s.pendingPush(10).map { it.opId } == listOf("l2"), "acked op leaves queue")
    }

    private fun metaPersists(s: ReplicaStore) {
        check(s.pullCursor() == 0L, "cursor starts at 0")
        check(s.clockHighWater() == Hlc.ZERO, "clock starts at ZERO")
        s.transaction { s.setPullCursor(99); s.setClockHighWater(Hlc(5, 1, "dev")) }
        check(s.pullCursor() == 99L, "cursor persists")
        check(s.clockHighWater() == Hlc(5, 1, "dev"), "clock persists")
    }

    private fun localValuesPersistApartFromMeta(s: ReplicaStore) {
        check(s.localValue("plan.follow") == null, "a local value starts absent")
        s.transaction { s.setLocalValue("plan.follow", "[{\"t\":\"héllo\\n\"}]"); s.setLocalValue("pull_cursor", "x") }
        check(s.localValue("plan.follow") == "[{\"t\":\"héllo\\n\"}]", "a local value round trips")
        check(s.pullCursor() == 0L, "a local value never touches the cursor")
        s.transaction { s.setLocalValue("plan.follow", "b") }
        check(s.localValue("plan.follow") == "b", "a local value is replaced")
        s.transaction { s.setLocalValue("plan.follow", null) }
        check(s.localValue("plan.follow") == null, "null removes a local value")
        check(s.localValue("pull_cursor") == "x", "removing one leaves the others")
        try {
            s.transaction { s.setLocalValue("rolled", "v"); throw IllegalStateException("boom") }
        } catch (_: IllegalStateException) {
        }
        check(s.localValue("rolled") == null, "a rolled back local value is absent")
        check(s.pendingCount() == 0, "local values are never queued for push")
    }

    private fun transactionRollsBack(s: ReplicaStore) {
        try {
            s.transaction {
                s.appendOp(op("x", FieldValue.Null, 1), local = true)
                s.setPullCursor(7)
                throw IllegalStateException("boom")
            }
        } catch (_: IllegalStateException) {
        }
        check(s.op("x") == null, "rolled back op is absent")
        check(s.pendingCount() == 0, "rolled back op not pending")
        check(s.pullCursor() == 0L, "rolled back cursor unchanged")
    }
}
