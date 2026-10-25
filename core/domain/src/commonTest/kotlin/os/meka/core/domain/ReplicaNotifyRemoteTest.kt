package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Taps feel instant, slice 4: the replica's listeners can tell another device's changes from this device's own. */
class ReplicaNotifyRemoteTest {
    @Test
    fun listenersSeeWhetherAChangeCameFromAnotherDevice() {
        var n = 0
        val a = Replica("hh", "a", HlcClock("a", { 1_000L }), InMemoryReplicaStore(), MekaSchema) { "a" + n++ }
        val b = Replica("hh", "b", HlcClock("b", { 1_000L }), InMemoryReplicaStore(), MekaSchema) { "b" + n++ }
        val seen = mutableListOf<Boolean>()
        b.addListener { seen += b.notifyingRemote }
        val ops = a.commitLocal("task", "t1", mapOf("title" to FieldValue.Text("Ring the garage")))
        b.commitLocal("task", "t2", mapOf("title" to FieldValue.Text("Book the MOT")))
        b.applyRemoteBatch(ops)
        assertEquals(listOf(false, true), seen)
        assertFalse(b.notifyingRemote)
    }
}
