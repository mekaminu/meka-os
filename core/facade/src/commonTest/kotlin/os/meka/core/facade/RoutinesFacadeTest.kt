package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.RoutineRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Routines through the facade (slice 1): a starter added on the Fold, paused on the Mac, removed, on both apps. */
class RoutinesFacadeTest {
    private val now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aStarterAddedOnTheFoldIsPausedOnTheMacAndRemoved() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(RoutineRules.EMPTY_LINE, fold.routinesView.value.summary)
        assertEquals(4, fold.routinesView.value.starters.size)
        assertNull(fold.addStarterRoutine("nope"))
        assertEquals("Added Sunday reset · Every Sunday at 19:00", fold.addStarterRoutine("sunday-reset")?.line)
        assertTrue(fold.addStarterRoutine("sunday-reset")!!.refused)
        val row = fold.routinesView.value.rows.single()
        assertEquals("Next: tomorrow", row.next)
        assertEquals(3, fold.routinesView.value.starters.size)

        fold.syncNow(); mac.syncNow()
        val onMac = mac.routinesView.value.rows.single()
        assertEquals("Sunday reset", onMac.name)
        assertTrue(mac.setRoutineOn(onMac.id, false))
        assertFalse(mac.setRoutineOn(onMac.id, false))
        mac.syncNow(); fold.syncNow()
        assertFalse(fold.routinesView.value.rows.single().on)
        assertEquals("All paused", fold.routinesView.value.summary)

        // Slice 2: a step's label changed on the Fold shows on the Mac's page.
        assertFalse(fold.setRoutineStepMode(row.id, 0, "does"))
        assertFalse(fold.setRoutineStepMode(row.id, 0, "nope"))
        assertTrue(fold.setRoutineStepMode(row.id, 0, "ask"))
        fold.syncNow(); mac.syncNow()
        assertEquals("Asks you", mac.routinesView.value.detail(row.id)?.steps?.first()?.badge)
        assertTrue(fold.removeRoutine(row.id))
        fold.syncNow(); mac.syncNow()
        assertTrue(mac.routinesView.value.isEmpty)
        assertEquals(4, mac.routinesView.value.starters.size)
    }
}
