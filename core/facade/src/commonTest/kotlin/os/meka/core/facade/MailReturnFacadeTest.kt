package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.MailReturnRules
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Returns and warranties, completeness: Sent it back waits for the refund, Keeping it just clears; heads-ups on both. */
class MailReturnFacadeTest {
    private var now = 1_792_225_800_000L // Wed 14 Oct 2026, 09:30 London
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun sentItBackPutsTheRefundOnWaitingForOnBothDevices() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val today = fold.todayEpochDay()
        val id = fold.addRenewal("Last day to return · Amazon order", ObligationKind.OTHER, today + 2, RenewalRepeat.NONE, "40", null)
        val keep = fold.addRenewal("Last day to return · Next order", ObligationKind.OTHER, today + 2, RenewalRepeat.NONE, null, null)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())

        // The heads-up two days before, one per order, on both devices.
        assertEquals(2, mac.noticesNow().count { it.source == NoticeSource.RETURNS })
        assertTrue(mac.noticesNow().none { it.source == NoticeSource.RENEWAL && it.title.startsWith("Last day") })
        val item = mac.listsView.value.renewals.all.single { it.id == id }
        assertEquals("Sent it back", item.doneLabel)
        assertEquals("Keeping it", item.stopLabel)

        mac.renewalDone(id)
        mac.stopRenewal(keep)
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertTrue(fold.listsView.value.renewals.all.none { it.id == id || it.id == keep })
        val wait = fold.listsView.value.waiting.single()
        assertEquals(MailReturnRules.refundWaitId(id), wait.id)
        assertEquals("Refund · Amazon order · £40", wait.title)
        assertEquals("Amazon", wait.who)
        assertTrue(fold.noticesNow().none { it.source == NoticeSource.RETURNS })
    }
}
