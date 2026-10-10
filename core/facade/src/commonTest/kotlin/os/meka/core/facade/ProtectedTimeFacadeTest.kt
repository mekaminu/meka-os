package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ProtectedRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Protected time through the facade (slice 1): set on one app, seen on the other, kept free of tasks by Plan my day. */
class ProtectedTimeFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun protectedTimeIsSetOnOneAppAndPlanMyDayKeepsTasksOut() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(ProtectedRules.OFF_LINE, fold.protectedView.value.summary)
        assertFalse(fold.protectedView.value.on)
        fold.addTask("Sort the garage")
        fold.tick()
        // Off: Saturday is the planner's like any day.
        assertEquals(listOf("Sort the garage"), fold.planDay().placements.map { it.task.title })

        assertEquals(
            "Evenings from 19:00, weekends and bank holidays · nothing is planned into them",
            fold.setProtectedTime(evenings = true, fromMin = 19 * 60, weekends = true),
        )
        assertNull(fold.setProtectedTime(evenings = true, fromMin = 19 * 60, weekends = true)) // nothing changed
        fold.syncNow(); mac.syncNow()
        mac.tick()
        val v = mac.protectedView.value
        assertTrue(v.on && v.evenings && v.weekends)
        assertEquals(19 * 60, v.fromMin)
        assertEquals("Today is protected · tasks wait for Mon 12 Oct", v.todayLine)

        // Saturday: nothing planned, the garage waits for Monday.
        val sat = mac.planDay()
        assertTrue(sat.placements.isEmpty())
        assertEquals("Waits for Mon 12 Oct: Sort the garage", sat.unplacedLine)
        assertTrue(sat.keptLine.orEmpty().contains("Protected time"), "the note under the plan says so")

        // Monday 18:00 in London: whatever fits goes before the protected evening; the rest won't fit today.
        now += 2 * 86_400_000L + 8 * 3_600_000L
        mac.tick()
        assertEquals("Protected from 19:00 tonight", mac.protectedView.value.todayLine)
        mac.addTask("Call the bank")
        mac.addTask("Book the MOT")
        val mon = mac.planDay()
        val sevenPm = now + 3_600_000L
        assertTrue(mon.placements.all { it.endMs <= sevenPm }, "nothing planned after 19:00")
        assertTrue(mon.unplaced.isNotEmpty())
        assertTrue(mon.unplacedLine.orEmpty().startsWith("Won't fit today: "))
        assertEquals("Protected time: nothing is planned after 19:00.", mon.keptLine?.lines()?.last())

        // Turned off on the Mac: the Fold's evening is open again.
        assertEquals(ProtectedRules.OFF_LINE, mac.setProtectedTime(evenings = false, fromMin = 19 * 60, weekends = false))
        mac.syncNow(); fold.syncNow()
        fold.tick()
        assertFalse(fold.protectedView.value.on)
        assertNull(fold.protectedView.value.todayLine)
    }
}
