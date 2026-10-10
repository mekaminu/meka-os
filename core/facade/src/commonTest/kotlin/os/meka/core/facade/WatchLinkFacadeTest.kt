package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ActivityKind
import os.meka.core.domain.WatchLinkRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.DeviceLinkCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings → Watch through the facade (Galaxy Watch, slice 1): type the watch's code, see it linked, unlink it. */
class WatchLinkFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London
    private val watchId = "watch0123456789abcdef"

    private inner class Server(service: SyncService) : SyncTransport, DeviceLinkApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val watches = mutableListOf<DeviceLinkCodec.Watch>()
        val codes = mutableListOf<String>()
        var down = false
        var refuse: String? = null
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        private fun check() {
            if (down) throw TransportException("offline")
            refuse?.let { throw LinkRefusedException(it) }
        }
        override suspend fun linkWatch(code: String): DeviceLinkCodec.Linked {
            check()
            codes += code
            watches.add(0, DeviceLinkCodec.Watch(watchId, "Galaxy Watch", now))
            return DeviceLinkCodec.Linked(watchId, "Galaxy Watch")
        }
        override suspend fun linkedWatches(): List<DeviceLinkCodec.Watch> { check(); return watches.toList() }
        override suspend fun unlinkWatch(id: String) {
            check()
            if (watches.none { it.id == id }) throw LinkRefusedException(DeviceLinkCodec.ERR_UNKNOWN)
            watches.removeAll { it.id == id }
        }
    }

    private val service = SyncService(InMemoryServerOpStore())
    private val server = Server(service)

    private fun core(transport: SyncTransport? = server) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun typeTheCodeSeeItLinkedAndUnlinkIt() = runTest {
        val c = core()
        assertNull(c.watchLinkView.value)
        assertEquals("No watch linked yet", c.refreshWatches().summary)

        // A code that isn't 8 digits never reaches the server.
        assertFalse(c.linkWatch("1234"))
        assertEquals(WatchLinkRules.NOT_A_CODE, c.watchLinkView.value!!.problem)
        assertTrue(server.codes.isEmpty())

        assertTrue(c.linkWatch("1234 5678"))
        assertEquals(listOf("12345678"), server.codes)
        val linked = c.watchLinkView.value!!
        assertNull(linked.problem)
        assertEquals("Galaxy Watch is linked · it shows Up next and takes Done", linked.summary)
        assertEquals("Linked today", linked.rows.single().line)
        val activity = c.activityView.value.days.flatMap { it.rows }.filter { it.kind == ActivityKind.DEVICE }.map { it.summary }
        assertEquals(listOf("Linked Galaxy Watch"), activity)

        assertTrue(c.unlinkWatch(watchId))
        assertEquals("No watch linked yet", c.watchLinkView.value!!.summary)
        assertTrue(server.watches.isEmpty())
        assertEquals(
            setOf("Linked Galaxy Watch", "Unlinked Galaxy Watch"),
            c.activityView.value.days.flatMap { it.rows }.filter { it.kind == ActivityKind.DEVICE }.map { it.summary }.toSet(),
        )
        // Unlinking one the server no longer knows is fine (already gone) and logs nothing more.
        assertTrue(c.unlinkWatch(watchId))
        assertEquals(2, c.activityView.value.days.flatMap { it.rows }.count { it.kind == ActivityKind.DEVICE })
    }

    @Test
    fun refusalsAndProblemsReadInWordsAndTheLastRowsStay() = runTest {
        val c = core()
        c.linkWatch("12345678")
        server.refuse = DeviceLinkCodec.ERR_CODE
        assertFalse(c.linkWatch("87654321"))
        assertEquals(WatchLinkRules.WRONG_CODE, c.watchLinkView.value!!.problem)
        assertEquals(1, c.watchLinkView.value!!.rows.size)
        server.refuse = DeviceLinkCodec.ERR_WAIT
        assertFalse(c.linkWatch("87654321"))
        assertEquals(WatchLinkRules.TOO_MANY, c.watchLinkView.value!!.problem)
        server.refuse = null
        server.down = true
        assertEquals(WatchLinkRules.OFFLINE, c.refreshWatches().problem)
        assertEquals(1, c.watchLinkView.value!!.rows.size)
        server.down = false
        assertNull(c.refreshWatches().problem)

        // A device that isn't connected says so.
        val offline = core(transport = null)
        assertEquals(WatchLinkRules.NOT_CONNECTED, offline.refreshWatches().problem)
        assertFalse(offline.linkWatch("12345678"))
    }
}
