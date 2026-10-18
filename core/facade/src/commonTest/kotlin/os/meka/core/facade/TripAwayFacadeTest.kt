package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Travel mode completeness: a trip's days on both apps are away days — no commute, and the brief says where. */
class TripAwayFacadeTest {
    private var now = 1_792_317_600_000L // Sun 18 Oct 2026, 11:00 London
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore()))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 7 else 8), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aTripMondayHasNoCommuteAndTheBriefSaysWhereOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(!fold.addTrip("Barcelona 19–21 Oct").refused)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        now += 20 * 3_600_000L // Mon 19 Oct, 07:00
        val mon = 20745L // Monday 19 October 2026
        for (c in listOf(fold, mac)) {
            c.tick()
            val days = c.workMode.value.commuteDays.map { it.epochDay }
            assertTrue(days.none { it in mon..mon + 2 }, "no commute while away: $days")
            assertTrue(mon + 3 in days, "Thursday is back at the office: $days")
            val b = c.briefView.value
            assertEquals("Barcelona", b.story.away)
            assertTrue(b.workLine.orEmpty().startsWith("Work "), b.workLine)
            assertTrue(!b.story.officeDay)
        }
    }
}
