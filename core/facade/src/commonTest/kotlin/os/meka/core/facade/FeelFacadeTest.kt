package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DeviceHealth
import os.meka.core.domain.FeelEntry
import os.meka.core.domain.FeelMoment
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Taps feel instant, part (4), through the facade: the apps hand in cold start, tab and first-content times without
 * waiting on the core, and Health → Speed leads with the one furthest over its aim.
 */
class FeelFacadeTest {
    private var now = 1_792_144_800_000L

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(3), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val fold = DeviceHealth(mac = false, batteryExempt = true, notificationAccess = true, lastNotificationMs = now, callRoleHeld = true, version = "0.1.300")

    @Test
    fun theAppsTimesReachHealthSpeed() = runTest {
        val c = core()
        c.noteFeel(FeelMoment.COLD_START, 1_200)
        c.noteFeel(FeelMoment.TAB, 90, "Calendar")
        c.noteFeel(FeelMoment.TAB, 420, "Ask")
        c.noteFeel(FeelMoment.SCREEN, 60_000_000, "Money") // the phone slept: dropped
        c.noteFeel(FeelMoment.SCREEN, 80, "Money")
        val all = c.feelAll()
        assertEquals(
            listOf(
                FeelEntry(FeelMoment.COLD_START, 1_200, null, 1_200, 1, 0),
                FeelEntry(FeelMoment.TAB, 420, "Ask", 420, 2, 1),
                FeelEntry(FeelMoment.SCREEN, 80, "Money", 80, 1, 0),
            ),
            all,
        )
        val speed = c.refreshHealth(fold).rows.first { it.key == "speed" }
        assertEquals("Slower than it should be: Tab switch · 420 ms (aim 150 ms)", speed.line)
        assertTrue(speed.detail!!.startsWith("How it feels · last day\nCold start to Today · 1.2 s · aim 1.5 s\n"), speed.detail)
    }
}
