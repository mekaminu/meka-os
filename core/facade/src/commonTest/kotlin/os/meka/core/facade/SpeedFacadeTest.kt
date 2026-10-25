package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Taps feel instant, slices 3 and 5a, through the facade: with Money open, a tap elsewhere (a new task) no longer
 * rebuilds the money view over 3,000 payments; a money change, the minute turning or opening it again does. Every
 * whole-app refresh is timed into the Speed log, which Health shows.
 */
class SpeedFacadeTest {
    private var now = 1_792_144_800_000L // Fri 16 Oct 2026, 11:00 in London

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())),
        secureRandom = Random(7), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun statement(n: Int): String = buildString {
        append("Date,Description,Amount\n")
        val shops = listOf("TESCO STORES 2041", "COSTA COFFEE", "TFL TRAVEL", "AMAZON MARKETPLACE", "SHELL 4411", "PRET A MANGER")
        for (i in 0 until n) append((1 + i % 15).toString().padStart(2, '0')).append("/10/2026,").append(shops[i % shops.size])
            .append(" ").append(i).append(",-").append(1 + i % 40).append(".25\n")
    }

    @Test
    fun aTapElsewhereKeepsTheMoneyViewAndAMoneyChangeRebuildsIt() = runTest(timeout = 5.minutes) {
        val c = core()
        c.openMoney()
        assertFalse(c.importMoneyStatement("Current account", statement(3_000)).refused)
        val built = c.moneyView.value
        val builds = c.moneyViewBuilds
        val passes = c.refreshPasses

        c.addTask("Ring the garage")
        assertTrue(c.refreshPasses > passes, "the tap still refreshed the app")
        assertEquals(builds, c.moneyViewBuilds, "a new task doesn't rebuild Money")
        assertTrue(built === c.moneyView.value)

        // A money change does.
        c.importMoneyStatement("Current account", statement(3_001))
        assertTrue(c.moneyViewBuilds > builds)

        // So does a new minute ("synced 5 min ago" moves on).
        val afterImport = c.moneyViewBuilds
        now += 60_000
        c.addTask("Book the MOT")
        assertEquals(afterImport + 1, c.moneyViewBuilds)

        // Locking and opening again builds afresh, never the old view behind the lock.
        c.closeMoney()
        assertTrue(c.moneyView.value.locked)
        c.openMoney()
        assertFalse(c.moneyView.value.locked)
        assertEquals(afterImport + 2, c.moneyViewBuilds)
        assertEquals("October so far", c.moneyView.value.month!!.label)
    }

    @Test
    fun everyRefreshIsTimedAndHealthCanListTheSlowest() = runTest(timeout = 5.minutes) {
        val c = core()
        c.openMoney()
        c.importMoneyStatement("Current account", statement(500))
        c.addTask("Ring the garage")
        val worst = c.speedWorst()
        val names = worst.map { it.name }
        assertTrue("Whole-app refresh" in names, names.toString())
        assertTrue("Money view" in names, names.toString())
        assertTrue("Search" in names, names.toString())
        assertTrue(worst.size <= 10)
        assertEquals(worst.sortedByDescending { it.worstMs }.map { it.worstMs }, worst.map { it.worstMs })
        assertTrue(worst.first { it.name == "Whole-app refresh" }.count >= 2)
    }
}
