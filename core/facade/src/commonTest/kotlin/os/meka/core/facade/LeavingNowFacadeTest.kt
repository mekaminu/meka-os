package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.LeavingTap
import os.meka.core.domain.NoticeSource
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Leaving now" through the facade: due when Monday's work ends, Send only with the shown words, switched off on the Mac. */
class LeavingNowFacadeTest {
    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theDraftArrivesWhenWorkEndsAndSendCarriesItsWords() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals("On · a draft to Jeanette when work ends on office days · about 70 min home", fold.leavingView.value.summary)
        // Morning: nothing posts yet, but the governor plans to wake for it.
        val morning = fold.governNotifications(null, DeviceAlerts.ALL)
        assertTrue(morning.post.none { it.source == NoticeSource.LEAVING })

        now += 7 * 3_600_000L + 30 * 60_000L // 17:30
        val r = fold.governNotifications(morning.stateEncoded, DeviceAlerts.ALL)
        val n = r.post.single { it.source == NoticeSource.LEAVING }
        assertEquals("Leaving now, home about 18:40", n.text)
        assertEquals(LeavingTap.Send("Jeanette", n.text), fold.leavingTap(n.key, n.text, edit = false))

        // Set on the Fold, switched off on the Mac: no draft, and an old notification's Send does nothing.
        assertEquals("On · a draft to Wife when work ends on office days · about 80 min home", fold.setLeavingNow(true, "Wife", 80))
        assertNull(fold.setLeavingNow(true, "Wife", 80))
        fold.syncNow(); mac.syncNow()
        assertEquals("Wife", mac.leavingView.value.to)
        mac.setLeavingNow(false, "Wife", 80)
        mac.syncNow(); fold.syncNow()
        assertTrue(fold.governNotifications(null, DeviceAlerts.ALL).post.none { it.source == NoticeSource.LEAVING })
        assertNull(fold.leavingTap(n.key, n.text, edit = false))
    }
}
