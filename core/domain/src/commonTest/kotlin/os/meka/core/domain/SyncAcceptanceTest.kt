package os.meka.core.domain

import os.meka.core.sync.TransportException
import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M0 acceptance tests from the programme brief (Stage 8), at the core level. */
class SyncAcceptanceTest {

    @Test
    fun onlineThenOfflineEditThenOnlineConverges() {
        val w = SyncWorld()
        val android = w.device("android")
        val mac = w.device("mac")

        val id = android.tasks.create(NewTask("Buy Rex new boots"))
        android.sync(); mac.sync()
        assertEquals("Buy Rex new boots", mac.tasks.get(id)!!.title)

        android.goOffline()
        w.clock.advance(60_000)
        android.tasks.edit(id, TaskEdit(title = "Buy Rex new boots (size 4)"))
        android.tasks.complete(id)
        assertFailsWith<TransportException> { android.sync() }
        // title + lifecycle + completedAt
        assertEquals(3, android.replica.pendingPushCount(), "edits are queued, not lost")
        assertTrue(android.tasks.get(id)!!.isDone, "local state is immediately correct offline")

        android.goOnline()
        android.syncWithRetry(); mac.sync()
        assertEquals(0, android.replica.pendingPushCount())
        assertEquals(android.tasks.get(id), mac.tasks.get(id))
        assertTrue(mac.tasks.get(id)!!.isDone)
    }

    @Test
    fun bothOfflineConflictingEditsSurfaceOneConflictAndConvergeAfterResolution() {
        val w = SyncWorld()
        val android = w.device("android")
        val mac = w.device("mac", skewMs = 3_000) // Mac clock 3s fast: HLC must still behave
        val id = mac.tasks.create(NewTask("Call school"))
        mac.sync(); android.sync()

        android.goOffline(); mac.goOffline()
        w.clock.advance(10_000)
        android.tasks.edit(id, TaskEdit(title = "Call school about trip"))
        mac.tasks.edit(id, TaskEdit(title = "Email school about trip", notes = "form attached"))
        android.tasks.edit(id, TaskEdit(estimateMinutes = 10))

        android.goOnline(); mac.goOnline()
        android.syncWithRetry(); mac.syncWithRetry(); android.syncWithRetry()

        val a = android.tasks.get(id)!!
        val m = mac.tasks.get(id)!!
        assertEquals(a, m, "both devices converge to the same state")
        assertTrue(a.hasConflict, "meaningful conflict (title) is user-visible")
        assertEquals("form attached", a.notes, "non-conflicting fields merge")
        assertEquals(10, a.estimateMinutes)
        val conflict = android.tasks.conflicts().single()
        assertEquals(ActionableFields.TITLE, conflict.key.field)

        android.tasks.resolveConflict(conflict, "Call school about trip".fv())
        android.sync(); mac.sync()
        assertTrue(mac.tasks.conflicts().isEmpty())
        assertEquals("Call school about trip", mac.tasks.get(id)!!.title)
        assertEquals(android.tasks.get(id), mac.tasks.get(id))
    }

    @Test
    fun concurrentCompleteAndReopenResolvesToDoneWithoutConflict() {
        val w = SyncWorld()
        val android = w.device("android")
        val mac = w.device("mac")
        val id = android.tasks.create(NewTask("Renew car tax"))
        android.sync(); mac.sync()

        android.goOffline()
        android.tasks.complete(id) // done on the phone, offline
        w.clock.advance(1_000)
        mac.tasks.complete(id)
        mac.tasks.reopen(id) // Mac undoes its own completion; it never saw the phone's
        mac.sync()
        android.goOnline(); android.syncWithRetry(); mac.sync()

        assertTrue(android.tasks.get(id)!!.isDone)
        assertEquals(android.tasks.get(id), mac.tasks.get(id))
        assertTrue(mac.tasks.conflicts().isEmpty(), "status uses TerminalWins, not a user-visible conflict")
    }

    @Test
    fun deleteBeatsConcurrentEdit() {
        val w = SyncWorld()
        val android = w.device("android")
        val mac = w.device("mac")
        val id = android.tasks.create(NewTask("Old idea"))
        android.sync(); mac.sync()
        android.goOffline()
        android.tasks.edit(id, TaskEdit(notes = "still thinking"))
        mac.tasks.delete(id)
        mac.sync(); android.goOnline(); android.syncWithRetry(); mac.sync()
        assertNull(android.tasks.get(id))
        assertNull(mac.tasks.get(id))
    }

    @Test
    fun retryStormProducesZeroDuplicates() {
        val w = SyncWorld()
        val android = w.device("android", batchSize = 7)
        val mac = w.device("mac")
        repeat(50) { android.tasks.create(NewTask("Task $it")) }
        val opsAuthored = android.store.opCount

        // The server commits every push but the response is lost 5 times in a row.
        android.transport.dropResponses = 5
        android.syncWithRetry(maxAttempts = 20)
        // A further paranoid re-push of everything must also be harmless.
        repeat(3) { android.sync() }

        assertEquals(opsAuthored, w.serverStore.size, "server stored each op exactly once")
        mac.sync()
        assertEquals(50, mac.tasks.all().size)
        assertEquals(android.tasks.all().map { it.id }.toSet(), mac.tasks.all().map { it.id }.toSet())
        assertEquals(0, android.replica.pendingPushCount())
    }

    @Test
    fun pullPaginatesAndCursorOnlyAdvancesAfterApply() {
        val w = SyncWorld()
        val android = w.device("android")
        repeat(1_200) { android.tasks.create(NewTask("T$it")) }
        android.sync()
        val mac = w.device("mac")
        val report = mac.sync()
        assertEquals(w.serverStore.size, report.pulled)
        assertEquals(1_200, mac.tasks.all().size)
        assertEquals(0, mac.sync().pulled, "second sync is a no-op")
    }

    @Test
    fun checklistItemsAddedOnBothDevicesUnion() {
        val w = SyncWorld()
        val android = w.device("android")
        val mac = w.device("mac")
        val id = android.tasks.create(NewTask("Logan football kit"))
        android.sync(); mac.sync()
        android.goOffline()
        android.tasks.addChecklistItem(id, "Boots")
        mac.tasks.addChecklistItem(id, "Water bottle")
        mac.sync(); android.goOnline(); android.syncWithRetry(); mac.sync()
        val texts = mac.tasks.get(id)!!.checklist.map { it.text }.toSet()
        assertEquals(setOf("Boots", "Water bottle"), texts)
        assertEquals(android.tasks.get(id), mac.tasks.get(id))
    }

    @Test
    fun opIdReuseWithDifferentContentIsRejectedNotMerged() {
        val w = SyncWorld()
        val android = w.device("android")
        android.tasks.create(NewTask("Legit"))
        android.sync()
        val stored = w.serverStore.after(w.householdId, 0, 1).single().op
        val forged = stored.copy(value = "Forged".fv())
        val resp = w.service.push(os.meka.core.sync.PushRequest(w.householdId, stored.deviceId, listOf(forged)))
        assertEquals("opId reused with different content", resp.rejected[forged.opId])
        val mac = w.device("mac"); mac.sync()
        assertNotNull(mac.tasks.all().singleOrNull { it.title == "Legit" })
    }
}
