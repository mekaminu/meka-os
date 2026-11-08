package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CallbackReminderRules
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obi on calls: Remind me on a call back Obi agreed makes one task with a reminder, the same on the Fold and the Mac. */
class CallbackReminderFacadeTest {
    private var now = 1_793_181_600_000L // Wed 28 Oct 2026, 10:00 in London
    private val server = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore()))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val hour = 60 * 60_000L
    private fun item(backAt: Long?) = CapturedItem(
        id = "twilio:CB1", app = CaptureApp.PHONE, kind = CaptureKind.VOICE_MESSAGE, personName = "Sam", text = "About the boiler",
        conversation = null, atMs = now - hour, callback = backAt?.let { "call back Thu 29 Oct, 10:30" }, callbackAtMs = backAt,
    )

    @Test
    fun remindMeMakesOneTaskOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val back = now + 24 * hour + 30 * 60_000L // Thu 29 Oct, 10:30
        val msg = item(back)
        assertEquals(CallbackReminderRules.REMIND_ME, assertNotNull(fold.callbackChip(msg)).label)
        assertEquals("Reminder set · Tomorrow · 10:20", fold.remindCallback(msg))
        val chip = assertNotNull(fold.callbackChip(msg))
        assertTrue(chip.done)
        assertEquals("Reminder set · Tomorrow · 10:20", chip.label)
        fold.syncNow(); mac.syncNow()
        assertEquals("Reminder set · Tomorrow · 10:20", assertNotNull(mac.callbackChip(msg)).label)
        // A second tap on the Mac keeps the one task.
        assertEquals("Reminder set · Tomorrow · 10:20", mac.remindCallback(msg))
        // No call back agreed: nothing to offer, and a tap says it's gone.
        assertNull(fold.callbackChip(item(null).copy(id = "twilio:CB2")))
        assertEquals(CallbackReminderRules.GONE, fold.remindCallback(item(null).copy(id = "twilio:CB2")))
    }
}
