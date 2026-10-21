package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CarryChoice
import os.meka.core.domain.Lifecycle
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import os.meka.core.testing.FaultyTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board and roll-over, slice 1: a task planned for an earlier day leads Today under From yesterday, on both apps. */
class RollOverFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London
    private val service = SyncService(InMemoryServerOpStore())
    private val day = 86_400_000L

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = FaultyTransport(service),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aTaskPlannedEarlierCarriesThenThePromptAsksOnceAndSomedayUndoes() = runTest {
        val fold = core("android"); val mac = core("mac")
        val id = fold.addTask("Book MOT")
        fold.schedule(id, now + 60 * 60_000L) // today, 18:15
        assertTrue(fold.today.value.fromYesterday.isEmpty())

        now += day
        fold.tick()
        val first = fold.today.value.fromYesterday.single()
        assertEquals(id, first.task.id)
        assertEquals("Carried from yesterday", first.line)
        assertFalse(first.asks)
        assertTrue(fold.today.value.yourDay.none { it.id == id } && fold.today.value.upNext?.id != id)

        now += 2 * day
        fold.tick()
        assertTrue(fold.today.value.fromYesterday.single().asks)
        assertEquals("Moved “Book MOT” to Someday", fold.answerCarried(id, CarryChoice.SOMEDAY))
        assertTrue(fold.today.value.fromYesterday.isEmpty())
        assertTrue(fold.undoCarried(id))
        assertTrue(fold.today.value.fromYesterday.single().asks)
        assertFalse(fold.undoCarried(id))

        assertNull(fold.answerCarried(id, CarryChoice.KEEP))
        val kept = fold.today.value.fromYesterday.single()
        assertEquals("Carried 3 days", kept.line)
        assertFalse(kept.asks)
        assertEquals(Lifecycle.ACTIVE, kept.task.lifecycle)

        // The Mac sees the same row and the prompt already answered.
        fold.syncNow(); mac.syncNow()
        val onMac = mac.today.value.fromYesterday.single()
        assertEquals(id, onMac.task.id)
        assertFalse(onMac.asks)

        // Moving it to today ends the carry.
        mac.schedule(id, now + 30 * 60_000L)
        assertTrue(mac.today.value.fromYesterday.isEmpty())
    }

    @Test
    fun dropCancelsItWithUndo() = runTest {
        val c = core("android")
        val id = c.addTask("Fix the gate")
        c.schedule(id, now + 60 * 60_000L)
        now += 4 * day
        c.tick()
        assertEquals("Dropped “Fix the gate”", c.answerCarried(id, CarryChoice.DROP))
        assertTrue(c.today.value.fromYesterday.isEmpty())
        assertTrue(c.today.value.doneToday.none { it.id == id })
        assertTrue(c.undoCarried(id))
        assertEquals(id, c.today.value.fromYesterday.single().task.id)
    }
}
