package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EmergencyAdded
import os.meka.core.domain.EmergencyRules
import os.meka.core.domain.EmergencyView
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The emergency card through the facade (slice 1): lines typed on one app reach the other; secrets never land. */
class EmergencyCardFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun linesTypedOnTheFoldReachTheMacAndSecretsAreRefused() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(EmergencyView.EMPTY.summary, fold.emergencyView.value.summary)
        assertEquals(EmergencyAdded("Added GP to Who to call", false), fold.addEmergency("call", "GP: Dr Patel, 01767 123456"))
        now += 1_000
        assertEquals(EmergencyAdded("Added Home insurance to Key accounts", false), fold.addEmergency("accounts", "Home insurance: Aviva"))
        assertEquals(EmergencyAdded(EmergencyRules.REFUSED_SECRET, true), fold.addEmergency("accounts", "Bank PIN: 4821"))
        assertEquals(2, fold.emergencyView.value.count)

        fold.syncNow(); mac.syncNow()
        val v = mac.emergencyView.value
        assertEquals("2 lines · Jeanette sees it read-only on her family page", v.summary)
        val gp = v.groups.first { it.section == "call" }.rows.single()
        assertEquals("01767123456", gp.phone)

        assertTrue(mac.removeEmergency(gp.id))
        mac.syncNow(); fold.syncNow()
        assertEquals(1, fold.emergencyView.value.count)
        assertTrue(fold.emergencyView.value.groups.first { it.section == "call" }.rows.isEmpty())
    }
}
