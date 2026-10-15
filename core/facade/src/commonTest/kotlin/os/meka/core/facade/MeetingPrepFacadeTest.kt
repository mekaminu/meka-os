package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.MeetingPrepLead
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Meeting prep, slice 2: Mail's Meeting prep lead time (or Off), synced between the Fold and the Mac. */
class MeetingPrepFacadeTest {
    private var now = 1_792_225_800_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theLeadIsSyncedAndAnUnknownKeyChangesNothing() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(MeetingPrepLead.FIFTEEN, mac.meetingPrepLeadSetting())
        assertEquals("Meeting prep 30 min before each meeting with other people", fold.setMeetingPrepLead(MeetingPrepLead.THIRTY))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals("30", mac.meetingPrepLeadKey())
        assertEquals("Meeting prep 30 min before each meeting with other people", mac.setMeetingPrepLeadKey("45"))
        assertEquals("Meeting prep off", mac.setMeetingPrepLeadKey("off"))
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertEquals(MeetingPrepLead.OFF, fold.meetingPrepLeadSetting())
    }
}
