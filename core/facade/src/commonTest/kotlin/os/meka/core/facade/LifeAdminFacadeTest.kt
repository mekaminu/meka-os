package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.LifeAdminRules
import os.meka.core.domain.NoticeSource
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Life admin hour through the facade: set on the Fold, booked and gathered on the Mac, its heads-up at the hour. */
class LifeAdminFacadeTest {
    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theHourIsBookedOnBothAppsGathersTheJobsAndCanBeSkipped() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(LifeAdminRules.OFF_LINE, fold.lifeAdminView.value.summary)
        assertEquals("Saturdays from 10:00", fold.setLifeAdmin(6, 10 * 60))
        assertNull(fold.setLifeAdmin(6, 10 * 60))
        fold.capture("Call the GP", null)
        fold.capture("Buy milk", null)
        fold.syncNow(); mac.syncNow()

        val v = mac.lifeAdminView.value
        assertTrue(v.on)
        assertEquals("Saturdays from 10:00 · this week Sat 17 Oct 10:00–11:00 · 1 job", v.summary)
        assertEquals(listOf("Call the GP"), v.jobs.map { it.title })
        val sat = v.thisWeek!!

        // On Saturday morning Today says it, and at 10:00 the heads-up lists the job.
        now = sat.startMs - 2 * 3_600_000L
        mac.tick()
        assertEquals("Life admin hour at 10:00 · 1 job", mac.today.value.lifeAdmin?.text)
        now = sat.startMs + 60_000L
        mac.tick()
        val g = mac.governNotifications(null, DeviceAlerts.ALL)
        val n = g.post.single { it.source == NoticeSource.LIFE_ADMIN }
        assertEquals("Life admin hour · 1 job", n.title)
        assertEquals("Call the GP", n.text)

        // Skipped from the Fold: no line, no heads-up, and the Mac's view says next week.
        now = sat.startMs - 2 * 3_600_000L
        assertNotNull(fold.skipLifeAdmin(true))
        assertNull(fold.skipLifeAdmin(true))
        fold.syncNow(); mac.syncNow()
        assertTrue(mac.lifeAdminView.value.skipped)
        assertNull(mac.today.value.lifeAdmin)
        assertTrue(mac.governNotifications(null, DeviceAlerts.ALL).post.none { it.source == NoticeSource.LIFE_ADMIN })
        assertTrue(mac.lifeAdminOff())
        assertFalse(mac.lifeAdminOff())
    }
}
