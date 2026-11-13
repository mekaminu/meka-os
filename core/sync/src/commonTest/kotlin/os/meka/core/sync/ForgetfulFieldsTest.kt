package os.meka.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class ForgetfulFieldsTest {
    private fun spot(id: String, wall: Long, v: FieldValue, field: String = "parkedSpot") =
        Op(id, "h", "context_mode", "car", field, v, Hlc(wall, 0, "fold"), emptyList(), "fold")

    @Test
    fun theServerKeepsOnlyTheNewestSpotAndNothingAfterDroveOff() {
        val store = InMemoryServerOpStore()
        val sync = SyncService(store)
        val a = spot("a", 1, FieldValue.Text("one"))
        val b = spot("b", 2, FieldValue.Text("two"))
        sync.push(PushRequest("h", "fold", listOf(a, b)))
        assertEquals(FieldValue.Null, store.find("h", "a")!!.value)
        assertEquals(FieldValue.Text("two"), store.find("h", "b")!!.value)
        sync.push(PushRequest("h", "fold", listOf(spot("c", 3, FieldValue.Null))))
        assertEquals(FieldValue.Null, store.find("h", "b")!!.value)
        // Pulls still carry every op, in order; a fresh device ends with nothing parked.
        val pulled = sync.pull(PullRequest("h", "watch", 0)).ops
        assertEquals(listOf("a", "b", "c"), pulled.map { it.op.opId })
        assertEquals(true, pulled.all { it.op.value == FieldValue.Null })
    }

    @Test
    fun aLateOlderSpotIsForgottenAndTheNewerStays() {
        val store = InMemoryServerOpStore()
        val sync = SyncService(store)
        sync.push(PushRequest("h", "fold", listOf(spot("new", 5, FieldValue.Text("new")))))
        sync.push(PushRequest("h", "fold", listOf(spot("old", 2, FieldValue.Text("old")))))
        assertEquals(FieldValue.Text("new"), store.find("h", "new")!!.value)
        assertEquals(FieldValue.Null, store.find("h", "old")!!.value)
    }

    @Test
    fun aRetryOfAForgottenOpIsAcknowledgedAndOtherFieldsAreKept() {
        val store = InMemoryServerOpStore()
        val sync = SyncService(store)
        val a = spot("a", 1, FieldValue.Text("one"))
        val note = spot("n", 1, FieldValue.Text("RingGo"), field = "parkingNote")
        val note2 = spot("n2", 2, FieldValue.Text("RingGo 2"), field = "parkingNote")
        sync.push(PushRequest("h", "fold", listOf(a, note, note2, spot("b", 2, FieldValue.Text("two")))))
        val r = sync.push(PushRequest("h", "fold", listOf(a)))
        assertEquals(listOf("a"), r.acknowledged)
        assertEquals(emptyMap(), r.rejected)
        assertEquals(FieldValue.Text("RingGo"), store.find("h", "n")!!.value, "a field not in the list keeps its history")
    }
}
