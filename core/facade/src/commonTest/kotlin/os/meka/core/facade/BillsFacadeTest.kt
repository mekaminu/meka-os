package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.NoticeAction
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Bills due through the facade: a bill and its pay link set on the Fold remind on the Mac too, not in the digest. */
class BillsFacadeTest {
    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aBillWithItsPayLinkRemindsOnBothAppsWithPay() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val water = fold.addRenewal("Anglian Water", ObligationKind.BILL, fold.todayEpochDay() + 3, RenewalRepeat.MONTHLY, "32.50", null)
        assertFalse(fold.setRenewalPayLink(water, "anglian water"))
        assertFalse(fold.setRenewalPayLink("gone", "anglianwater.co.uk"))
        assertTrue(fold.setRenewalPayLink(water, "anglianwater.co.uk/account"))
        fold.syncNow(); mac.syncNow()
        assertEquals("https://anglianwater.co.uk/account", mac.listsView.value.renewals.all.single().payLink)

        val g = mac.governNotifications(null, DeviceAlerts.ALL)
        val bill = g.post.single { it.source == NoticeSource.BILLS }
        assertEquals("Anglian Water due Thu 15 Oct · £32.50", bill.title)
        assertEquals("In 3 days · Pay opens anglianwater.co.uk", bill.text)
        assertEquals(listOf(NoticeAction.PAY), bill.actions)
        assertEquals("https://anglianwater.co.uk/account", bill.link)
        assertTrue(g.digest?.lines.orEmpty().none { it.startsWith("Anglian") })
        assertTrue(mac.governNotifications(g.stateEncoded, DeviceAlerts.ALL).post.none { it.source == NoticeSource.BILLS })
    }
}
