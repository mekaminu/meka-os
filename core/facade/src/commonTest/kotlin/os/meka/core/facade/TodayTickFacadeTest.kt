package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.TodayTickRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import os.meka.core.testing.FaultyTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Week board by hand, slice 6 (Meka's check on the Fold): tick a task on Today and it stays on Today, ticked and
 * in the done group, the latest first; tap it again and it is open again — both synced to the Mac.
 */
class TodayTickFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London
    private val service = SyncService(InMemoryServerOpStore())

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = FaultyTransport(service),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun MekaCore.openIds(): List<String> = today.value.let { t -> listOfNotNull(t.upNext) + t.yourDay }.map { it.id }

    @Test
    fun aTickedTaskStaysOnTodayInTheDoneGroupAndUnticks() = runTest {
        val fold = core("android"); val mac = core("mac")
        val a = fold.addTask("Book MOT")
        now += 1_000L
        val b = fold.addTask("Fix the gate")
        assertTrue(a in fold.openIds() && b in fold.openIds())

        fold.complete(a)
        now += 60_000L
        fold.complete(b)
        fold.tick()
        assertFalse(a in fold.openIds())
        assertEquals(listOf(b, a), TodayTickRules.shown(fold.today.value.doneToday).map { it.id })
        assertEquals("2 done today", TodayTickRules.label(fold.today.value.doneToday))

        fold.syncNow(); mac.syncNow()
        assertEquals(listOf(b, a), mac.today.value.doneToday.map { it.id })

        // Tap the done row's circle: open again on Today, on both.
        fold.reopen(a)
        assertTrue(a in fold.openIds())
        assertEquals(listOf(b), fold.today.value.doneToday.map { it.id })
        fold.syncNow(); mac.syncNow()
        assertTrue(a in mac.openIds())
    }
}
