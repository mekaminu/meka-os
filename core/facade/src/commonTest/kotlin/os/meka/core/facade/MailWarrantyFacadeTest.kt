package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.MailWarrantyLevel
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Inbox autopilot, slice 3f: Mail's Warranty reminders threshold, synced between the Fold and the Mac. */
class MailWarrantyFacadeTest {
    private var now = 1_792_225_800_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theThresholdIsSyncedAndAnUnknownKeyChangesNothing() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(MailWarrantyLevel.HUNDRED, mac.mailWarranty())
        assertEquals("Warranty reminders for orders of £250 or more", fold.setMailWarranty(MailWarrantyLevel.TWO_FIFTY))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals("250", mac.mailWarrantyKey())
        assertEquals("Warranty reminders for orders of £250 or more", mac.setMailWarrantyKey("1000"))
        assertEquals("Warranty reminders off · reminders already on Renewals stay", mac.setMailWarrantyKey("off"))
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertEquals(MailWarrantyLevel.OFF, fold.mailWarranty())
    }
}
