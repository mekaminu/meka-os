package os.meka.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MergeTest {
    private fun op(id: String, value: FieldValue, hlc: Long, vararg base: String, node: String = "n") = Op(
        opId = id, householdId = "h", entityType = "task", entityId = "t1", field = "f", value = value,
        hlc = Hlc(hlc, 0, node), baseOpIds = base.toList(), deviceId = node,
    )

    private fun stateOf(ops: List<Op>) = ops.fold(FieldState.EMPTY) { s, o -> s.add(o) }
    private fun headsOf(ops: List<Op>) = stateOf(ops).heads

    private fun <T> permutations(xs: List<T>): List<List<T>> =
        if (xs.size <= 1) listOf(xs) else xs.flatMap { x -> permutations(xs - x).map { listOf(x) + it } }

    @Test
    fun causalChainLeavesOneHead() {
        val a = op("a", "1".fv(), 5)
        val b = op("b", "2".fv(), 7, "a")
        assertEquals(listOf("b"), headsOf(listOf(a, b)).map { it.opId })
        assertEquals(listOf("b"), headsOf(listOf(b, a)).map { it.opId }) // late arrival of a superseded op
    }

    @Test
    fun headsAreOrderIndependentAcrossAllPermutations() {
        val a = op("a", "A".fv(), 5, node = "x")
        val b = op("b", "B".fv(), 6, node = "y")
        val c = op("c", "C".fv(), 7, "a", node = "x") // saw a, not b
        val d = op("d", "D".fv(), 3, node = "z") // concurrent, older timestamp
        val expected = stateOf(listOf(a, b, c, d))
        assertEquals(listOf("d", "b", "c"), expected.heads.map { it.opId })
        permutations(listOf(a, b, c, d)).forEach { assertEquals(expected, stateOf(it)) }
    }

    @Test
    fun laterTimestampDoesNotImplyCausality() {
        // Regression: phone completes offline at t=5. Mac completes at t=6 then reopens at t=7 having seen only its
        // own completion. The reopen must not be treated as having seen the phone's completion.
        val phoneDone = op("phoneDone", "DONE".fv(), 5, "create", node = "phone")
        val macDone = op("macDone", "DONE".fv(), 6, "create", node = "mac")
        val macReopen = op("macReopen", "ACTIVE".fv(), 7, "macDone", node = "mac")
        val heads = headsOf(listOf(phoneDone, macDone, macReopen))
        assertEquals(setOf("phoneDone", "macReopen"), heads.map { it.opId }.toSet())
        assertEquals("phoneDone", Merge.winner(heads, MergePolicy.TerminalWins(setOf("DONE".fv())))!!.opId)
    }

    @Test
    fun olderConcurrentTitleEditStillRaisesConflict() {
        val a = op("a", "Call school".fv(), 5, "orig", node = "phone")
        val m1 = op("m1", "Email school".fv(), 6, "orig", node = "mac")
        val m2 = op("m2", "Email the school".fv(), 9, "m1", node = "mac")
        val c = assertNotNull(Merge.conflict(headsOf(listOf(a, m1, m2)), MergePolicy.UserVisible))
        assertEquals("m2", c.winning.opId)
        assertEquals(listOf("a"), c.competing.map { it.opId })
    }

    @Test
    fun identicalConcurrentValuesAreNotAConflict() {
        val heads = headsOf(listOf(op("a", "Same".fv(), 5, node = "x"), op("b", "Same".fv(), 6, node = "y")))
        assertNull(Merge.conflict(heads, MergePolicy.UserVisible))
    }

    @Test
    fun lwwNeverReportsConflicts() {
        val heads = headsOf(listOf(op("a", "x".fv(), 5, node = "x"), op("b", "y".fv(), 6, node = "y")))
        assertNull(Merge.conflict(heads, MergePolicy.Lww))
        assertEquals("b", Merge.winner(heads, MergePolicy.Lww)!!.opId)
    }

    @Test
    fun terminalWinsOverConcurrentReopenButNotOverCausalReopen() {
        val policy = MergePolicy.TerminalWins(setOf("DONE".fv()))
        val done = op("done", "DONE".fv(), 5, node = "x")
        assertEquals("done", Merge.winner(headsOf(listOf(done, op("reopen", "ACTIVE".fv(), 9, node = "y"))), policy)!!.opId)
        assertEquals("reopen2", Merge.winner(headsOf(listOf(done, op("reopen2", "ACTIVE".fv(), 9, "done", node = "y"))), policy)!!.opId)
    }

    @Test
    fun deleteBeatsConcurrentUndeleteButRestoreAfterSeeingDeleteWins() {
        val del = op("del", true.fv(), 5, node = "x")
        assertEquals(true.fv(), Merge.winner(headsOf(listOf(del, op("f", false.fv(), 8, node = "y"))), MergePolicy.TrueWins)!!.value)
        assertEquals(false.fv(), Merge.winner(headsOf(listOf(del, op("r", false.fv(), 8, "del", node = "y"))), MergePolicy.TrueWins)!!.value)
    }

    @Test
    fun resolutionOpSupersedesAllHeads() {
        val s = stateOf(listOf(op("a", "A".fv(), 5, node = "x"), op("b", "B".fv(), 6, node = "y")))
        val resolve = op("r", "A".fv(), 10, *Merge.baseFor(s.heads).toTypedArray(), node = "y")
        assertEquals(listOf("r"), s.add(resolve).heads.map { it.opId })
    }

    @Test
    fun invalidOpsAreDetected() {
        assertEquals("op cannot supersede itself", op("a", "x".fv(), 5, "a").validationError())
        assertNull(op("a", "x".fv(), 6, "b").validationError())
    }
}
